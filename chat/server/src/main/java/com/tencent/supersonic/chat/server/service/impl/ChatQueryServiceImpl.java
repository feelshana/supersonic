package com.tencent.supersonic.chat.server.service.impl;

import com.google.common.collect.Lists;
import com.tencent.supersonic.chat.api.pojo.enums.JsqlParserType;
import com.tencent.supersonic.chat.api.pojo.enums.MemoryStatus;
import com.tencent.supersonic.chat.api.pojo.request.ChatExecuteReq;
import com.tencent.supersonic.chat.api.pojo.request.ChatParseReq;
import com.tencent.supersonic.chat.api.pojo.request.ChatQueryDataReq;
import com.tencent.supersonic.chat.api.pojo.request.TextVoiceReq;
import com.tencent.supersonic.chat.api.pojo.response.ChatParseResp;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.executor.ChatQueryExecutor;
import com.tencent.supersonic.chat.server.parser.ChatQueryParser;
import com.tencent.supersonic.chat.server.parser.NL2SQLParser;
import com.tencent.supersonic.chat.server.pojo.ChatHistory;
import com.tencent.supersonic.chat.server.pojo.ExecuteContext;
import com.tencent.supersonic.chat.server.pojo.ParseContext;
import com.tencent.supersonic.chat.server.processor.execute.DataInterpretProcessor;
import com.tencent.supersonic.chat.server.processor.execute.ExecuteResultProcessor;
import com.tencent.supersonic.chat.server.processor.parse.ParseResultProcessor;
import com.tencent.supersonic.chat.server.service.AgentService;
import com.tencent.supersonic.chat.server.service.ChatManageService;
import com.tencent.supersonic.chat.server.service.ChatQueryService;
import com.tencent.supersonic.chat.server.service.HistoryService;
import com.tencent.supersonic.chat.server.service.VoiceService;
import com.tencent.supersonic.chat.server.util.ComponentFactory;
import com.tencent.supersonic.chat.server.util.QueryReqConverter;
import com.tencent.supersonic.common.jsqlparser.*;
import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.DimensionConstants;
import com.tencent.supersonic.common.pojo.FileInfo;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.FilterOperatorEnum;
import com.tencent.supersonic.common.pojo.enums.Text2SQLType;
import com.tencent.supersonic.common.util.BeanMapper;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.common.util.DateUtils;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.api.pojo.*;
import com.tencent.supersonic.headless.api.pojo.request.DimensionValueReq;
import com.tencent.supersonic.headless.api.pojo.request.QueryFilter;
import com.tencent.supersonic.headless.api.pojo.request.QueryNLReq;
import com.tencent.supersonic.headless.api.pojo.request.SemanticQueryReq;
import com.tencent.supersonic.headless.api.pojo.response.*;
import com.tencent.supersonic.headless.chat.corrector.S2SqlDateHelper;
import com.tencent.supersonic.headless.chat.parser.llm.OnePassSCSqlGenStrategy;
import com.tencent.supersonic.headless.chat.parser.llm.SqlGenStrategyFactory;
import com.tencent.supersonic.headless.chat.query.QueryManager;
import com.tencent.supersonic.headless.chat.query.SemanticQuery;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMSqlQuery;
import com.tencent.supersonic.headless.server.facade.service.ChatLayerService;
import com.tencent.supersonic.headless.server.facade.service.SemanticLayerService;
import com.tencent.supersonic.headless.server.service.SchemaService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.service.TokenStream;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

import static com.tencent.supersonic.chat.server.parser.NL2SQLParser.APP_KEY_MULTI_TURN;
import static com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq.SqlGenType.ONE_PASS_SELF_CONSISTENCY;

@Slf4j
@Service
public class ChatQueryServiceImpl implements ChatQueryService {

    @Autowired
    private ChatManageService chatManageService;
    @Autowired
    private ChatLayerService chatLayerService;
    @Autowired
    private SemanticLayerService semanticLayerService;
    @Autowired
    @Lazy
    private AgentService agentService;
    @Autowired
    private HistoryService historyService;
    @Autowired
    private VoiceService voiceService;
    @Autowired
    private SchemaService schemaService;
    @Autowired
    @Qualifier("chatExecutor")
    private ThreadPoolExecutor chatExecutor;
    private final List<ChatQueryParser> chatQueryParsers = ComponentFactory.getChatParsers();
    private final List<ChatQueryExecutor> chatQueryExecutors = ComponentFactory.getChatExecutors();
    private final List<ParseResultProcessor> parseResultProcessors =
            ComponentFactory.getParseProcessors();
    private final List<ExecuteResultProcessor> executeResultProcessors =
            ComponentFactory.getExecuteProcessors();

    @Override
    public List<SearchResult> search(ChatParseReq chatParseReq) {
        ParseContext parseContext = buildParseContext(chatParseReq, null);
        Agent agent = parseContext.getAgent();
        if (!agent.enableSearch()) {
            return Lists.newArrayList();
        }
        QueryNLReq queryNLReq = QueryReqConverter.buildQueryNLReq(parseContext);
        return chatLayerService.retrieve(queryNLReq);
    }

    @Override
    public ChatParseResp parse(ChatParseReq chatParseReq) {
        Long queryId = chatParseReq.getQueryId();
        if (Objects.isNull(queryId)) {
            queryId = chatManageService.createChatQuery(chatParseReq);
            chatParseReq.setQueryId(queryId);
        }

        ParseContext parseContext = buildParseContext(chatParseReq, new ChatParseResp(queryId));
        for (ChatQueryParser parser : chatQueryParsers) {
            if (parser.accept(parseContext)) {
                parser.parse(parseContext);
                break;
            }
        }
        long _t1 = System.currentTimeMillis();

        // saveHistoryInfo 为纯写操作，异步化不阻塞主流程
        final ParseContext finalParseContext = parseContext;
        chatExecutor.execute(() -> {
            long _s = System.currentTimeMillis();
            saveHistoryInfo(finalParseContext);
            log.info("[PERF-parse] saveHistoryInfo(async): {}ms", System.currentTimeMillis() - _s);
        });

        // 不是简易模式的自然语言回答才走后续逻辑
        if (!parseContext.getResponse().getSelectedParses().isEmpty() && !Objects.equals(
                parseContext.getResponse().getSelectedParses().get(0).getSqlInfo().getResultType(),
                "text")) {
            for (ParseResultProcessor processor : parseResultProcessors) {
                if (processor.accept(parseContext)) {
                    processor.process(parseContext);
                }
            }
        }
        if (!parseContext.needFeedback()) {
            // batchAddParse 必须同步：execute() 阶段需要从 DB 读取刚写入的 parseInfo
            chatManageService.batchAddParse(chatParseReq, parseContext.getResponse());
            long _t3 = System.currentTimeMillis();
            log.info("[PERF-parse] batchAddParse(sync): {}ms", _t3 - _t1);
            // updateParseCostTime 为纯统计写入，异步化
            final ChatParseResp finalResp = parseContext.getResponse();
            chatExecutor.execute(() -> {
                long _s = System.currentTimeMillis();
                chatManageService.updateParseCostTime(finalResp);
                log.info("[PERF-parse] updateParseCostTime(async): {}ms",
                        System.currentTimeMillis() - _s);
            });
        }

        return parseContext.getResponse();
    }

