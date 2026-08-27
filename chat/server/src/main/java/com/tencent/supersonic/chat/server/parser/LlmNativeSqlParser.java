package com.tencent.supersonic.chat.server.parser;

import com.tencent.supersonic.chat.api.pojo.request.ChatParseReq;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.config.LlmNativeParserConfig;
import com.tencent.supersonic.chat.server.parser.llmnative.LlmNativeContext;
import com.tencent.supersonic.chat.server.parser.llmnative.LlmNativeSchemaBuilder;
import com.tencent.supersonic.chat.server.parser.llmnative.LlmNativeSegmentService;
import com.tencent.supersonic.chat.server.parser.llmnative.LlmNativeSqlPostProcessor;
import com.tencent.supersonic.chat.server.pojo.ParseContext;
import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SchemaElementType;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.SqlInfo;
import com.tencent.supersonic.headless.api.pojo.response.ParseResp;
import com.tencent.supersonic.headless.server.utils.ModelConfigHelper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.provider.ModelProvider;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Set;

import static com.tencent.supersonic.headless.chat.parser.llm.OnePassSCSqlGenStrategy.APP_KEY;

/**
 * LLM_NATIVE 模式解析器：跳过 MAPPING / PARSING / CORRECTING / TRANSLATING 全流程， 直接把完整的物理表结构（含 BI customs
 * 新增列表达式、指标聚合方式）交给 LLM 生成物理 SQL， 再由代码做确定性后处理（字段校验、默认值注入、LIMIT 兜底）。
 *
 * <p>
 * 设计要点：本类实现 {@link ChatQueryParser} SPI 而不是在 {@code parseAndExecute} 里短路， 因此
 * {@code /parse}+{@code /execute} 分离调用与 {@code /parseAndExecute} 统一调用都能生效。 生成的物理 SQL 同时写入
 * {@code sqlInfo.querySQL} 和 {@code sqlInfo.correctedS2SQL}： 前者让下游跳过 Calcite 翻译层，后者是
 * {@code SqlExecutor} 的非空前置校验所必需。
 *
 * <p>
 * 失败重试无需额外实现：{@code ChatQueryServiceImpl.retryWithErrorFeedback()} 会带着 {@code errorFeedback} 重新走
 * parse 链路，本类读取该字段注入提示词即可。
 */
@Slf4j
public class LlmNativeSqlParser implements ChatQueryParser {

    /** 请求中 queryType 取该值时进入本模式。 */
    public static final String QUERY_TYPE = "llm_native";

    /** 供 SqlExecutor 与前端区分的查询模式标识。 */
    public static final String QUERY_MODE = "LLM_NATIVE_SQL";

    private static final String PROMPT_FILE_NAME = "llm_native_prompt.txt";
    private static final String PLACEHOLDER_DATE = "{{CURRENT_DATE}}";
    private static final String PLACEHOLDER_SCHEMA = "{{SCHEMA_INFO}}";
    private static final String PLACEHOLDER_QUERY = "{{QUERY_TEXT}}";
    private static final String PLACEHOLDER_ENGINE = "{{ENGINE_TYPE}}";
    private static final String PLACEHOLDER_ERROR = "{{ERROR_FEEDBACK}}";
    private static final String PLACEHOLDER_CUSTOM_RULES = "{{CUSTOM_RULES}}";
    private static final String PLACEHOLDER_TABLE_TYPE_HINT = "{{TABLE_TYPE_HINT}}";

    /**
     * Agent 提示词中“报表定制规则”的起始标记。BI 训练时 {@code BiAgentServiceImpl.buildNewRulesContent()}
     * 会以该标记开头追加报表规则，用户后续也可在前端继续补充。从该标记到提示词结尾即为定制规则部分。
     */
    private static final String CUSTOM_RULES_MARKER = "Sql生成的限制条件";

    /** 定制规则中出现该词时，判定为明细表，切换为明细表提示。 */
    private static final String DETAIL_TABLE_MARKER = "当前报表是明细表";

    /** 默认表类型提示：预聚合结果表。覆盖绝大多数报表，明细表通过定制规则显式声明。 */
    private static final String RESULT_TABLE_HINT =
            "【表类型】本数据集为预聚合结果表：" + "指标字段的表达式即最终口径，裸字段表示已预聚合的最终值，直接 SELECT，不要再套聚合函数，也不要为其 GROUP BY；"
                    + "若表达式自带聚合函数，则按 GROUP BY 规则处理。";

