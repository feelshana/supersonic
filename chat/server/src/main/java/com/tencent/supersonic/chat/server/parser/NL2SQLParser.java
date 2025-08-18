package com.tencent.supersonic.chat.server.parser;

import com.google.common.collect.Lists;
import com.hankcs.hanlp.HanLP;
import com.tencent.supersonic.chat.api.pojo.response.ChatParseResp;
import com.tencent.supersonic.chat.api.pojo.response.QueryResp;
import com.tencent.supersonic.chat.server.persistence.dataobject.ChatQueryDO;
import com.tencent.supersonic.chat.server.pojo.ParseContext;
import com.tencent.supersonic.chat.server.service.AgentService;
import com.tencent.supersonic.chat.server.service.ChatManageService;
import com.tencent.supersonic.chat.server.util.QueryReqConverter;
import com.tencent.supersonic.common.config.EmbeddingConfig;
import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.Text2SQLExemplar;
import com.tencent.supersonic.common.pojo.enums.AppModule;
import com.tencent.supersonic.common.pojo.enums.Text2SQLType;
import com.tencent.supersonic.common.service.impl.ExemplarServiceImpl;
import com.tencent.supersonic.common.util.ChatAppManager;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.api.pojo.SchemaElementMatch;
import com.tencent.supersonic.headless.api.pojo.SchemaElementType;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.enums.MapModeEnum;
import com.tencent.supersonic.headless.api.pojo.request.QueryNLReq;
import com.tencent.supersonic.headless.api.pojo.response.MapResp;
import com.tencent.supersonic.headless.api.pojo.response.ParseResp;
import com.tencent.supersonic.headless.api.pojo.response.QueryState;
import com.tencent.supersonic.headless.chat.parser.ParserConfig;
import com.tencent.supersonic.headless.chat.service.RecommendedQuestionsService;
import com.tencent.supersonic.headless.server.facade.service.ChatLayerService;
import com.tencent.supersonic.headless.server.utils.ModelConfigHelper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.provider.ModelProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

import static com.tencent.supersonic.headless.chat.parser.ParserConfig.*;
import static dev.langchain4j.data.message.UserMessage.userMessage;

@Slf4j
public class NL2SQLParser implements ChatQueryParser {

    private static final Logger logger = LoggerFactory.getLogger(NL2SQLParser.class);

    public static final String APP_KEY_MULTI_TURN = "REWRITE_MULTI_TURN";
    private static final String REWRITE_MULTI_TURN_INSTRUCTION = "# 角色\n" +
            "咪咕数据平台数据分析师\n" +
            "\n" +
            "### 核心任务\n" +
            "1. 分析「当前问题」与「历史问题」的语义关联性（历史问题按时间正序排列：问题1[最早], 问题2, ...）\n" +
            "2. **仅当满足以下全部条件时改写问题**：\n" +
            "   - 当前问题与某个历史问题主题高度一致\n" +
            "    - 需要补充历史问题的关键信息才能完整回答\n" +
            "3. **无关时直接返回原始问题**\n" +
            "\n" +
            "### 输入\n" +
            "- 当前问题：{{current_question}}\n" +
            "- 历史问题：{{history}}\n" +
            "\n" +
            "### 输出规则\n" +
            "1. 必须且仅返回最终问题文本（无任何前缀/解释）  \n" +
            "2. 拒绝改写时直接输出当前问题";
    // private static final String REWRITE_MULTI_TURN_INSTRUCTION = ""
    // +"根据历史问题对当前问题进行改写，只需替换关键维度，不要随意改写,生成一条最符合语意的问题"
    // + "#当前问题: {{current_question}}"
    // + "#历史问题: {{history_question}}"
    // + "#改写后的问题 Question: ";

    public NL2SQLParser() {
        ChatAppManager.register(APP_KEY_MULTI_TURN,
                ChatApp.builder().prompt(REWRITE_MULTI_TURN_INSTRUCTION).name("多轮对话改写")
                        .appModule(AppModule.CHAT).description("通过大模型根据历史对话来改写本轮对话").enable(false)
                        .build());
    }