    private void saveHistoryInfo(ParseContext parseContext) {
        // 来闲聊这里不存历史记录
        if (!parseContext.getResponse().getSelectedParses().isEmpty() && !Objects.equals(
                parseContext.getResponse().getSelectedParses().get(0).getQueryMode(),
                "PLAIN_TEXT")) {
            historyService.saveHistoryInfo(parseContext);
        } else if (parseContext.getResponse().getState() == ParseResp.ParseState.FAILED) {
            historyService.saveHistoryErrorInfo(parseContext);
        }
    }

    @Override
    public QueryResult execute(ChatExecuteReq chatExecuteReq) {
        long _e0 = System.currentTimeMillis();
        QueryResult queryResult = new QueryResult();
        ExecuteContext executeContext = buildExecuteContext(chatExecuteReq);
        log.info("[PERF-execute] buildExecuteContext(含getParseInfo DB查询): {}ms",
                System.currentTimeMillis() - _e0);

        for (ChatQueryExecutor chatQueryExecutor : chatQueryExecutors) {
            if (chatQueryExecutor.accept(executeContext)) {
                queryResult = chatQueryExecutor.execute(executeContext);
                if (queryResult != null) {
                    break;
                }
            }
        }

        executeContext.setResponse(queryResult);
        if (queryResult != null) {
            savePlainText(queryResult, executeContext);
            for (ExecuteResultProcessor processor : executeResultProcessors) {
                if (processor.accept(executeContext)) {
                    processor.process(executeContext);
                }
            }
            // saveQueryResult 为纯写操作，异步化不阻塞结果返回
            final ChatExecuteReq finalReq = chatExecuteReq;
            final QueryResult finalResult = queryResult;
            chatExecutor.execute(() -> {
                long _s = System.currentTimeMillis();
                saveQueryResult(finalReq, finalResult);
                log.info("[PERF-execute] saveQueryResult(async): {}ms",
                        System.currentTimeMillis() - _s);
            });
        }

        return queryResult;
    }