    /** 明细表提示：按需聚合。 */
    private static final String DETAIL_TABLE_HINT = "【表类型】本数据集为明细表："
            + "每行是一条业务记录，维度组合可能重复。请依据用户问题的统计口径，对指标字段按需使用聚合函数并配合 GROUP BY；" + "字段表达式自带聚合时按原样使用。";

    /**
     * 是否接管本次解析。判定优先级：
     *
     * <ol>
     * <li>请求显式传了 {@code queryType} → 严格按它判定。传本模式则接管，传其他模式（simple/super_simple）则让位，
     * 保证调用方能显式覆盖配置，便于灰度与联调。</li>
     * <li>请求没传 → 先看 Agent 是否在排除名单（{@link LlmNativeParserConfig#getExcludeAgentIds()}）， 命中则让位、回退原
     * NL2SQLParser；否则看全量开关 {@code enable-all-bi-agents}。</li>
     * </ol>
     */
    @Override
    public boolean accept(ParseContext parseContext) {
        if (parseContext == null || parseContext.getRequest() == null) {
            return false;
        }
        String queryType = parseContext.getRequest().getQueryType();
        if (StringUtils.isNotBlank(queryType)) {
            return QUERY_TYPE.equalsIgnoreCase(queryType);
        }
        return isEnabledForAgent(parseContext.getAgent());
    }

    /** 排除名单优先（命中即回退原模式）；否则看"对所有 BI 报表 Agent 生效"开关。 */
    private boolean isEnabledForAgent(Agent agent) {
        if (agent == null || agent.getId() == null) {
            return false;
        }
        try {
            LlmNativeParserConfig config = ContextUtils.getBean(LlmNativeParserConfig.class);
            if (config == null) {
                return false;
            }
            if (config.getExcludeAgentIds().contains(agent.getId())) {
                log.info("[LLM_NATIVE] Agent[{}] 在排除名单中，回退原 NL2SQL 模式", agent.getId());
                return false;
            }
            return config.isEnableAllBiAgents();
        } catch (Exception e) {
            log.warn("[LLM_NATIVE] 读取模式启用配置失败，按未启用处理", e);
            return false;
        }
    }

