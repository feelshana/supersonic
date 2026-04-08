package com.tencent.supersonic.headless.chat.parser.llm;

import com.amazonaws.services.bedrockagent.model.Agent;
import com.github.pagehelper.PageInfo;
import com.google.common.collect.Lists;
import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.common.pojo.Constants;
import com.tencent.supersonic.common.pojo.Text2SQLExemplar;
import com.tencent.supersonic.common.pojo.enums.AppModule;
import com.tencent.supersonic.common.pojo.enums.MatchType;
import com.tencent.supersonic.common.pojo.enums.TypeEnums;
import com.tencent.supersonic.common.util.BeanMapper;
import com.tencent.supersonic.common.util.ChatAppManager;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.headless.api.pojo.DimValueMap;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SemanticSchema;
import com.tencent.supersonic.headless.api.pojo.enums.DimensionType;
import com.tencent.supersonic.headless.api.pojo.request.DictValueReq;
import com.tencent.supersonic.headless.api.pojo.response.DictValueDimResp;
import com.tencent.supersonic.headless.api.pojo.response.DictValueResp;
import com.tencent.supersonic.headless.api.pojo.response.DimensionResp;
import com.tencent.supersonic.headless.chat.knowledge.file.FileHandler;
import com.tencent.supersonic.headless.chat.parser.ParserConfig;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMResp;
import com.tencent.supersonic.headless.chat.service.RecommendedQuestionsService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.time.DateFormatUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.tencent.supersonic.headless.chat.parser.ParserConfig.PARSER_FORMAT_JSON_TYPE;

@Service
@Slf4j
public class OnePassSCSqlGenStrategy extends SqlGenStrategy {

    private static final Logger keyPipelineLog = LoggerFactory.getLogger("keyPipeline");
    public static final String APP_KEY = "S2SQL_PARSER";
    public static final String INSTRUCTION = "#角色：你是一位精通SQL语言的数据分析师\n"
            + "#任务：用户将提供自然语言问题，请将其转换为SQL查询语句，以便通过对底层数据库执行该SQL查询返回相关数据\n" + "#规则：\n" + "1.Schema中:\n"
            + "--Dimensions代表维度;\n" + "--Metrics代表指标;\n"
            + "--Values代表用户问题分词后，通过向量召回得到维度取值条件，供参考，当用户未明确指定维度值的情况下可参考使用。\n"
            + "2.SQL语句中查询的列名与作为过滤条件的列名，必须严格引用Schema中的Dimensions和Metrics中的字段名，完全一致，禁止任何改造\n"
            + "3.Schema中的Dimensions包含日期字段，日期字段包含FORMAT，比如<订购日期 FORMAT 'yyyyMMdd' COMMENT '订购日期'> 代表Table为日表，<订购日期 FORMAT 'yyyyMM' COMMENT '订购日期'>代表Table为月表 \n"
            + "4.#维度值说明：\n" + "  {{dimensionValues}}\n"
            + "5.当前日期为:{{currentDate}},请根据当前日期，来生成日期范围，必须使用>/</>=/<=运算符显式声明，而不是使用日期函数\n"
            + "6.为了防止输出的SQL在使用后返回数据量太大，确保输出的SQL都是限制了最大返回条数的，按照用户问题限制最多返回100条数据，根据情况在sql添加limit，保证没有语法错误。\n"
            + "7.别名使用中文\n"
            + "8.涉及两组数据计算同环比，差值等时，必须通过left join实现,禁止使用with子查询，禁止使用over函数。计算排名时请参考Exemplars中的示例,通过left join来实现\n"
            + "9.禁止使用字符串作为查询列，如 select '8月' as month\n" + "#Exemplars: {{exemplar}}\n"
            + "#Query: Question:{{question}},Schema:{{schema}},SideInfo:{{information}}\n"
            + "#排序规则\n" + "   - 当问题涉及排序要求时（如'最高'、'最低'、'top10'、'前10'等），\n"
            + "   - 必须根据问题要求添加ORDER BY子句\n" + "   - 对于'最高'、'最大'等要求，使用DESC降序排列\n"
            + "   - 对于'最低'、'最小'等要求，使用ASC升序排列\n"
            + "   - 当问题明确要求前N条记录时（如'top10'、'前10'），必须同时添加ORDER BY和LIMIT N\n";