    @Override
    public SseEmitter streamExecute(ChatExecuteReq chatExecuteReq) throws Exception {
        TokenStream stream = null;
        ExecuteContext executeContext = buildExecuteContext(chatExecuteReq);
        for (ChatQueryExecutor chatQueryExecutor : chatQueryExecutors) {
            stream = chatQueryExecutor.streamExecute(executeContext);
            if (stream != null) {
                break;
            }
        }

        // 1. 创建SSE发射器（1分钟超时）
        SseEmitter emitter = new SseEmitter(60_000L);
        if (stream == null) {
            emitter.complete();
        }
        stream.onNext(chunk -> {
            try {
                // 发送单个数据块
                Map<String, String> data = new HashMap<>();
                data.put("text", chunk);
                emitter.send(SseEmitter.event().data(data, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                log.error("SSE send error", e);
                emitter.completeWithError(e);
            }
        }).onComplete(response -> {
            processStreamResult(chatExecuteReq, executeContext, response.content());
            emitter.complete();
        }).onError(ex -> {
            emitter.completeWithError(ex);
        }).start();
        emitter.onTimeout(() -> {
            emitter.complete();
        });
        return emitter;
    }

    private void processStreamResult(ChatExecuteReq chatExecuteReq, ExecuteContext executeContext,
            AiMessage message) {
        QueryResult result = new QueryResult();
        result.setQueryState(QueryState.SUCCESS);
        result.setQueryMode(executeContext.getParseInfo().getQueryMode());
        result.setTextResult(message.text());
        savePlainText(result, executeContext);
        for (ExecuteResultProcessor processor : executeResultProcessors) {
            if (processor.accept(executeContext)) {
                processor.process(executeContext);
            }
        }
        saveQueryResult(chatExecuteReq, result);
    }

    public void saveFinalResult(ChatExecuteReq chatExecuteReq, String finalContent) {
        QueryResult result = new QueryResult();
        result.setTextResult(finalContent);
        result.setQueryState(QueryState.SUCCESS);
        result.setQueryMode("PLAIN_TEXT");
        ExecuteContext executeContext = buildExecuteContext(chatExecuteReq);
        savePlainText(result, executeContext);
        List<FileInfo> fileInfos = new ArrayList<>();
        // 如果有文件内容，也存储
        if (chatExecuteReq.getFileInfoList() != null
                && !chatExecuteReq.getFileInfoList().isEmpty()) {
            fileInfos.addAll(chatExecuteReq.getFileInfoList());
            result.setHasFile(true);
            result.setFileInfoList(fileInfos);
        }
        saveQueryResult(chatExecuteReq, result);
    }

    private void savePlainText(QueryResult queryResult, ExecuteContext executeContext) {
        if (!queryResult.getQueryMode().isEmpty()
                && executeContext.getParseInfo().getSqlInfo().getResultType().isEmpty()
                && "PLAIN_TEXT".equals(queryResult.getQueryMode())
                && executeContext.getParseInfo().getSqlInfo().getCorrectedS2SQL() == null) {
            historyService.createHistory(
                    ChatHistory.builder().queryId(executeContext.getRequest().getQueryId())
                            .agentId(executeContext.getAgent().getId()).status(MemoryStatus.PENDING)
                            .question(executeContext.getRequest().getQueryText())
                            .s2sql(queryResult.getTextResult())
                            .createdBy(executeContext.getRequest().getUser().getName())
                            .updatedBy(executeContext.getRequest().getUser().getName())
                            .createdAt(new Date()).build());
        }
    }

    @Override
    public QueryResult dataInterpret(ChatExecuteReq chatExecuteReq) {
        ExecuteContext executeContext = buildExecuteContext(chatExecuteReq);
        QueryResult queryResult = new QueryResult();
        queryResult.setTextResult(chatExecuteReq.getTextResult());
        executeContext.setResponse(queryResult);
        DataInterpretProcessor dataInterpretProcessor = new DataInterpretProcessor();
        if (queryResult.getTextResult() != null) {
            dataInterpretProcessor.dataInterpret(executeContext);
        }
        TextVoiceReq ttsReq = new TextVoiceReq();
        ttsReq.setText("智能洞察：" + queryResult.getTextSummary());
        queryResult.setTtsUrl(voiceService.textVoice(ttsReq));
        return queryResult;
    }

    @Override
    public SseEmitter streamParse(ChatParseReq chatParseReq) {
        // 获取Agent配置
        AgentService agentService = ContextUtils.getBean(AgentService.class);
        Agent chatAgent = agentService.getAgent(chatParseReq.getAgentId());
        Set<Long> dataSetIds = chatAgent.getDataSetIds();
        SemanticSchema semanticSchema = schemaService.getSemanticSchema(dataSetIds);
        // 检查是否开启多轮对话
        ChatApp chatApp = chatAgent.getChatAppConfig().get(APP_KEY_MULTI_TURN);
        if (chatApp != null && chatApp.isEnable()) {
            Long queryId = chatParseReq.getQueryId();
            if (Objects.isNull(queryId)) {
                queryId = chatManageService.createChatQuery(chatParseReq);
                chatParseReq.setQueryId(queryId);
            }
            ParseContext parseContext = buildParseContext(chatParseReq, new ChatParseResp(queryId));
            QueryNLReq queryNLReq = QueryReqConverter.buildQueryNLReq(parseContext);
            queryNLReq.setText2SQLType(Text2SQLType.LLM_OR_RULE);
            parseContext.setResponse(new ChatParseResp(parseContext.getResponse().getQueryId()));
            // 遍历 chatQueryParsers，找到 NL2SQLParser 并调用 rewriteQuery 方法
            for (ChatQueryParser parser : chatQueryParsers) {
                if (parser instanceof NL2SQLParser) {
                    ((NL2SQLParser) parser).rewriteMultiTurn(parseContext,
                            parseContext.getAgent().getId(),
                            parseContext.getRequest().getQueryText());
                    // 更新 chatParseReq 中的问题文本为改写后的文本
                    chatParseReq.setQueryText(queryNLReq.getQueryText());
                    break;
                }
            }
        }
        LLMReq llmReq = new LLMReq();
        llmReq.setQueryText(chatParseReq.getQueryText());
        llmReq.setChatAppConfig(chatAgent.getChatAppConfig());
        llmReq.setAgentId(chatParseReq.getAgentId());
        OnePassSCSqlGenStrategy sqlGenStrategy =
                (OnePassSCSqlGenStrategy) SqlGenStrategyFactory.get(ONE_PASS_SELF_CONSISTENCY);
        return sqlGenStrategy.streamGenerate(llmReq, semanticSchema);
    }

    @Override
    public QueryResult parseAndExecute(ChatParseReq chatParseReq) {
        long totalStart = System.currentTimeMillis();
        long parseTime = 0;
        long executeTime = 0;
        try {
            String queryType = chatParseReq.getQueryType();
            if ("simple".equals(queryType)) {
                log.info("queryType 为: {} , 进入简易模式。", queryType);
            } else if ("super_simple".equals(queryType)) {
                log.info("queryType 为: {} , 进入超级简易模式。", queryType);
            } else {
                log.info("queryType 为: {} , 进入正常模式。", queryType);
            }
            // SUPER_SIMPLE 模式：完全跳过 parse 流程，直接 LLM 生成物理 SQL 并执行
            if ("super_simple".equalsIgnoreCase(chatParseReq.getQueryType())) {
                log.info("[SUPER_SIMPLE] 进入超级简易模式，直接LLM生成物理SQL，问题: {}",
                        chatParseReq.getQueryText());
                SuperSimpleQueryHandler handler =
                        new SuperSimpleQueryHandler(agentService, semanticLayerService);
                QueryResult result = handler.execute(chatParseReq);
                log.info("[PERFORMANCE-TOTAL] SUPER_SIMPLE 总耗时: {}ms, 问题: {}",
                        System.currentTimeMillis() - totalStart,
                        StringUtils.abbreviate(chatParseReq.getQueryText(), 50));
                return result;
            }
            long parseStart = System.currentTimeMillis();
            ChatParseResp parseResp = parse(chatParseReq);
            parseTime = System.currentTimeMillis() - parseStart;

            if (CollectionUtils.isEmpty(parseResp.getSelectedParses())) {
                log.warn(
                        "chatId:{}, agentId:{}, queryText:{}, parseResp.getSelectedParses() is empty",
                        chatParseReq.getChatId(), chatParseReq.getAgentId(),
                        chatParseReq.getQueryText());
                QueryResult emptyResult = new QueryResult();
                emptyResult.setQueryState(QueryState.EMPTY);
                if (StringUtils.isBlank(parseResp.getErrorMsg())) {
                    emptyResult.setErrorMsg("未能解析出有效查询，请尝试换一种问法");
                } else {
                    emptyResult.setErrorMsg(parseResp.getErrorMsg());
                }
                return emptyResult;
            }
            ChatExecuteReq executeReq = new ChatExecuteReq();
            executeReq.setQueryId(parseResp.getQueryId());
            executeReq.setParseId(parseResp.getSelectedParses().get(0).getId());
            executeReq.setQueryText(chatParseReq.getQueryText());
            executeReq.setChatId(chatParseReq.getChatId());
            executeReq.setUser(User.getDefaultUser());
            executeReq.setAgentId(chatParseReq.getAgentId());
            executeReq.setSaveAnswer(true);

            long executeStart = System.currentTimeMillis();
            QueryResult queryResult = execute(executeReq);
            executeTime = System.currentTimeMillis() - executeStart;

            if (queryResult == null) {
                QueryResult failResult = new QueryResult();
                failResult.setQueryState(QueryState.EMPTY);
                failResult.setErrorMsg("执行查询未返回结果");
                return failResult;
            }

            // SQL执行失败且错误可重试：通过 errorFeedback 通道传递错误信息，重新走完整 parse+execute 链路，
            // 不污染 queryText，不影响 MAPPING 向量召回、多轮改写和聊天历史
            if (StringUtils.isBlank(chatParseReq.getErrorFeedback())
                    && isRetryableFailure(queryResult)) {
                QueryResult retryResult =
                        retryWithErrorFeedback(chatParseReq, parseResp, queryResult);
                if (retryResult != null) {
                    queryResult = retryResult;
                }
            }

            long totalTime = System.currentTimeMillis() - totalStart;
            log.info(
                    "[PERFORMANCE-TOTAL] /parseAndExecute 总耗时: {}ms (PARSE: {}ms, EXECUTE: {}ms), 问题: {}",
                    totalTime, parseTime, executeTime,
                    StringUtils.abbreviate(chatParseReq.getQueryText(), 50));

            return queryResult;
        } catch (Exception e) {
            log.error("parseAndExecute failed, chatId:{}, agentId:{}, queryText:{}",
                    chatParseReq.getChatId(), chatParseReq.getAgentId(),
                    chatParseReq.getQueryText(), e);
            QueryResult errorResult = new QueryResult();
            errorResult.setQueryState(QueryState.INVALID);
            errorResult.setErrorMsg("查询处理异常：" + e.getMessage());
            return errorResult;
        }
    }

    /**
     * 判断查询失败是否可通过重新生成 SQL 修复。 可重试：SQL语法错误、字段/表不存在、类型不匹配、连接异常；
     * 不可重试：结果为空（SQL没错确实无数据）、权限拒绝、解析失败等。
     */
    private boolean isRetryableFailure(QueryResult queryResult) {
        if (queryResult == null
                || !QueryState.INVALID.equals(queryResult.getQueryState())
                || StringUtils.isBlank(queryResult.getErrorMsg())) {
            return false;
        }
        String errMsg = queryResult.getErrorMsg().toLowerCase();
        return errMsg.contains("syntax") || errMsg.contains("sql语法")
                || errMsg.contains("unknown column") || errMsg.contains("unknown table")
                || errMsg.contains("doesn't exist") || errMsg.contains("not found")
                || errMsg.contains("no database selected")
                || errMsg.contains("truncated incorrect")
                || errMsg.contains("incorrect double") || errMsg.contains("incorrect date")
                || errMsg.contains("communications link")
                || errMsg.contains("connection refused") || errMsg.contains("timeout");
    }

    /**
     * 带错误反馈重试：构建新的 ChatParseReq（queryText 保持不变，错误信息走 errorFeedback 专用通道），
     * 重新走 MAPPING → PARSING → CORRECTING → TRANSLATING 全链路后执行。 重试成功返回新结果，重试失败返回 null（由调用方保留原失败结果）。
     */
    private QueryResult retryWithErrorFeedback(ChatParseReq chatParseReq, ChatParseResp parseResp,
            QueryResult failedResult) {
        long retryStart = System.currentTimeMillis();
        try {
            log.info("[RETRY] SQL执行失败，带错误反馈重新解析。错误: {}", failedResult.getErrorMsg());
            ChatParseReq retryReq = new ChatParseReq();
            BeanMapper.mapper(chatParseReq, retryReq);
            // 强制创建新 queryId，避免覆盖原解析记录
            retryReq.setQueryId(null);
            retryReq.setErrorFeedback(buildErrorFeedback(failedResult, parseResp));

            ChatParseResp retryParseResp = parse(retryReq);
            if (CollectionUtils.isEmpty(retryParseResp.getSelectedParses())) {
                log.warn("[RETRY] 重试解析未生成有效查询，保留原失败结果");
                return null;
            }
            ChatExecuteReq retryExecuteReq = new ChatExecuteReq();
            retryExecuteReq.setQueryId(retryParseResp.getQueryId());
            retryExecuteReq.setParseId(retryParseResp.getSelectedParses().get(0).getId());
            retryExecuteReq.setQueryText(chatParseReq.getQueryText());
            retryExecuteReq.setChatId(chatParseReq.getChatId());
            retryExecuteReq.setUser(User.getDefaultUser());
            retryExecuteReq.setAgentId(chatParseReq.getAgentId());
            retryExecuteReq.setSaveAnswer(true);

            QueryResult retryResult = execute(retryExecuteReq);
            log.info("[RETRY] 重试完成，耗时: {}ms, 结果状态: {}", System.currentTimeMillis() - retryStart,
                    retryResult != null ? retryResult.getQueryState() : "null");
            if (retryResult != null && QueryState.SUCCESS.equals(retryResult.getQueryState())) {
                return retryResult;
            }
            return null;
        } catch (Exception e) {
            log.error("[RETRY] 重试过程异常，保留原失败结果", e);
            return null;
        }
    }

    /**
     * 构建错误反馈文本：失败SQL + 错误信息，注入 PARSING 阶段 LLM 提示词的 SideInfo。
     */
    private String buildErrorFeedback(QueryResult failedResult, ChatParseResp parseResp) {
        String failedSql = failedResult.getQuerySql();
        if (StringUtils.isBlank(failedSql) && parseResp != null
                && !CollectionUtils.isEmpty(parseResp.getSelectedParses())) {
            SqlInfo sqlInfo = parseResp.getSelectedParses().get(0).getSqlInfo();
            if (sqlInfo != null) {
                failedSql = StringUtils.isNotBlank(sqlInfo.getQuerySQL()) ? sqlInfo.getQuerySQL()
                        : sqlInfo.getCorrectedS2SQL();
            }
        }
        StringBuilder feedback = new StringBuilder();
        feedback.append("#重试提示：上次针对该问题生成的SQL执行报错\n");
        if (StringUtils.isNotBlank(failedSql)) {
            feedback.append("失败SQL: ").append(failedSql).append("\n");
        }
        feedback.append("错误信息: ").append(failedResult.getErrorMsg()).append("\n");
        feedback.append("请分析错误原因，避免重复同样的错误，重新生成正确的SQL。");
        return feedback.toString();
    }

    private ParseContext buildParseContext(ChatParseReq chatParseReq, ChatParseResp chatParseResp) {
        ParseContext parseContext = new ParseContext(chatParseReq, chatParseResp);
        Agent agent = agentService.getAgent(chatParseReq.getAgentId());
        parseContext.setAgent(agent);
        return parseContext;
    }

    private ExecuteContext buildExecuteContext(ChatExecuteReq chatExecuteReq) {
        ExecuteContext executeContext = new ExecuteContext(chatExecuteReq);
        SemanticParseInfo parseInfo = chatManageService.getParseInfo(chatExecuteReq.getQueryId(),
                chatExecuteReq.getParseId());
        Agent agent = agentService.getAgent(chatExecuteReq.getAgentId());
        executeContext.setAgent(agent);
        executeContext.setParseInfo(parseInfo);
        return executeContext;
    }

    @Override
    public Object queryData(ChatQueryDataReq chatQueryDataReq, User user) throws Exception {
        Integer parseId = chatQueryDataReq.getParseId();
        SemanticParseInfo parseInfo =
                chatManageService.getParseInfo(chatQueryDataReq.getQueryId(), parseId);
        mergeParseInfo(parseInfo, chatQueryDataReq);
        DataSetSchema dataSetSchema =
                semanticLayerService.getDataSetSchema(parseInfo.getDataSetId());

        SemanticQuery semanticQuery = QueryManager.createQuery(parseInfo.getQueryMode());
        semanticQuery.setParseInfo(parseInfo);

        if (LLMSqlQuery.QUERY_MODE.equalsIgnoreCase(parseInfo.getQueryMode())) {
            handleLLMQueryMode(chatQueryDataReq, semanticQuery, dataSetSchema, user);
        } else {
            handleRuleQueryMode(semanticQuery, dataSetSchema, user);
        }

        return executeQuery(semanticQuery, user);
    }

    private List<String> getFieldsFromSql(SemanticParseInfo parseInfo) {
        SqlInfo sqlInfo = parseInfo.getSqlInfo();
        if (Objects.isNull(sqlInfo) || StringUtils.isNotBlank(sqlInfo.getCorrectedS2SQL())) {
            return new ArrayList<>();
        }
        return SqlSelectHelper.getAllSelectFields(sqlInfo.getCorrectedS2SQL());
    }

    private void handleLLMQueryMode(ChatQueryDataReq chatQueryDataReq, SemanticQuery semanticQuery,
            DataSetSchema dataSetSchema, User user) throws Exception {
        SemanticParseInfo parseInfo = semanticQuery.getParseInfo();
        String rebuiltS2SQL;
        if (checkMetricReplace(chatQueryDataReq, parseInfo)) {
            log.info("rebuild S2SQL with adjusted metrics!");
            SchemaElement metricToReplace = chatQueryDataReq.getMetrics().iterator().next();
            rebuiltS2SQL = replaceMetrics(parseInfo, metricToReplace);
        } else {
            log.info("rebuild S2SQL with adjusted filters!");
            rebuiltS2SQL = replaceFilters(chatQueryDataReq, parseInfo, dataSetSchema);
        }
        // reset SqlInfo and request re-translation
        parseInfo.getSqlInfo().setCorrectedS2SQL(rebuiltS2SQL);
        parseInfo.getSqlInfo().setParsedS2SQL(rebuiltS2SQL);
        parseInfo.getSqlInfo().setQuerySQL(null);
        SemanticQueryReq semanticQueryReq = semanticQuery.buildSemanticQueryReq();
        SemanticTranslateResp explain = semanticLayerService.translate(semanticQueryReq, user);
        parseInfo.getSqlInfo().setQuerySQL(explain.getQuerySQL());
    }

    private void handleRuleQueryMode(SemanticQuery semanticQuery, DataSetSchema dataSetSchema,
            User user) {
        log.info("rule begin replace metrics and revise filters!");
        validFilter(semanticQuery.getParseInfo().getDimensionFilters());
        validFilter(semanticQuery.getParseInfo().getMetricFilters());
        semanticQuery.buildS2Sql(dataSetSchema);
    }

    private QueryResult executeQuery(SemanticQuery semanticQuery, User user) throws Exception {
        SemanticQueryReq semanticQueryReq = semanticQuery.buildSemanticQueryReq();
        SemanticParseInfo parseInfo = semanticQuery.getParseInfo();
        QueryResult queryResult = doExecution(semanticQueryReq, parseInfo.getQueryMode(), user);
        queryResult.setChatContext(semanticQuery.getParseInfo());
        parseInfo.getSqlInfo().setQuerySQL(queryResult.getQuerySql());
        return queryResult;
    }

    private boolean checkMetricReplace(ChatQueryDataReq chatQueryDataReq,
            SemanticParseInfo parseInfo) {
        List<String> oriFields = getFieldsFromSql(parseInfo);
        Set<SchemaElement> metrics = chatQueryDataReq.getMetrics();
        if (CollectionUtils.isEmpty(oriFields) || CollectionUtils.isEmpty(metrics)) {
            return false;
        }
        List<String> metricNames =
                metrics.stream().map(SchemaElement::getName).collect(Collectors.toList());
        return !oriFields.containsAll(metricNames);
    }

    private String replaceFilters(ChatQueryDataReq queryData, SemanticParseInfo parseInfo,
            DataSetSchema dataSetSchema) {
        String correctorSql = parseInfo.getSqlInfo().getCorrectedS2SQL();
        log.info("correctorSql before replacing:{}", correctorSql);
        JsqlParserType jsqlParserType = checkJsqlParserType(correctorSql);
        if (jsqlParserType != JsqlParserType.COMMON) {
            log.info("校验sql结构存在子查询，使用replaceFiltersTest解析sql");
            correctorSql = replaceFiltersByJsqlParserType(queryData, parseInfo, dataSetSchema,
                    jsqlParserType);
            return correctorSql;
        }
        log.info("校验sql结构不存在子查询，使用replaceFilters解析sql");
        // get where filter and having filter
        List<FieldExpression> whereExpressionList =
                SqlSelectHelper.getWhereExpressions(correctorSql);

        // replace where filter
        List<Expression> addWhereConditions = new ArrayList<>();
        Set<String> removeWhereFieldNames =
                updateFilters(whereExpressionList, queryData.getDimensionFilters(),
                        parseInfo.getDimensionFilters(), addWhereConditions);

        Map<String, Map<String, String>> filedNameToValueMap = new HashMap<>();
        Set<String> removeDataFieldNames = updateDateInfo(queryData, parseInfo, dataSetSchema,
                filedNameToValueMap, whereExpressionList, addWhereConditions);
        removeWhereFieldNames.addAll(removeDataFieldNames);

        correctorSql = SqlReplaceHelper.replaceValue(correctorSql, filedNameToValueMap);
        correctorSql = SqlRemoveHelper.removeWhereCondition(correctorSql, removeWhereFieldNames);

        // replace having filter
        List<FieldExpression> havingExpressionList =
                SqlSelectHelper.getHavingExpressions(correctorSql);
        List<Expression> addHavingConditions = new ArrayList<>();
        Set<String> removeHavingFieldNames =
                updateFilters(havingExpressionList, queryData.getDimensionFilters(),
                        parseInfo.getDimensionFilters(), addHavingConditions);
        correctorSql = SqlReplaceHelper.replaceHavingValue(correctorSql, new HashMap<>());
        correctorSql = SqlRemoveHelper.removeHavingCondition(correctorSql, removeHavingFieldNames);

        correctorSql = SqlAddHelper.addWhere(correctorSql, addWhereConditions);
        correctorSql = SqlAddHelper.addHaving(correctorSql, addHavingConditions);
        log.info("correctorSql after replacing:{}", correctorSql);
        return correctorSql;
    }

    private String replaceFiltersByJsqlParserType(ChatQueryDataReq queryData,
            SemanticParseInfo parseInfo, DataSetSchema dataSetSchema,
            JsqlParserType jsqlParserType) {

        String correctorSql = parseInfo.getSqlInfo().getCorrectedS2SQL();
        log.info("correctorSql before replacing:{}", correctorSql);

        Select selectStatement = SqlSelectHelper.getSelect(correctorSql);
        PlainSelect plainSelect = (PlainSelect) selectStatement;

        ArrayList<Select> selectArrayList = new ArrayList<>();
        if (jsqlParserType == JsqlParserType.WITH) {
            selectArrayList.addAll(SqlSelectHelper.getWithItem(selectStatement));
        } else {

            Select fromItemSelect = plainSelect.getFromItem(ParenthesedSelect.class).getSelect();
            log.info("fromItemSelect is:{}", fromItemSelect);

            List<Join> joins = plainSelect.getJoins();
            FromItem fromItem = joins.get(0).getFromItem();
            ParenthesedSelect parenthesedSelect = (ParenthesedSelect) fromItem;
            Select JoinItemSelect = parenthesedSelect.getSelect();
            log.info("JoinItemSelect is:{}", JoinItemSelect);


            selectArrayList.add(fromItemSelect);
            selectArrayList.add(JoinItemSelect);
        }

        List<String> modifiedSubQueries = new ArrayList<>();
        selectArrayList.forEach(select -> {
            String selectSql = select.toString();
            log.info("selectSql is:{}", selectSql);

            Map<String, String> dateRange = extractDateRangeFromSql(selectSql);
            log.info("Extracted date range: {}", dateRange);

            // get where filter and having filter
            List<FieldExpression> whereExpressionList =
                    SqlSelectHelper.getWhereExpressions(selectSql);

            // replace where filter
            List<Expression> addWhereConditions = new ArrayList<>();
            Set<String> removeWhereFieldNames =
                    updateFilters(whereExpressionList, queryData.getDimensionFilters(),
                            parseInfo.getDimensionFilters(), addWhereConditions);

            Map<String, Map<String, String>> filedNameToValueMap = new HashMap<>();
            Set<String> removeDataFieldNames =
                    updateDateInfoTest(queryData, parseInfo, dataSetSchema, filedNameToValueMap,
                            whereExpressionList, addWhereConditions, dateRange);
            removeWhereFieldNames.addAll(removeDataFieldNames);

            selectSql = SqlReplaceHelper.replaceValue(selectSql, filedNameToValueMap);
            selectSql = SqlRemoveHelper.removeWhereCondition(selectSql, removeWhereFieldNames);

            // replace having filter
            List<FieldExpression> havingExpressionList =
                    SqlSelectHelper.getHavingExpressions(selectSql);
            List<Expression> addHavingConditions = new ArrayList<>();
            Set<String> removeHavingFieldNames =
                    updateFilters(havingExpressionList, queryData.getDimensionFilters(),
                            parseInfo.getDimensionFilters(), addHavingConditions);
            selectSql = SqlReplaceHelper.replaceHavingValue(selectSql, new HashMap<>());
            selectSql = SqlRemoveHelper.removeHavingCondition(selectSql, removeHavingFieldNames);

            selectSql = SqlAddHelper.addWhere(selectSql, addWhereConditions);
            selectSql = SqlAddHelper.addHaving(selectSql, addHavingConditions);
            log.info("selectSql after replacing:{}", selectSql);
            modifiedSubQueries.add(selectSql);
        });

        correctorSql = rebuildCorrectorSql(correctorSql, modifiedSubQueries, jsqlParserType);
        log.info("correctorSql after replacing:{}", correctorSql);
        return correctorSql;
    }

    private JsqlParserType checkJsqlParserType(String correctorSql) {
        Select selectStatement = SqlSelectHelper.getSelect(correctorSql);
        if (!(selectStatement instanceof PlainSelect)) {
            throw new IllegalArgumentException("修正S2SQL的结构有误！");
        }

        PlainSelect plainSelect = (PlainSelect) selectStatement;

        FromItem fromItem = plainSelect.getFromItem();
        List<Join> joins = plainSelect.getJoins();
        Expression where = plainSelect.getWhere();
        List<WithItem> withItemsList = plainSelect.getWithItemsList();

        if (Objects.nonNull(withItemsList) && withItemsList.size() >= 2) {
            return JsqlParserType.WITH;
        }
        if (withItemsList == null && fromItem != null && joins != null && !joins.isEmpty()
                && where == null) {
            log.info("非with语句，fromItem和joins不为null，where为null，返回SELECT。");
            return JsqlParserType.SELECT;
        }
        return JsqlParserType.COMMON;
    }

    private String rebuildCorrectorSql(String correctorSql, List<String> modifiedSubQueries,
            JsqlParserType jsqlParserType) {
        Select selectStatement = SqlSelectHelper.getSelect(correctorSql);
        if (!(selectStatement instanceof PlainSelect)) {
            throw new IllegalArgumentException("修正S2SQL的结构有误！");
        }

        if (jsqlParserType == JsqlParserType.WITH) {
            // 针对 WITH 的 SQL 重建逻辑
            log.info("Detected WITH clause in correctorSql, rebuilding WITH structure...");
            rebuildWithClause(selectStatement, modifiedSubQueries);
        } else {
            // 普通 SELECT 的 SQL 重建逻辑
            log.info("Detected standard SELECT structure, rebuilding...");
            rebuildSelectClause(selectStatement, modifiedSubQueries);
        }

        String finalSql = selectStatement.toString();
        log.info("Rebuilt correctorSql: {}", finalSql);

        return finalSql;
    }

    private void rebuildWithClause(Select selectStatement, List<String> modifiedSubQueries) {
        List<WithItem> withItemsList = selectStatement.getWithItemsList();
        if (withItemsList == null || withItemsList.size() != modifiedSubQueries.size()) {
            throw new IllegalArgumentException("WITH 子查询数量与修改后的子查询数量不匹配！");
        }

        for (int i = 0; i < withItemsList.size(); i++) {
            WithItem withItem = withItemsList.get(i);
            String modifiedSubQuery = modifiedSubQueries.get(i);

            // 使用修改后的 SQL 替换 WITH 子查询
            Select modifiedWithSelect = SqlSelectHelper.getSelect(modifiedSubQuery);
            Select withSelect = withItem.getSelect();
            if (withSelect instanceof ParenthesedSelect) {
                ParenthesedSelect parenthesedSelect = (ParenthesedSelect) withSelect;
                parenthesedSelect.setSelect(modifiedWithSelect);
            }
        }
    }

    private void rebuildSelectClause(Select selectStatement, List<String> modifiedSubQueries) {
        if (!(selectStatement instanceof PlainSelect)) {
            throw new IllegalArgumentException("SELECT 结构有误，无法解析！");
        }

        PlainSelect plainSelect = (PlainSelect) selectStatement;

        // 替换 FROM 子查询
        if (modifiedSubQueries.size() < 1) {
            throw new IllegalArgumentException("缺少 FROM 子查询！");
        }
        ParenthesedSelect fromItem = (ParenthesedSelect) plainSelect.getFromItem();
        Select fromItemSelect = SqlSelectHelper.getSelect(modifiedSubQueries.get(0));
        fromItem.setSelect(fromItemSelect);

        // 替换 JOIN 子查询
        List<Join> joins = plainSelect.getJoins();
        if (joins != null && !joins.isEmpty()) {
            for (int i = 0; i < joins.size(); i++) {
                if (i + 1 >= modifiedSubQueries.size()) {
                    throw new IllegalArgumentException("JOIN 子查询数量不足！");
                }
                Join join = joins.get(i);
                ParenthesedSelect joinFromItem = (ParenthesedSelect) join.getRightItem();
                Select joinItemSelect = SqlSelectHelper.getSelect(modifiedSubQueries.get(i + 1));
                joinFromItem.setSelect(joinItemSelect);
            }
        }
    }

    private Map<String, String> extractDateRangeFromSql(String sql) {
        Map<String, String> dateRange = new HashMap<>();
        Select select = SqlSelectHelper.getSelect(sql);
        if (!(select instanceof PlainSelect plainSelect)) {
            return dateRange;
        }

        Expression where = plainSelect.getWhere();
        if (where == null) {
            return dateRange;
        }

        extractDateRangeFromExpression(where, dateRange);
        return dateRange;
    }

    private void extractDateRangeFromExpression(Expression expression,
            Map<String, String> dateRange) {
        if (expression instanceof EqualsTo equalsTo) {
            if (equalsTo.getLeftExpression() instanceof Column
                    && "数据日期".equals(((Column) equalsTo.getLeftExpression()).getColumnName())) {
                if (equalsTo.getRightExpression() instanceof StringValue) {
                    String date = ((StringValue) equalsTo.getRightExpression()).getValue();
                    dateRange.put("startDate", date);
                    dateRange.put("endDate", date);
                }
            }
        } else if (expression instanceof GreaterThanEquals greaterThanEquals) {
            if (greaterThanEquals.getLeftExpression() instanceof Column && "数据日期"
                    .equals(((Column) greaterThanEquals.getLeftExpression()).getColumnName())) {
                if (greaterThanEquals.getRightExpression() instanceof StringValue) {
                    dateRange.put("startDate",
                            ((StringValue) greaterThanEquals.getRightExpression()).getValue());
                }
            }
        } else if (expression instanceof GreaterThan greaterThan) {
            if (greaterThan.getLeftExpression() instanceof Column
                    && "数据日期".equals(((Column) greaterThan.getLeftExpression()).getColumnName())) {
                if (greaterThan.getRightExpression() instanceof StringValue) {
                    String originalDate =
                            ((StringValue) greaterThan.getRightExpression()).getValue();
                    String adjustedDate = DateUtils.getNextDate(originalDate);
                    dateRange.put("startDate", adjustedDate);
                }
            }
        } else if (expression instanceof MinorThanEquals minorThanEquals) {
            if (minorThanEquals.getLeftExpression() instanceof Column && "数据日期"
                    .equals(((Column) minorThanEquals.getLeftExpression()).getColumnName())) {
                if (minorThanEquals.getRightExpression() instanceof StringValue) {
                    dateRange.put("endDate",
                            ((StringValue) minorThanEquals.getRightExpression()).getValue());
                }
            }
        } else if (expression instanceof MinorThan minorThan) {
            if (minorThan.getLeftExpression() instanceof Column
                    && "数据日期".equals(((Column) minorThan.getLeftExpression()).getColumnName())) {
                if (minorThan.getRightExpression() instanceof StringValue) {
                    String originalDate = ((StringValue) minorThan.getRightExpression()).getValue();
                    String adjustedDate = DateUtils.getPreviousDate(originalDate);
                    dateRange.put("endDate", adjustedDate);
                }
            }
        } else if (expression instanceof AndExpression andExpression) {
            // 递归处理 AND 条件
            extractDateRangeFromExpression(andExpression.getLeftExpression(), dateRange);
            extractDateRangeFromExpression(andExpression.getRightExpression(), dateRange);
        }
    }


    private Set<String> updateDateInfoTest(ChatQueryDataReq queryData, SemanticParseInfo parseInfo,
            DataSetSchema dataSetSchema, Map<String, Map<String, String>> filedNameToValueMap,
            List<FieldExpression> fieldExpressionList, List<Expression> addConditions,
            Map<String, String> dateRange) {
        Set<String> removeFieldNames = new HashSet<>();
        if (Objects.isNull(queryData.getDateInfo())) {
            return removeFieldNames;
        }
        if (queryData.getDateInfo().getUnit() > 1) {
            queryData.getDateInfo()
                    .setStartDate(DateUtils.getBeforeDate(queryData.getDateInfo().getUnit() + 1));
            queryData.getDateInfo().setEndDate(DateUtils.getBeforeDate(0));
        }
        SchemaElement partitionDimension = dataSetSchema.getPartitionDimension();

        String startDate = dateRange.get("startDate");
        String endDate = dateRange.get("endDate");
        // startDate equals to endDate
        for (FieldExpression fieldExpression : fieldExpressionList) {
            if (partitionDimension.getName().equals(fieldExpression.getFieldName())) {
                // first remove,then add
                removeFieldNames.add(partitionDimension.getName());
                GreaterThanEquals greaterThanEquals = new GreaterThanEquals();
                String greaterThanEqualsDate;
                String minorThanEqualsDate;
                if (startDate != null) {
                    greaterThanEqualsDate = startDate;
                } else {
                    greaterThanEqualsDate = queryData.getDateInfo().getStartDate();
                }
                addTimeFilters(greaterThanEqualsDate, greaterThanEquals, addConditions,
                        partitionDimension);
                if (endDate != null) {
                    minorThanEqualsDate = endDate;
                } else {
                    minorThanEqualsDate = queryData.getDateInfo().getEndDate();
                }
                MinorThanEquals minorThanEquals = new MinorThanEquals();
                addTimeFilters(minorThanEqualsDate, minorThanEquals, addConditions,
                        partitionDimension);
                break;
            }
        }
        for (FieldExpression fieldExpression : fieldExpressionList) {
            for (QueryFilter queryFilter : queryData.getDimensionFilters()) {
                if (queryFilter.getOperator().equals(FilterOperatorEnum.LIKE)
                        && FilterOperatorEnum.LIKE.getValue()
                                .equalsIgnoreCase(fieldExpression.getOperator())) {
                    Map<String, String> replaceMap = new HashMap<>();
                    String preValue = fieldExpression.getFieldValue().toString();
                    String curValue = queryFilter.getValue().toString();
                    if (preValue.startsWith("%")) {
                        curValue = "%" + curValue;
                    }
                    if (preValue.endsWith("%")) {
                        curValue = curValue + "%";
                    }
                    replaceMap.put(preValue, curValue);
                    filedNameToValueMap.put(fieldExpression.getFieldName(), replaceMap);
                    break;
                }
            }
        }
        parseInfo.setDateInfo(queryData.getDateInfo());
        return removeFieldNames;
    }

    private String replaceMetrics(SemanticParseInfo parseInfo, SchemaElement metric) {
        List<String> oriMetrics = parseInfo.getMetrics().stream().map(SchemaElement::getName)
                .collect(Collectors.toList());
        String correctorSql = parseInfo.getSqlInfo().getCorrectedS2SQL();
        log.info("before replaceMetrics:{}", correctorSql);
        log.info("filteredMetrics:{},metrics:{}", oriMetrics, metric);
        Map<String, Pair<String, String>> fieldMap = new HashMap<>();
        if (!CollectionUtils.isEmpty(oriMetrics) && !oriMetrics.contains(metric.getName())) {
            fieldMap.put(oriMetrics.get(0), Pair.of(metric.getName(), metric.getDefaultAgg()));
            correctorSql = SqlReplaceHelper.replaceAggFields(correctorSql, fieldMap);
        }
        log.info("after replaceMetrics:{}", correctorSql);
        return correctorSql;
    }

    private QueryResult doExecution(SemanticQueryReq semanticQueryReq, String queryMode, User user)
            throws Exception {
        SemanticQueryResp queryResp = semanticLayerService.queryByReq(semanticQueryReq, user);
        QueryResult queryResult = new QueryResult();

        if (queryResp != null) {
            queryResult.setQueryAuthorization(queryResp.getQueryAuthorization());
            queryResult.setQuerySql(queryResp.getSql());
            queryResult.setQueryResults(queryResp.getResultList());
            queryResult.setQueryColumns(queryResp.getColumns());
        } else {
            queryResult.setQueryResults(new ArrayList<>());
            queryResult.setQueryColumns(new ArrayList<>());
        }

        queryResult.setQueryMode(queryMode);
        queryResult.setQueryState(QueryState.SUCCESS);
        return queryResult;
    }

    private Set<String> updateDateInfo(ChatQueryDataReq queryData, SemanticParseInfo parseInfo,
            DataSetSchema dataSetSchema, Map<String, Map<String, String>> filedNameToValueMap,
            List<FieldExpression> fieldExpressionList, List<Expression> addConditions) {
        Set<String> removeFieldNames = new HashSet<>();
        if (Objects.isNull(queryData.getDateInfo())) {
            return removeFieldNames;
        }
        if (queryData.getDateInfo().getUnit() > 1) {
            queryData.getDateInfo()
                    .setStartDate(DateUtils.getBeforeDate(queryData.getDateInfo().getUnit() + 1));
            queryData.getDateInfo().setEndDate(DateUtils.getBeforeDate(0));
        }
        SchemaElement partitionDimension = dataSetSchema.getPartitionDimension();
        // startDate equals to endDate
        for (FieldExpression fieldExpression : fieldExpressionList) {
            if (partitionDimension.getName().equals(fieldExpression.getFieldName())) {
                // first remove,then add
                removeFieldNames.add(partitionDimension.getName());
                GreaterThanEquals greaterThanEquals = new GreaterThanEquals();
                addTimeFilters(queryData.getDateInfo().getStartDate(), greaterThanEquals,
                        addConditions, partitionDimension);
                MinorThanEquals minorThanEquals = new MinorThanEquals();
                addTimeFilters(queryData.getDateInfo().getEndDate(), minorThanEquals, addConditions,
                        partitionDimension);
                break;
            }
            // 适配直连模式
            if (partitionDimension.getBizName().equals(fieldExpression.getFieldName())) {
                // first remove,then add
                removeFieldNames.add(partitionDimension.getBizName());
                GreaterThanEquals greaterThanEquals = new GreaterThanEquals();
                addTimeFiltersForBizName(queryData.getDateInfo().getStartDate(), greaterThanEquals,
                        addConditions, partitionDimension);
                MinorThanEquals minorThanEquals = new MinorThanEquals();
                addTimeFiltersForBizName(queryData.getDateInfo().getEndDate(), minorThanEquals,
                        addConditions, partitionDimension);
                break;
            }
        }
        for (FieldExpression fieldExpression : fieldExpressionList) {
            for (QueryFilter queryFilter : queryData.getDimensionFilters()) {
                if (queryFilter.getOperator().equals(FilterOperatorEnum.LIKE)
                        && FilterOperatorEnum.LIKE.getValue()
                                .equalsIgnoreCase(fieldExpression.getOperator())) {
                    Map<String, String> replaceMap = new HashMap<>();
                    String preValue = fieldExpression.getFieldValue().toString();
                    String curValue = queryFilter.getValue().toString();
                    if (preValue.startsWith("%")) {
                        curValue = "%" + curValue;
                    }
                    if (preValue.endsWith("%")) {
                        curValue = curValue + "%";
                    }
                    replaceMap.put(preValue, curValue);
                    filedNameToValueMap.put(fieldExpression.getFieldName(), replaceMap);
                    break;
                }
            }
        }
        parseInfo.setDateInfo(queryData.getDateInfo());
        return removeFieldNames;
    }

    private <T extends ComparisonOperator> void addTimeFilters(String date, T comparisonExpression,
            List<Expression> addConditions, SchemaElement partitionDimension) {
        Column column = new Column(partitionDimension.getName());
        StringValue stringValue = new StringValue(date);
        comparisonExpression.setLeftExpression(column);
        comparisonExpression.setRightExpression(stringValue);
        addConditions.add(comparisonExpression);
    }

    /**
     * 兼容直连模式
     */
    private <T extends ComparisonOperator> void addTimeFiltersForBizName(String date,
            T comparisonExpression, List<Expression> addConditions,
            SchemaElement partitionDimension) {
        Column column = new Column(partitionDimension.getBizName());
        StringValue stringValue;
        Object timeFormat =
                partitionDimension.getExtInfo().get(DimensionConstants.DIMENSION_TIME_FORMAT);
        if (timeFormat != null && StringUtils.isNotBlank(timeFormat.toString())) {
            stringValue = new StringValue(
                    DateUtils.format(date, DateUtils.DEFAULT_DATE_FORMAT, timeFormat.toString()));
        } else {
            stringValue = new StringValue(date);
        }
        comparisonExpression.setLeftExpression(column);
        comparisonExpression.setRightExpression(stringValue);
        addConditions.add(comparisonExpression);
    }

    private Set<String> updateFilters(List<FieldExpression> fieldExpressionList,
            Set<QueryFilter> metricFilters, Set<QueryFilter> contextMetricFilters,
            List<Expression> addConditions) {
        Set<String> removeFieldNames = new HashSet<>();
        if (CollectionUtils.isEmpty(metricFilters)) {
            return removeFieldNames;
        }

        for (QueryFilter dslQueryFilter : metricFilters) {
            for (FieldExpression fieldExpression : fieldExpressionList) {
                if (fieldExpression.getFieldName() != null
                        && fieldExpression.getFieldName().contains(dslQueryFilter.getName())) {
                    removeFieldNames.add(dslQueryFilter.getName());
                    handleFilter(dslQueryFilter, contextMetricFilters, addConditions);
                    break;
                }
            }
        }
        return removeFieldNames;
    }

    private void handleFilter(QueryFilter dslQueryFilter, Set<QueryFilter> contextMetricFilters,
            List<Expression> addConditions) {
        FilterOperatorEnum operator = dslQueryFilter.getOperator();

        if (operator == FilterOperatorEnum.IN) {
            addWhereInFilters(dslQueryFilter, new InExpression(), contextMetricFilters,
                    addConditions);
        } else {
            ComparisonOperator expression = FilterOperatorEnum.createExpression(operator);
            if (Objects.nonNull(expression)) {
                addWhereFilters(dslQueryFilter, expression, contextMetricFilters, addConditions);
            }
        }
    }

    // add in condition to sql where condition
    private void addWhereInFilters(QueryFilter dslQueryFilter, InExpression inExpression,
            Set<QueryFilter> contextMetricFilters, List<Expression> addConditions) {
        Column column = new Column(dslQueryFilter.getName());
        ParenthesedExpressionList parenthesedExpressionList = new ParenthesedExpressionList<>();
        List<String> valueList =
                JsonUtil.toList(JsonUtil.toString(dslQueryFilter.getValue()), String.class);
        if (CollectionUtils.isEmpty(valueList)) {
            return;
        }
        valueList.forEach(o -> {
            StringValue stringValue = new StringValue(o);
            parenthesedExpressionList.add(stringValue);
        });
        inExpression.setLeftExpression(column);
        inExpression.setRightExpression(parenthesedExpressionList);
        addConditions.add(inExpression);
        contextMetricFilters.forEach(o -> {
            if (o.getName().equals(dslQueryFilter.getName())) {
                o.setValue(dslQueryFilter.getValue());
                o.setOperator(dslQueryFilter.getOperator());
            }
        });
    }

    // add where filter
    private void addWhereFilters(QueryFilter dslQueryFilter,
            ComparisonOperator comparisonExpression, Set<QueryFilter> contextMetricFilters,
            List<Expression> addConditions) {
        String columnName = dslQueryFilter.getName();
        if (StringUtils.isNotBlank(dslQueryFilter.getFunction())) {
            columnName = dslQueryFilter.getFunction() + "(" + dslQueryFilter.getName() + ")";
        }
        if (Objects.isNull(dslQueryFilter.getValue())) {
            return;
        }
        Column column = new Column(columnName);
        comparisonExpression.setLeftExpression(column);
        if (StringUtils.isNumeric(dslQueryFilter.getValue().toString())) {
            LongValue longValue =
                    new LongValue(Long.parseLong(dslQueryFilter.getValue().toString()));
            comparisonExpression.setRightExpression(longValue);
        } else {
            StringValue stringValue = new StringValue(dslQueryFilter.getValue().toString());
            comparisonExpression.setRightExpression(stringValue);
        }
        addConditions.add(comparisonExpression);
        contextMetricFilters.forEach(o -> {
            if (o.getName().equals(dslQueryFilter.getName())) {
                o.setValue(dslQueryFilter.getValue());
                o.setOperator(dslQueryFilter.getOperator());
            }
        });
    }

    private void mergeParseInfo(SemanticParseInfo parseInfo, ChatQueryDataReq queryData) {
        if (Objects.nonNull(queryData.getDateInfo())) {
            parseInfo.setDateInfo(queryData.getDateInfo());
        }
        if (LLMSqlQuery.QUERY_MODE.equals(parseInfo.getQueryMode())) {
            return;
        }
        if (!CollectionUtils.isEmpty(queryData.getDimensions())) {
            parseInfo.setDimensions(queryData.getDimensions());
        }
        if (!CollectionUtils.isEmpty(queryData.getMetrics())) {
            parseInfo.setMetrics(queryData.getMetrics());
        }
        if (!CollectionUtils.isEmpty(queryData.getDimensionFilters())) {
            parseInfo.setDimensionFilters(queryData.getDimensionFilters());
        }
        if (!CollectionUtils.isEmpty(queryData.getMetricFilters())) {
            parseInfo.setMetricFilters(queryData.getMetricFilters());
        }

        parseInfo.setSqlInfo(new SqlInfo());
    }

    private void validFilter(Set<QueryFilter> filters) {
        Iterator<QueryFilter> iterator = filters.iterator();
        while (iterator.hasNext()) {
            QueryFilter queryFilter = iterator.next();
            Object queryFilterValue = queryFilter.getValue();
            if (Objects.isNull(queryFilterValue)) {
                iterator.remove();
                continue;
            }
            List<String> collection = new ArrayList<>();
            if (queryFilterValue instanceof List) {
                collection.addAll((List) queryFilterValue);
            } else if (queryFilterValue instanceof String) {
                collection.add((String) queryFilterValue);
            }
            if (FilterOperatorEnum.IN.equals(queryFilter.getOperator())
                    && CollectionUtils.isEmpty(collection)) {
                iterator.remove();
            }
        }
    }

    @Override
    public Object queryDimensionValue(DimensionValueReq dimensionValueReq, User user) {
        Integer agentId = dimensionValueReq.getAgentId();
        Agent agent = agentService.getAgent(agentId);
        dimensionValueReq.setDataSetIds(agent.getDataSetIds());
        return semanticLayerService.queryDimensionValue(dimensionValueReq, user);
    }

    public void saveQueryResult(ChatExecuteReq chatExecuteReq, QueryResult queryResult) {
        // The history record only retains the query result of the first parse
        if (chatExecuteReq.getParseId() > 1) {
            return;
        }
        chatManageService.saveQueryResult(chatExecuteReq, queryResult);
    }

    public void deleteChatQuery(Long queryId) {
        chatManageService.deleteQuery(queryId);
    }

}