    @Override
    public void parse(ParseContext parseContext) {
        long start = System.currentTimeMillis();
        ChatParseReq request = parseContext.getRequest();
        try {
            Long dataSetId = resolveDataSetId(parseContext);
            LlmNativeContext context =
                    LlmNativeSchemaBuilder.build(dataSetId, parseContext.getAgent());
            log.info("[LLM_NATIVE] Schema 构建完成，dataSetId={}, modelId={}, 维度{}个, 白名单字段{}个",
                    dataSetId, context.getModelId(), context.getDimensions().size(),
                    context.getAllowedFields().size());
            log.debug("[LLM_NATIVE] SchemaInfo:\n{}", context.getSchemaText());

            // 分词：独立的一次 LLM 调用，识别"分布/排名/TopN"类问题涉及的维度，
            // 作为默认值注入"排除汇总行(!=)"的权威信号。与 SQL 生成职责分离。
            LlmNativeSegmentService.segment(request.getQueryText(), context,
                    parseContext.getAgent());

            String prompt = renderPrompt(context, request, parseContext.getAgent());
            String rawSql = generateSql(parseContext, prompt);
            log.info("[LLM_NATIVE] LLM 原始输出: {}", rawSql);

            String finalSql = LlmNativeSqlPostProcessor.process(rawSql, context);
            log.info("[LLM_NATIVE] 后处理完成，最终物理SQL: {}", finalSql);

            fillParseInfo(parseContext, context, finalSql);
            parseContext.getResponse().setState(ParseResp.ParseState.COMPLETED);
            log.info("[PERFORMANCE] LLM_NATIVE 解析总耗时: {}ms", System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("[LLM_NATIVE] 解析失败, queryText: {}",
                    request != null ? request.getQueryText() : null, e);
            parseContext.getResponse().setState(ParseResp.ParseState.FAILED);
            parseContext.getResponse().setErrorMsg("SQL生成失败：" + e.getMessage());
        }
    }

    /** 数据集优先取请求显式指定的，否则取 Agent 绑定的第一个。 */
    private Long resolveDataSetId(ParseContext parseContext) {
        Long requested = parseContext.getRequest().getDataSetId();
        if (requested != null) {
            return requested;
        }
        Agent agent = parseContext.getAgent();
        if (agent == null) {
            throw new IllegalStateException("未找到 Agent: " + parseContext.getRequest().getAgentId());
        }
        Set<Long> dataSetIds = agent.getDataSetIds();
        if (dataSetIds == null || dataSetIds.isEmpty()) {
            throw new IllegalStateException("Agent 未绑定任何数据集");
        }
        if (dataSetIds.size() > 1) {
            log.warn("[LLM_NATIVE] Agent 绑定了 {} 个数据集，当前仅支持单数据集，取第一个。如需指定请传 dataSetId",
                    dataSetIds.size());
        }
        return dataSetIds.iterator().next();
    }

    private String renderPrompt(LlmNativeContext context, ChatParseReq request, Agent agent)
            throws Exception {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String engineName =
                context.getEngineType() != null ? context.getEngineType().getName() : "MySQL";
        // 定制规则只提取一次，同时供“表类型提示”与“定制规则段”使用
        String customRules = extractCustomRules(agent);
        return readPromptTemplate().replace(PLACEHOLDER_DATE, today)
                .replace(PLACEHOLDER_ENGINE, engineName)
                .replace(PLACEHOLDER_SCHEMA, context.getSchemaText())
                .replace(PLACEHOLDER_TABLE_TYPE_HINT, buildTableTypeHint(customRules))
                .replace(PLACEHOLDER_CUSTOM_RULES, buildCustomRulesSection(customRules))
                .replace(PLACEHOLDER_ERROR, buildErrorFeedbackSection(request))
                .replace(PLACEHOLDER_QUERY, StringUtils.defaultString(request.getQueryText()));
    }

    /**
     * 构建表类型提示。默认按“预聚合结果表”处理（覆盖绝大多数报表）； 当 BI 在定制规则中显式写明“明细表”时，切换为明细表提示。 采用“默认结果表 +
     * 显式声明明细表”的确定性策略，避免自动识别的静默误判。
     */
    private String buildTableTypeHint(String customRules) {
        if (StringUtils.isNotBlank(customRules) && customRules.contains(DETAIL_TABLE_MARKER)) {
            log.info("[LLM_NATIVE] 定制规则中识别到 [{}] 标记，使用明细表提示", DETAIL_TABLE_MARKER);
            return DETAIL_TABLE_HINT;
        }
        return RESULT_TABLE_HINT;
    }

    /**
     * 构建“本报表定制规则”段落。无定制规则时返回空串（占位符被替换为空，不影响基础提示词）。
     */
    private String buildCustomRulesSection(String customRules) {
        if (StringUtils.isBlank(customRules)) {
            return "";
        }
        return "### 本报表定制规则（在上述通用规则之外，同样必须严格遵守）\n\n" + customRules + "\n";
    }

    /**
     * 从 Agent 的 S2SQL_PARSER 提示词中提取报表定制规则。 规则部分以 {@link #CUSTOM_RULES_MARKER} 标记开头，取该标记到提示词结尾；
     * 找不到标记（说明该 Agent 无定制规则）或解析异常时返回空串，不阻断主流程。
     */
    private String extractCustomRules(Agent agent) {
        try {
            ChatApp chatApp = agent != null && agent.getChatAppConfig() != null
                    ? agent.getChatAppConfig().get(APP_KEY)
                    : null;
            if (chatApp == null || StringUtils.isBlank(chatApp.getPrompt())) {
                return "";
            }
            String prompt = chatApp.getPrompt();
            int markerIndex = prompt.indexOf(CUSTOM_RULES_MARKER);
            if (markerIndex < 0) {
                log.info("[LLM_NATIVE] Agent提示词中未找到定制规则标记 [{}]，跳过定制规则注入", CUSTOM_RULES_MARKER);
                return "";
            }
            String customRules = prompt.substring(markerIndex).trim();
            log.info("[LLM_NATIVE] 提取到本报表定制规则，长度: {} 字符", customRules.length());
            log.debug("[LLM_NATIVE] 定制规则内容:\n{}", customRules);
            return customRules;
        } catch (Exception e) {
            log.warn("[LLM_NATIVE] 提取定制规则失败，跳过", e);
            return "";
        }
    }

    /** 重试场景下把上次的失败 SQL 与错误原因回喂给 LLM。 */
    private String buildErrorFeedbackSection(ChatParseReq request) {
        String errorFeedback = request.getErrorFeedback();
        if (StringUtils.isBlank(errorFeedback)) {
            return "";
        }
        return "### 上一次生成的 SQL 执行失败，请修正后重新生成\n\n" + errorFeedback + "\n";
    }

    private String generateSql(ParseContext parseContext, String prompt) {
        Agent agent = parseContext.getAgent();
        ChatApp chatApp =
                agent.getChatAppConfig() != null ? agent.getChatAppConfig().get(APP_KEY) : null;
        if (chatApp == null) {
            throw new IllegalStateException("Agent 未配置 " + APP_KEY + " ChatApp");
        }
        ChatModelConfig modelConfig = ModelConfigHelper.getChatModelConfig(chatApp);
        if (modelConfig == null) {
            throw new IllegalStateException("ChatApp 模型配置为空");
        }
        long llmStart = System.currentTimeMillis();
        ChatLanguageModel llm = ModelProvider.getChatModel(modelConfig);
        SqlExtractor extractor = AiServices.create(SqlExtractor.class, llm);
        String sql = extractor.generateSql(prompt);
        log.info("[PERFORMANCE] LLM_NATIVE LLM生成SQL耗时: {}ms",
                System.currentTimeMillis() - llmStart);
        return sql;
    }

    /**
     * 组装 SemanticParseInfo。dataSet 必须设置，否则 {@code SqlExecutor} 拿不到 dataSetId； querySQL 与
     * correctedS2SQL 都要设置，原因见类注释。
     */
    private void fillParseInfo(ParseContext parseContext, LlmNativeContext context, String sql) {
        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.setId(1);
        parseInfo.setQueryMode(QUERY_MODE);
        parseInfo.setScore(1.0);

        SchemaElement dataSet =
                SchemaElement.builder().dataSetId(context.getDataSetId()).id(context.getDataSetId())
                        .model(context.getModelId()).name(context.getDataSetName())
                        .bizName(context.getDataSetName()).type(SchemaElementType.DATASET).build();
        parseInfo.setDataSet(dataSet);

        SqlInfo sqlInfo = new SqlInfo();
        sqlInfo.setQuerySQL(sql);
        // SqlExecutor.doExecute() 会校验 correctedS2SQL 非空，为空则直接返回 null 不执行
        sqlInfo.setCorrectedS2SQL(sql);
        // 故意不设 parsedS2SQL：ParseInfoFormatProcessor 依赖它非空才会尝试从 S2SQL 反推
        // 维度/指标，而它按中文名匹配字段，对物理 SQL 匹配不到任何结果，只会白跑一遍并多查一次 DataSetSchema。
        parseInfo.setSqlInfo(sqlInfo);

        parseContext.getResponse().getSelectedParses().add(parseInfo);
    }

    /** 优先读外部 conf 目录以支持线上热更，回退 classpath 内置文件。 */
    private String readPromptTemplate() throws Exception {
        String externalPath = System.getProperty("user.dir") + "/conf/" + PROMPT_FILE_NAME;
        File externalFile = new File(externalPath);
        if (externalFile.exists()) {
            log.info("[LLM_NATIVE] 从外部文件加载提示词: {}", externalPath);
            return new String(Files.readAllBytes(Paths.get(externalPath)), StandardCharsets.UTF_8);
        }
        ClassPathResource resource = new ClassPathResource(PROMPT_FILE_NAME);
        if (!resource.exists()) {
            throw new IllegalStateException("提示词文件不存在: " + PROMPT_FILE_NAME);
        }
        try (InputStream is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** LLM 接口定义，直接返回 SQL 字符串。 */
    interface SqlExtractor {
        @UserMessage("{{it}}")
        String generateSql(String prompt);
    }
}