    @Autowired
    private ParserConfig parserConfig;


    public OnePassSCSqlGenStrategy() {
        ChatAppManager.register(APP_KEY, ChatApp.builder().prompt(INSTRUCTION).name("语义SQL解析")
                .appModule(AppModule.CHAT).description("通过大模型做语义解析生成S2SQL").enable(true).build());
    }

    @Autowired
    private RecommendedQuestionsService recommendedQuestionsService;

    @Autowired
    @Qualifier("chatExecutor")
    private ThreadPoolExecutor executor;

    @Data
    static class SemanticSql {
        // @Description("告诉用户有关这个问题的查询思路，结合表的元数据与提示词中的查询规则")
        // private String thought;

        @Description("sql to generate")
        private String sql;

        // @Description("如果问题与提供的上下文无关，请礼貌引导用户提问与当前表及数据的相关问题")
        // private String message;
    }

    interface SemanticSqlExtractor {
        SemanticSql generateSemanticSql(String text);
    }

    interface StreamingSemanticParseExtractor {
        @SystemMessage("您的名字叫红海ChatBI, 您的职责是基于上下文给出查询思路.")
        @UserMessage("仅展示查询思路，不要出现表字段, 80-100字左右. {{it}}")
        Flux<String> generateStreamingSemanticParse(String text);
    }

    @Override
    public LLMResp generate(LLMReq llmReq) {

        // =================== 新增逻辑1：检查是否是推荐问题 ===================
        LLMResp recommendedResp = handleRecommendedQuestion(llmReq);
        if (recommendedResp != null) {
            log.info("匹配到推荐问题:\n{}", llmReq.getQueryText());
            return recommendedResp;
        }
        // =================== 新增逻辑2：检查是否是简易模型(直连模式) ===================
        if (isDirectLinkMode(llmReq)) {
            return handleDirectLinkMode(llmReq);
        }
        // ================== 原逻辑 ===================
        LLMResp llmResp = new LLMResp();
        llmResp.setQuery(llmReq.getQueryText());

        // 1.recall exemplars
        long recallStart = System.currentTimeMillis();
        log.debug("OnePassSCSqlGenStrategy llmReq:\n{}", llmReq);
        List<List<Text2SQLExemplar>> exemplarsList = promptHelper.getFewShotExemplars(llmReq);
        log.info("[PERFORMANCE] 召回exemplars耗时: {}ms, 组数: {}",
                System.currentTimeMillis() - recallStart, exemplarsList.size());

        // 2.generate sql generation prompt for each self-consistency inference
        ChatApp chatApp = llmReq.getChatAppConfig().get(APP_KEY);
        ChatModelConfig chatModelConfig = chatApp.getChatModelConfig();
        if (StringUtils.isBlank(parserConfig.getParameterValue(PARSER_FORMAT_JSON_TYPE))
                && chatModelConfig.getJsonFormat()) {
            chatModelConfig.setJsonFormat(false);
            chatModelConfig.setJsonFormatType("json_schema");
        }
        if (!StringUtils.isBlank(parserConfig.getParameterValue(PARSER_FORMAT_JSON_TYPE))) {
            chatModelConfig.setJsonFormat(true);
            chatModelConfig
                    .setJsonFormatType(parserConfig.getParameterValue(PARSER_FORMAT_JSON_TYPE));
        }
        ChatLanguageModel chatLanguageModel = getChatLanguageModel(chatModelConfig);
        SemanticSqlExtractor extractor =
                AiServices.create(SemanticSqlExtractor.class, chatLanguageModel);

        Map<Prompt, List<Text2SQLExemplar>> prompt2Exemplar = new HashMap<>();
        for (List<Text2SQLExemplar> exemplars : exemplarsList) {
            llmReq.setDynamicExemplars(exemplars);
            Prompt prompt = generatePrompt(llmReq, llmResp, chatApp);
            // log.info("生成提示词{}",prompt.text());
            prompt2Exemplar.put(prompt, exemplars);
        }
        // 3.perform multiple self-consistency inferences parallelly
        Map<String, Prompt> output2Prompt = new ConcurrentHashMap<>();
        prompt2Exemplar.keySet().parallelStream().forEach(prompt -> {
            long singleStart = System.currentTimeMillis();
            SemanticSql s2Sql = extractor.generateSemanticSql(prompt.toUserMessage().singleText());
            long singleCost = System.currentTimeMillis() - singleStart;
            output2Prompt.put(s2Sql.getSql(), prompt);
            log.info("[PERFORMANCE] LLM Text2SQL耗时: {}ms, SQL长度: {}", singleCost,
                    s2Sql.getSql() != null ? s2Sql.getSql().length() : 0);
            keyPipelineLog.info("OnePassSCSqlGenStrategy modelReq:\n{} \nmodelResp:\n{}",
                    prompt.text(), s2Sql);
        });
        // 4.format response.
        Pair<String, Map<String, Double>> sqlMapPair =
                ResponseHelper.selfConsistencyVote(Lists.newArrayList(output2Prompt.keySet()));
        llmResp.setSqlOutput(sqlMapPair.getLeft());
        List<Text2SQLExemplar> usedExemplars =
                prompt2Exemplar.get(output2Prompt.get(sqlMapPair.getLeft()));
        llmResp.setSqlRespMap(ResponseHelper.buildSqlRespMap(usedExemplars, sqlMapPair.getRight()));
        return llmResp;
    }