    public boolean accept(ParseContext parseContext) {
        return parseContext.enableNL2SQL();
    }

    @Override
    public void parse(ParseContext parseContext) {
        // // 1.多轮对话改写，未解析出来数据时重写
        rewriteMultiTurn(parseContext, parseContext.getAgent().getId(),parseContext.getRequest().getQueryText());

        // first go with rule-based parsers unless the user has already selected one parse.
        if (Objects.isNull(parseContext.getRequest().getSelectedParse())) {
            QueryNLReq queryNLReq = QueryReqConverter.buildQueryNLReq(parseContext);
            queryNLReq.setText2SQLType(Text2SQLType.ONLY_RULE);
            if (parseContext.enableLLM()) {
                queryNLReq.setText2SQLType(Text2SQLType.NONE);
            }

            // for every requested dataSet, recursively invoke rule-based parser with different
            // mapModes
            Set<Long> requestedDatasets = queryNLReq.getDataSetIds();
            List<SemanticParseInfo> candidateParses = Lists.newArrayList();
            StringBuilder errMsg = new StringBuilder();
            for (Long datasetId : requestedDatasets) {
                queryNLReq.setDataSetIds(Collections.singleton(datasetId));
                ChatParseResp parseResp = new ChatParseResp(parseContext.getRequest().getQueryId());
//                for (MapModeEnum mode : Lists.newArrayList(MapModeEnum.STRICT,
//                        MapModeEnum.MODERATE)) {
//                    queryNLReq.setMapModeEnum(mode);
//                    doParse(queryNLReq, parseResp);
//                }
                // Integer valueSize = 0;
                // if (!parseResp.getSelectedParses().isEmpty()) {
                // valueSize = parseResp.getSelectedParses().get(0).getElementMatches().stream()
                // .filter(schemaElementMatch -> schemaElementMatch.getElement()
                // .getType() == SchemaElementType.VALUE)
                // .collect(Collectors.toList()).size();
                // }
                // if ((parseResp.getSelectedParses().isEmpty() && candidateParses.isEmpty())
                // || valueSize == 0) {
                // queryNLReq.setMapModeEnum(MapModeEnum.LOOSE);
                // doParse(queryNLReq, parseResp);
                //
                // }
                queryNLReq.setMapModeEnum(MapModeEnum.MODERATE);
                doParse(queryNLReq, parseResp);
                List<SchemaElementMatch> keyWordsValues = new ArrayList<>();
                if (!parseResp.getSelectedParses().isEmpty()) {
                    keyWordsValues =
                            parseResp.getSelectedParses().getFirst().getElementMatches().stream()
                                    .filter(schemaElementMatch -> schemaElementMatch.getElement()
                                            .getType() == SchemaElementType.VALUE
                                            || schemaElementMatch.getElement()
                                                    .getType() == SchemaElementType.TERM)
                                    .toList();
                    parseResp.getSelectedParses().clear();
                }
                logger.info("适中模式映射到了value类型的keyWordsValues数量: {}", keyWordsValues.size());
                queryNLReq.setMapModeEnum(MapModeEnum.LOOSE);
                doParse(queryNLReq, parseResp);
                if (!CollectionUtils.isEmpty(keyWordsValues)) {
                    logger.info("适中模式映射到的keyWordsValues为: {}", keyWordsValues);
                    List<SchemaElementMatch> looseElementMatches =
                            parseResp.getSelectedParses().getFirst().getElementMatches();
                    Set<SchemaElementMatch> uniqueElements = new LinkedHashSet<>();
                    logger.info("宽松模式映射到的ElementMatches数量: {}", looseElementMatches.size());
                    for (SchemaElementMatch matchResult : looseElementMatches) {
                        log.info(
                                "宽松模式映射到的ElementMatche DetectWord=[{}],Word=[{}],similarity=[{}],dim_name=[{}],type=[{}]",
                                matchResult.getDetectWord(), matchResult.getWord(),
                                matchResult.getSimilarity(), matchResult.getElement().getName(),
                                matchResult.getElement().getType());
                    }

                    // 移除非value类型和term类型的元素
                    looseElementMatches.removeIf(schemaElementMatch -> schemaElementMatch
                            .getElement().getType() != SchemaElementType.VALUE
                            && schemaElementMatch.getElement().getType() != SchemaElementType.TERM);
                    // 移除重复的元素
                    Iterator<SchemaElementMatch> iterator = looseElementMatches.iterator();
                    while (iterator.hasNext()) {
                        SchemaElementMatch looseElementMatch = iterator.next();
                        if (isDuplicate(looseElementMatch, keyWordsValues)) {
                            iterator.remove();
                        }
                    }
                    // uniqueElements.removeIf(schemaElementMatch ->
                    // uniqueElements.contains(schemaElementMatch));
                    uniqueElements.addAll(keyWordsValues);
                    uniqueElements.addAll(looseElementMatches);
                    logger.info("宽松模式下合并keyWordsValues后的ElementMatches数量: {}",
                            uniqueElements.size());
                    for (SchemaElementMatch matchResult : uniqueElements) {
                        log.info(
                                "合并后的ElementMatche DetectWord=[{}],Word=[{}],similarity=[{}],dim_name=[{}],type=[{}]",
                                matchResult.getDetectWord(), matchResult.getWord(),
                                matchResult.getSimilarity(), matchResult.getElement().getName(),
                                matchResult.getElement().getType());
                    }
                    parseResp.getSelectedParses().getFirst().getElementMatches().clear();
                    parseResp.getSelectedParses().getFirst().getElementMatches()
                            .addAll(uniqueElements);
                }
                if (parseResp.getSelectedParses().isEmpty()) {
                    errMsg.append(parseResp.getErrorMsg());
                    continue;
                }
                // for one dataset select the top 1 parse after sorting
                SemanticParseInfo.sort(parseResp.getSelectedParses());
                candidateParses.add(parseResp.getSelectedParses().get(0));
            }
            ParserConfig parserConfig = ContextUtils.getBean(ParserConfig.class);
            int parserShowCount =
                    Integer.parseInt(parserConfig.getParameterValue(PARSER_SHOW_COUNT));
            SemanticParseInfo.sort(candidateParses);
            parseContext.getResponse().setSelectedParses(
                    candidateParses.subList(0, Math.min(parserShowCount, candidateParses.size())));
            if (parseContext.getResponse().getSelectedParses().isEmpty()) {
                parseContext.getResponse().setState(ParseResp.ParseState.FAILED);
                parseContext.getResponse().setErrorMsg(errMsg.toString());
            }
        }

        // next go with llm-based parsers unless LLM is disabled or use feedback is needed.
        if (parseContext.needLLMParse() && !parseContext.needFeedback()) {
            // either the user or the system selects one parse from the candidate parses.
            if (Objects.isNull(parseContext.getRequest().getSelectedParse())
                    && parseContext.getResponse().getSelectedParses().isEmpty()) {
                return;
            }
            QueryNLReq queryNLReq = QueryReqConverter.buildQueryNLReq(parseContext);
            queryNLReq.setText2SQLType(Text2SQLType.LLM_OR_RULE);
            SemanticParseInfo userSelectParse = parseContext.getRequest().getSelectedParse();
            queryNLReq.setSelectedParseInfo(Objects.nonNull(userSelectParse) ? userSelectParse
                    : parseContext.getResponse().getSelectedParses().get(0));
            parseContext.setResponse(new ChatParseResp(parseContext.getResponse().getQueryId()));



            // // 2.fowShot召回，召唤记忆中启用的，RAG向量库中召回
            addDynamicExemplars(parseContext, queryNLReq);
            // // 3.调用Llm生成语义sql
            doParse(queryNLReq, parseContext.getResponse());

            // try again with all semantic fields passed to LLM
            if (parseContext.getResponse().getState().equals(ParseResp.ParseState.FAILED)) {
                queryNLReq.setSelectedParseInfo(null);
                queryNLReq.setMapModeEnum(MapModeEnum.ALL);
                doParse(queryNLReq, parseContext.getResponse());
            }
        }
    }

