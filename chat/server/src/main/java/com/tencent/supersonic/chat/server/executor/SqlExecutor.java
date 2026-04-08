package com.tencent.supersonic.chat.server.executor;

import com.tencent.supersonic.chat.api.pojo.enums.MemoryStatus;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.pojo.ChatContext;
import com.tencent.supersonic.chat.server.pojo.ChatHistory;
import com.tencent.supersonic.chat.server.pojo.ChatMemory;
import com.tencent.supersonic.chat.server.pojo.ExecuteContext;
import com.tencent.supersonic.chat.server.service.ChatContextService;
import com.tencent.supersonic.chat.server.service.HistoryService;
import com.tencent.supersonic.chat.server.service.MemoryService;
import com.tencent.supersonic.chat.server.util.ResultFormatter;
import com.tencent.supersonic.common.pojo.Text2SQLExemplar;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.request.QuerySqlReq;
import com.tencent.supersonic.headless.api.pojo.response.QueryState;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMSqlQuery;
import com.tencent.supersonic.headless.server.facade.service.SemanticLayerService;
import dev.langchain4j.service.TokenStream;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
public class SqlExecutor implements ChatQueryExecutor {

    @Override
    public boolean accept(ExecuteContext executeContext) {
        return true;
    }

    @SneakyThrows
    @Override
    public QueryResult execute(ExecuteContext executeContext) {
        if (Objects.equals(executeContext.getParseInfo().getSqlInfo().getResultType(), "text")) {
            QueryResult queryResult = doExecute(executeContext);
            return queryResult;
        }
        QueryResult queryResult = doExecute(executeContext);

        if (queryResult != null) {
            if (queryResult.getQueryResults().isEmpty()) {
                queryResult.setQueryMode("PLAIN_TEXT");
                queryResult.setTextResult("当前时间周期暂无数据，请换个指标或者时间周期查询");
            } else {
                String textResult = ResultFormatter.transform2TextNew(queryResult.getQueryColumns(),
                        queryResult.getQueryResults());
                queryResult.setTextResult(textResult);
            }
            if (queryResult.getQueryState().equals(QueryState.SUCCESS)
                    && queryResult.getQueryMode().equals(LLMSqlQuery.QUERY_MODE)) {
                Text2SQLExemplar exemplar =
                        JsonUtil.toObject(
                                JsonUtil.toString(executeContext.getParseInfo().getProperties()
                                        .get(Text2SQLExemplar.PROPERTY_KEY)),
                                Text2SQLExemplar.class);

                MemoryService memoryService = ContextUtils.getBean(MemoryService.class);
                memoryService.createMemory(ChatMemory.builder().queryId(queryResult.getQueryId())
                        .agentId(executeContext.getAgent().getId()).status(MemoryStatus.PENDING)
                        .question(exemplar.getQuestion()).sideInfo(exemplar.getSideInfo())
                        .dbSchema(exemplar.getDbSchema()).s2sql(exemplar.getSql())
                        .createdBy(executeContext.getRequest().getUser().getName())
                        .updatedBy(executeContext.getRequest().getUser().getName())
                        .createdAt(new Date()).build());
            }
        }

        return queryResult;
    }

    @Override
    public TokenStream streamExecute(ExecuteContext executeContext) {
        return null;
    }

    @SneakyThrows
    private QueryResult doExecute(ExecuteContext executeContext) {
        SemanticLayerService semanticLayer = ContextUtils.getBean(SemanticLayerService.class);
        ChatContextService chatContextService = ContextUtils.getBean(ChatContextService.class);

        ChatContext chatCtx =
                chatContextService.getOrCreateContext(executeContext.getRequest().getChatId());

        SemanticParseInfo parseInfo = executeContext.getParseInfo();
        if (Objects.isNull(parseInfo.getSqlInfo())
                || StringUtils.isBlank(parseInfo.getSqlInfo().getCorrectedS2SQL())) {
            return null;
        }

        // 将setSchemaValueMaps的维度值信息清理了，减少Context数据存储
        parseInfo.getDimensions().forEach(schemaElement -> schemaElement.setSchemaValueMaps(null));
        if (parseInfo.getElementMatches() != null && !parseInfo.getElementMatches().isEmpty()) {
            parseInfo.getElementMatches().forEach(schemaElementMatch -> {
                if (schemaElementMatch.getElement() != null) {
                    schemaElementMatch.getElement().setSchemaValueMaps(null);
                }
            });
        }

        Map<String, Object> properties = parseInfo.getProperties();

        // 使用querySQL，它已经包含了所有修正（包括物理SQL修正）
        String finalSql = StringUtils.isNotBlank(parseInfo.getSqlInfo().getQuerySQL())
                ? parseInfo.getSqlInfo().getQuerySQL()
                : parseInfo.getSqlInfo().getCorrectedS2SQL();

        QuerySqlReq sqlReq = QuerySqlReq.builder().sql(finalSql).build();
        sqlReq.setSqlInfo(parseInfo.getSqlInfo());
        sqlReq.setDataSetId(parseInfo.getDataSetId());
        sqlReq.setQueryId(executeContext.getRequest().getQueryId());
        long sqlStart = System.currentTimeMillis();
        QueryResult queryResult = new QueryResult();
        queryResult.setQueryId(executeContext.getRequest().getQueryId());
        queryResult.setChatContext(parseInfo);
        queryResult.setQueryMode(parseInfo.getQueryMode());
        if (Objects.equals(parseInfo.getSqlInfo().getResultType(), "text")) {
            queryResult.setQueryMode("PLAIN_TEXT");
            queryResult.setQueryState(QueryState.SUCCESS);
            queryResult.setTextResult(parseInfo.getSqlInfo().getCorrectedS2SQL());
            return queryResult;
        }
        parseInfo.setProperties(null);
        try {
            SemanticQueryResp queryResp = semanticLayer.queryBySchemaStrValues(sqlReq,
                    executeContext.getRequest().getUser());
            long sqlCost = System.currentTimeMillis() - sqlStart;
            log.info("[PERFORMANCE] SQL执行耗时: {}ms, 返回行数: {}", sqlCost,
                    queryResp != null ? queryResp.getResultList().size() : 0);
            queryResult.setQueryTimeCost(sqlCost);
            if (queryResp != null) {
                queryResult.setQueryAuthorization(queryResp.getQueryAuthorization());
                queryResult.setQuerySql(finalSql);
                queryResult.setQueryResults(queryResp.getResultList());
                queryResult.setQueryColumns(queryResp.getColumns());
                queryResult.setQueryState(QueryState.SUCCESS);
                queryResult.setErrorMsg(queryResp.getErrorMsg());
                queryResult.setResultType(queryResp.getResultType());

                // updateContext 为纯写操作，异步化不阻塞 SQL 结果返回
                final ChatContext finalChatCtx = chatCtx;
                finalChatCtx.setParseInfo(parseInfo);
                ThreadPoolExecutor chatExecutor =
                        ContextUtils.getBean("chatExecutor", ThreadPoolExecutor.class);
                chatExecutor.execute(() -> {
                    long _s = System.currentTimeMillis();
                    chatContextService.updateContext(finalChatCtx);
                    log.info("[PERF-execute] updateContext(async): {}ms",
                            System.currentTimeMillis() - _s);
                });
            } else {
                queryResult.setQueryState(QueryState.INVALID);
            }
        } finally {
            parseInfo.setProperties(properties);
        }
        return queryResult;
    }
}