    public SseEmitter streamGenerate(LLMReq llmReq, SemanticSchema semanticSchema) {
        long start = System.currentTimeMillis();

        // 1. 创建SSE发射器（1分钟超时）
        SseEmitter emitter = new SseEmitter(60_000L);
        // 2. 在异步线程中执行后续逻辑，线程池资源隔离
        CompletableFuture.runAsync(() -> {
            try {
                // 初始化响应对象
                LLMResp llmResp = new LLMResp();
                llmResp.setQuery(llmReq.getQueryText());
                // 获取模型配置
                ChatApp chatApp = llmReq.getChatAppConfig().get(APP_KEY);
                // 使用流式专用模型配置
                StreamingChatLanguageModel streamChatModel =
                        getStreamChatModel(chatApp.getChatModelConfig());
                // 创建流式解析器
                StreamingSemanticParseExtractor extractor =
                        AiServices.create(StreamingSemanticParseExtractor.class, streamChatModel);
                // 生成prompt
                SimpleStrategy simpleStrategy = new SimpleStrategy();
                Prompt promptText = simpleStrategy.generateStreamPrompt(llmReq, semanticSchema);

                // 获取响应流，设置背压为最大100个元素
                Flux<String> thought = extractor
                        .generateStreamingSemanticParse(promptText.toUserMessage().singleText())
                        .onBackpressureBuffer(100);

                // 订阅响应流，设置延迟为100毫秒，并行调度
                log.info("模型流式通道建立耗时：" + (System.currentTimeMillis() - start) + "ms");
                long subscribeStart = System.currentTimeMillis();

                AtomicBoolean isFirst = new AtomicBoolean(true);
                Disposable subscription = thought.subscribe(chunk -> {
                    try {
                        if (isFirst.getAndSet(false)) {
                            log.info("模型流式通道响应耗时：" + (System.currentTimeMillis() - subscribeStart)
                                    + "ms");
                        }

                        // 发送单个数据块
                        emitter.send(SseEmitter.event().data(chunk));
                    } catch (IOException e) {
                        log.error("SSE send error", e);
                        emitter.completeWithError(e);
                    }
                }, error -> {
                    log.error("Stream processing error", error);
                    emitter.completeWithError(error);
                }, () -> {
                    // log.info("Stream completed successfully");
                    emitter.complete();
                });
                // 添加取消订阅处理
                emitter.onCompletion(subscription::dispose);
                emitter.onTimeout(() -> {
                    subscription.dispose();
                    emitter.complete();
                });
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }, executor);
        return emitter;
    }

    private boolean isDirectLinkMode(LLMReq llmReq) {
        // 直连模式：DataSet 名称以"直连模式"结尾
        if (StringUtils.endsWithIgnoreCase(llmReq.getSchema().getDataSetName(), "直连模式")) {
            return true;
        }
        // SIMPLE 模式：调用方显式传参 queryType="SIMPLE"，问题已高度结构化，直接带完整 Schema 生成 SQL
        return "SIMPLE".equalsIgnoreCase(llmReq.getQueryType());
    }

    private LLMResp handleDirectLinkMode(LLMReq llmReq) {
        LLMResp llmResp = new LLMResp();
        llmResp.setQuery(llmReq.getQueryText());
        Map<Prompt, List<Text2SQLExemplar>> prompt2Exemplar = new HashMap<>();
        try {
            List<List<Text2SQLExemplar>> exemplarsList =
                    promptHelper.getFewShotExemplarsByHistory(llmReq);
            for (List<Text2SQLExemplar> exemplars : exemplarsList) {
                llmReq.setDynamicExemplars(exemplars);
                SimpleStrategy simpleStrategy = new SimpleStrategy();
                String dimensionValueInfo = buildDimensionValueInfo(llmReq);
                Prompt promptText =
                        simpleStrategy.generatePrompt(llmReq, promptHelper, dimensionValueInfo);
                prompt2Exemplar.put(promptText, exemplars);
            }
        } catch (Exception e) {
            log.warn("few-shot召唤异常", e);
        }


        ChatApp chatApp = llmReq.getChatAppConfig().get(APP_KEY);
        ChatLanguageModel languageModel = getChatLanguageModel(chatApp.getChatModelConfig());
        SemanticSqlExtractor extractor =
                AiServices.create(SemanticSqlExtractor.class, languageModel);

        long llmStart = System.currentTimeMillis();
        Map<String, Prompt> output2Prompt = new ConcurrentHashMap<>();
        prompt2Exemplar.keySet().parallelStream().forEach(prompt -> {
            SemanticSql s2Sql = extractor.generateSemanticSql(prompt.toUserMessage().singleText());
            String key = pickFirstNonBlank(s2Sql.getSql());
            output2Prompt.put(key, prompt);
            keyPipelineLog.info("OnePassSCSqlGenStrategy modelReq:\n{} \nmodelResp:\n{}",
                    prompt.text(), s2Sql);
        });

        Pair<String, Map<String, Double>> sqlMapPair =
                ResponseHelper.selfConsistencyVote(Lists.newArrayList(output2Prompt.keySet()));
        llmResp.setSqlOutput(sqlMapPair.getLeft());

        List<Text2SQLExemplar> usedExemplars =
                prompt2Exemplar.get(output2Prompt.get(sqlMapPair.getLeft()));
        llmResp.setSqlRespMap(ResponseHelper.buildSqlRespMap(usedExemplars, sqlMapPair.getRight()));

        log.info("[PERFORMANCE] LLM Text2SQL耗时: {}ms, SQL: {}",
                System.currentTimeMillis() - llmStart, llmResp.getSqlOutput());
        return llmResp;
    }

    /**
     * 返回第一个非空的字符串(先看 sql, 再看 message, 再看 thought)
     */
    private String pickFirstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (StringUtils.isNotBlank(c)) {
                return c;
            }
        }
        return "";
    }

    /**
     * 如果当前提问是“推荐问题”，则直接返回包含该 SQL 的 LLMResp； 若不是，返回 null。
     */
    private LLMResp handleRecommendedQuestion(LLMReq llmReq) {
        if (llmReq.getAgentId() == null) {
            return null;
        }
        // 去掉用户输入问题末尾的符号
        String queryText = llmReq.getQueryText().replaceAll("[。？！.,?！]+$", "");
        String querySql = recommendedQuestionsService
                .findQuerySqlByQuestion(Math.toIntExact(llmReq.getAgentId()), queryText);
        if (StringUtils.isNotEmpty(querySql)) {
            LLMResp resp = new LLMResp();
            resp.setQuery(llmReq.getQueryText());
            resp.setSqlOutput(querySql);
            resp.setSqlRespMap(ResponseHelper.buildSqlRespMap(Collections.emptyList(),
                    Collections.emptyMap()));
            log.info("查到推荐问题对应的sql: {}", querySql);
            return resp;
        }
        return null;
    }

    @Autowired
    private FileHandler fileHandler;

    public Prompt generatePrompt(LLMReq llmReq, LLMResp llmResp, ChatApp chatApp) {
        StringBuilder exemplars = new StringBuilder();
        for (Text2SQLExemplar exemplar : llmReq.getDynamicExemplars()) {
            String exemplarStr = String.format("\nQuestion:%s,Schema:%s,SideInfo:%s,SQL:%s",
                    exemplar.getQuestion(), exemplar.getDbSchema(), exemplar.getSideInfo(),
                    exemplar.getSql());
            exemplars.append(exemplarStr);
        }
        String dataSemantics = promptHelper.buildSchemaStr(llmReq);
        String sideInformation = promptHelper.buildSideInformation(llmReq);
        llmResp.setSchema(dataSemantics);
        llmResp.setSideInfo(sideInformation);
        String dimensionValueInfo = buildDimensionValueInfo(llmReq);
        Map<String, Object> variable = new HashMap<>();
        variable.put("exemplar", exemplars);
        variable.put("question", llmReq.getQueryText());
        variable.put("schema", dataSemantics);
        variable.put("information", sideInformation);
        variable.put("dimensionValues", dimensionValueInfo);
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy年MM月dd日");
        String currentDate = dateFormat.format(new Date());
        variable.put("currentDate", currentDate);
        if (llmReq.getAgentId() != null && llmReq.getAgentId() == 43) {
            String currentDayRule = "所有日期不用日期函数，根据今天的日期去推算过去，今天的日期是"
                    + DateFormatUtils.format(new Date(), "yyyyMMdd") + "\n";
            variable.put("current-day-rule", currentDayRule);
        }
        // use custom prompt template if provided.
        String promptTemplate = chatApp.getPrompt();
        return PromptTemplate.from(promptTemplate).apply(variable);
    }

    public String buildDimensionValueInfo(LLMReq llmReq) {
        StringBuilder dimensionValueInfo = new StringBuilder();
        boolean hasDimensionValues = false;
        List<SchemaElement> dimensions = llmReq.getSchema().getDimensions();
        // 提取Values中matchTpye不为空的且类型为keyword的维度名称
        Set<String> matchedDimensionNames = llmReq.getSchema().getValues().stream()
                .filter(elementValue -> StringUtils.isNotBlank(elementValue.getMatchType())
                        && elementValue.getMatchType().equals(MatchType.EMBEDDING.name()))
                .map(LLMReq.ElementValue::getFieldValue).collect(Collectors.toSet());
        for (SchemaElement dimension : dimensions) {
            // 筛选条件1：跳过已经匹配到的维度
            if (matchedDimensionNames.contains(dimension.getName())) {
                continue;
            }
            // 筛选条件2：跳过省份、城市和日期维度
            if (isSkipDimension(dimension)) {
                continue;
            }
            // 筛选条件3：跳过没有维度值的维度
            if (!dimension.isHasDimValues()) {
                continue;
            }
            PageInfo<DictValueDimResp> dimensionValuesFromDict =
                    getDimensionValuesFromDict(dimension);
            List<DictValueDimResp> list = dimensionValuesFromDict.getList();
            List<String> dimensionValues = list.stream().map(DictValueDimResp::getValue).toList();
            // 筛选条件4：跳过维度值数量为0和数量大于等于50的维度
            if (CollectionUtils.isEmpty(dimensionValues) || dimensionValues.size() >= 50) {
                continue;
            }
            dimensionValueInfo.append(dimension.getName()).append("包含如下维度值: ")
                    .append(String.join("，", dimensionValues)).append("\n");
            hasDimensionValues = true;
        }
        // 添加说明
        if (hasDimensionValues) {
            dimensionValueInfo.append("请注意，根据用户的语义，和上述的维度值可选内容，生成维度选条件，作为sql的where条件.\n");
            return dimensionValueInfo.toString();
        }

        return "";
    }

    // public PageInfo<DictValueDimResp> getDimensionValuesFromDict(SchemaElement dimension) {
    // DictValueReq dictValueReq = new DictValueReq();
    // dictValueReq.setModelId(dimension.getModel());
    // dictValueReq.setItemId(dimension.getId());
    // dictValueReq.setType(TypeEnums.DIMENSION);
    // dictValueReq.setPageSize(50);
    // dictValueReq.setCurrent(1);
    // String fileName = String.format("dic_value_%d_%s_%s", dictValueReq.getModelId(),
    // dictValueReq.getType().name(), dictValueReq.getItemId()) + Constants.DOT + "txt";
    // PageInfo<DictValueResp> dictValueRespList =
    // fileHandler.queryDictValue(fileName, dictValueReq);
    // PageInfo<DictValueDimResp> result = convert2DictValueDimRespPage(dictValueRespList);
    // fillDimMapInfo(result.getList(), dimension);
    // return result;
    // }
    // 暂停使用词典获取维度值，使用维度值映射表获取
    public PageInfo<DictValueDimResp> getDimensionValuesFromDict(SchemaElement dimension) {
        return getDimensionValues(dimension);
    }

    public PageInfo<DictValueDimResp> getDimensionValues(SchemaElement dimension) {
        return getDimensionValuesFromMaps(dimension);
    }

    private PageInfo<DictValueDimResp> getDimensionValuesFromMaps(SchemaElement dimension) {
        PageInfo<DictValueDimResp> pageInfo = new PageInfo<>();
        if (dimension == null || CollectionUtils.isEmpty(dimension.getSchemaValueMaps())) {
            pageInfo.setList(new ArrayList<>());
            return pageInfo;
        }
        List<DictValueDimResp> list = dimension.getSchemaValueMaps().stream()
                .filter(Objects::nonNull).map(dimValueMap -> {
                    DictValueDimResp resp = new DictValueDimResp();
                    resp.setValue(dimValueMap.getValue());
                    resp.setBizName(dimValueMap.getBizName());
                    resp.setAlias(dimValueMap.getAlias());
                    return resp;
                }).filter(resp -> StringUtils.isNotBlank(resp.getValue())).limit(50)
                .collect(Collectors.toList());
        pageInfo.setList(list);
        pageInfo.setTotal(list.size());
        pageInfo.setPageNum(1);
        pageInfo.setPageSize(50);
        return pageInfo;
    }

    private void fillDimMapInfo(List<DictValueDimResp> list, SchemaElement dimension) {

        if (CollectionUtils.isEmpty(dimension.getDimValueMaps())) {
            return;
        }
        Map<String, DimValueMap> valueAndMap = dimension.getDimValueMaps().stream()
                .collect(Collectors.toMap(DimValueMap::getValue, v -> v, (v1, v2) -> v2));
        if (CollectionUtils.isEmpty(valueAndMap)) {
            return;
        }
        list.forEach(dictValueDimResp -> {
            String dimValue = dictValueDimResp.getValue();
            if (valueAndMap.containsKey(dimValue) && Objects.nonNull(valueAndMap.get(dimValue))) {
                dictValueDimResp.setAlias(valueAndMap.get(dimValue).getAlias());
            }
        });
    }

    private PageInfo<DictValueDimResp> convert2DictValueDimRespPage(
            PageInfo<DictValueResp> dictValueRespPage) {
        PageInfo<DictValueDimResp> result = new PageInfo<>();
        BeanMapper.mapper(dictValueRespPage, result);
        if (CollectionUtils.isEmpty(dictValueRespPage.getList())) {
            return result;
        }

        List<DictValueDimResp> list = getDictValueDimRespList(dictValueRespPage.getList());
        result.setList(list);
        return result;
    }

    private List<DictValueDimResp> getDictValueDimRespList(List<DictValueResp> dictValueRespList) {
        List<DictValueDimResp> list = dictValueRespList.stream()
                .map(this::convert2DictValueInternal).collect(Collectors.toList());
        return list;
    }

    private DictValueDimResp convert2DictValueInternal(DictValueResp dictValue) {
        DictValueDimResp dictValueDimResp = new DictValueDimResp();
        BeanMapper.mapper(dictValue, dictValueDimResp);
        return dictValueDimResp;
    }

    private boolean isSkipDimension(SchemaElement dimension) {
        if (dimension == null) {
            return true;
        }
        // 跳过省份、城市和日期维度
        String dimensionName = dimension.getName().toLowerCase();
        return dimensionName.contains("省份") || dimensionName.contains("城市")
                || dimensionName.contains("日期") || dimensionName.contains("时间")
                || dimensionName.contains("province") || dimensionName.contains("city")
                || dimensionName.contains("date") || dimensionName.contains("time");
    }

    @Override
    public void afterPropertiesSet() {
        SqlGenStrategyFactory
                .addSqlGenerationForFactory(LLMReq.SqlGenType.ONE_PASS_SELF_CONSISTENCY, this);
    }

}