    private boolean isDuplicate(SchemaElementMatch element,
            List<SchemaElementMatch> elementsToCompare) {
        return elementsToCompare.stream()
                .anyMatch(e -> element.getWord().equals(e.getWord())
                        && element.getElement().getName().equals(e.getElement().getName())
                        && element.getElement().getBizName().equals(e.getElement().getBizName())
                        && element.getElement().getType().equals(e.getElement().getType()));
    }

    private void doParse(QueryNLReq req, ChatParseResp resp) {
        ChatLayerService chatLayerService = ContextUtils.getBean(ChatLayerService.class);
        ParseResp parseResp = chatLayerService.parse(req);
        if (parseResp.getState().equals(ParseResp.ParseState.COMPLETED)) {
            resp.getSelectedParses().addAll(parseResp.getSelectedParses());
        }
        resp.setState(parseResp.getState());
        resp.setParseTimeCost(parseResp.getParseTimeCost());
        resp.setErrorMsg(parseResp.getErrorMsg());
    }

    public void rewriteMultiTurn(ParseContext parseContext, Integer agentId, String queryText) {
        ChatApp chatApp = parseContext.getAgent().getChatAppConfig().get(APP_KEY_MULTI_TURN);
        RecommendedQuestionsService recommendedQuestionsService =
                ContextUtils.getBean(RecommendedQuestionsService.class);
        boolean isRecommendQuestion = recommendedQuestionsService
                .findQuerySqlByQuestion(Math.toIntExact(agentId), queryText) != null;
        // 示例问题
        AgentService agentService = ContextUtils.getBean(AgentService.class);
        List<String> examples =
                agentService.getAgent(parseContext.getRequest().getAgentId()).getExamples();
        if (Objects.isNull(chatApp) || !chatApp.isEnable() || isRecommendQuestion
                || examples.contains(queryText)) {
            return;
        }

        // derive mapping result of current question and parsing result of last question.
        ChatLayerService chatLayerService = ContextUtils.getBean(ChatLayerService.class);
        // MapResp currentMapResult = chatLayerService.map(queryNLReq);

        List<QueryResp> historyQueries =
                getHistoryQueries(parseContext.getRequest().getUser().getName(),
                        parseContext.getRequest().getChatId());
        if (historyQueries.isEmpty()) {
            return;
        }
        ParserConfig parserConfig = ContextUtils.getBean(ParserConfig.class);
        List<String> historyQueryList =
                historyQueries
                        .subList(0,
                                Math.min(
                                        Integer.parseInt(
                                                parserConfig.getParameterValue(CHAT_HISTORY_NUM)),
                                        historyQueries.size()))
                        .stream().map(queryResp -> queryResp.getQueryText())
                        .collect(Collectors.toUnmodifiableList());

        if (CollectionUtils.isEmpty(historyQueryList)) {
            return;
        }
        // 指令：请根据语义理解并重写问题。
        // 规则：
        // 1. 始终保持相关的实体、指标、维度、值和日期范围不变。
        // 2. 只输出改写后的问题，无需其他解释或推理过程。
        // 3. 优先使用最近的历史问题（history.history_question）作为参考。
        // 4.
        // 改写时需结合历史数据（history.history_query_results、history.history_schema、history.history_sql）进行合理调整。
        // 5. 如果 history_query_results 匹配到当前问题中的值（如数字或日期），按以下规则改写：
        // - 将匹配的值（value）追加到历史问题中，格式为“key等于value”。
        // - 示例：
        // - 当前问题：756440
        // - 历史问题：最近一个月大结局点映活动的年累计收入是多少
        // - 改写结果：最近一个月大结局点映活动且年累计收入是756440
        //
        // 字段说明：
        // - 当前问题：{{current_question}}
        // - 当前映射模式：{{current_schema}}
        // - 历史数据：{{history}}
        //
        // 改写后的问题：
        Map<String, Object> variables = new HashMap<>();
        variables.put("current_question", queryText);
        variables.put("history", String.join(",", historyQueryList.reversed()));
        String st = "规则补充：只返回生成的结果，不需要返回推理过程";
        Prompt prompt = PromptTemplate.from(chatApp.getPrompt() + st).apply(variables);
        ChatLanguageModel chatLanguageModel =
                ModelProvider.getChatModel(ModelConfigHelper.getChatModelConfig(chatApp));
        Response<AiMessage> response = chatLanguageModel.generate(prompt.toUserMessage());
        String rewrittenQuery = response.content().text();
        // logger.info("QueryRewrite modelReq:\n{} \nmodelResp:\n{}", prompt.text(), response);
        parseContext.getRequest().setQueryText(rewrittenQuery);
        log.info(" Current Query: {}, Rewritten Query: {}", queryText, rewrittenQuery);
    }



    private String generateSchemaPrompt(List<SchemaElementMatch> elementMatches) {
        List<String> metrics = new ArrayList<>();
        List<String> dimensions = new ArrayList<>();
        List<String> values = new ArrayList<>();

        for (SchemaElementMatch match : elementMatches) {
            if (match.getElement().getType().equals(SchemaElementType.METRIC)) {
                metrics.add(match.getWord());
            } else if (match.getElement().getType().equals(SchemaElementType.DIMENSION)) {
                dimensions.add(match.getWord());
            } else if (match.getElement().getType().equals(SchemaElementType.VALUE)) {
                values.add(match.getWord());
            }
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append(String.format("'metrics:':[%s]", String.join(",", metrics)));
        prompt.append(",");
        prompt.append(String.format("'dimensions:':[%s]", String.join(",", dimensions)));
        prompt.append(",");
        prompt.append(String.format("'values:':[%s]", String.join(",", values)));

        return prompt.toString();
    }

    private List<QueryResp> getHistoryQueries(String userName, int chatId) {
        ChatManageService chatManageService = ContextUtils.getBean(ChatManageService.class);
        ParserConfig parserConfig = ContextUtils.getBean(ParserConfig.class);
        // List<QueryResp> contextualParseInfoList =
        // chatManageService.getChatQueries(chatId).stream()
        // .filter(q -> Objects.nonNull(q.getQueryResult())
        // && q.getQueryResult().getQueryState() == QueryState.SUCCESS
        // && StringUtils.endsWithIgnoreCase(userName, q.getUserName())
        // && DateUtils.calculateDiffMs(q.getCreateTime()) < 1000l * Integer
        // .parseInt(parserConfig.getParameterValue(CHAT_HISTORY_SECONDS)))
        // .collect(Collectors.toList());

        List<QueryResp> contextualParseInfoList =
                chatManageService.getChatQueriesByUserName(chatId, userName).stream()
                        .filter(q -> Objects.nonNull(q.getQueryResult())
                                && q.getQueryResult().getQueryState() == QueryState.SUCCESS)
                        .collect(Collectors.toList());
        // List<QueryResp> contextualList = contextualParseInfoList.subList(0,
        // Math.min(Integer.parseInt(parserConfig.getParameterValue(CHAT_HISTORY_NUM)),
        // contextualParseInfoList.size()));
        // Collections.reverse(contextualParseInfoList);
        return contextualParseInfoList;
    }

    private void addDynamicExemplars(ParseContext parseContext, QueryNLReq queryNLReq) {
        ExemplarServiceImpl exemplarManager = ContextUtils.getBean(ExemplarServiceImpl.class);
        EmbeddingConfig embeddingConfig = ContextUtils.getBean(EmbeddingConfig.class);
        String memoryCollectionName =
                embeddingConfig.getMemoryCollectionName(parseContext.getAgent().getId());
        ParserConfig parserConfig = ContextUtils.getBean(ParserConfig.class);
        int exemplarRecallNumber =
                Integer.parseInt(parserConfig.getParameterValue(PARSER_EXEMPLAR_RECALL_NUMBER));
        List<Text2SQLExemplar> exemplars = exemplarManager.recallExemplars(memoryCollectionName,
                queryNLReq.getQueryText(), exemplarRecallNumber);
        queryNLReq.getDynamicExemplars().addAll(exemplars);
        parseContext.getResponse().setUsedExemplars(exemplars);
    }

    public static class HistoryQuery {
        String question;



        public String getQuestion() {
            return question;
        }

        public void setQuestion(String question) {
            this.question = question;
        }

    }
}
