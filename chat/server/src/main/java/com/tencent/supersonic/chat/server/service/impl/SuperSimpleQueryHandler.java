package com.tencent.supersonic.chat.server.service.impl;

import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.chat.api.pojo.request.ChatParseReq;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.service.AgentService;
import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.headless.api.pojo.DataSetSchema;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SqlInfo;
import com.tencent.supersonic.headless.api.pojo.request.QuerySqlReq;
import com.tencent.supersonic.headless.api.pojo.response.DictValueDimResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.api.pojo.response.QueryState;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.chat.parser.llm.OnePassSCSqlGenStrategy;
import com.tencent.supersonic.headless.server.facade.service.SemanticLayerService;
import com.tencent.supersonic.headless.server.service.SchemaService;
import com.tencent.supersonic.headless.server.utils.ModelConfigHelper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.provider.ModelProvider;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.CollectionUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

import static com.tencent.supersonic.headless.chat.parser.llm.OnePassSCSqlGenStrategy.APP_KEY;

/**
 * SUPER_SIMPLE 模式处理器：跳过 mapping/parsing/correct/translate， 直接根据数据集 Schema 组装提示词，调用 LLM 生成物理
 * SQL，然后执行并返回结果。
 */
@Slf4j
public class SuperSimpleQueryHandler {

    private static final String PROMPT_FILE_NAME = "super_simple_prompt.txt";
    private static final String PLACEHOLDER_DATE = "{{CURRENT_DATE}}";
    private static final String PLACEHOLDER_SCHEMA = "{{SCHEMA_INFO}}";
    private static final String PLACEHOLDER_QUERY = "{{QUERY_TEXT}}";
    private final AgentService agentService;
    private final SemanticLayerService semanticLayerService;

    public SuperSimpleQueryHandler(AgentService agentService,
            SemanticLayerService semanticLayerService) {
        this.agentService = agentService;
        this.semanticLayerService = semanticLayerService;
    }

    public QueryResult execute(ChatParseReq chatParseReq) {
        long start = System.currentTimeMillis();
        try {
            // Step 1: 获取 Agent 绑定的第一个 dataSetId
            Agent agent = agentService.getAgent(chatParseReq.getAgentId());
            if (agent == null) {
                return errorResult("未找到 Agent: " + chatParseReq.getAgentId());
            }
            Set<Long> dataSetIds = agent.getDataSetIds();
            if (dataSetIds == null || dataSetIds.isEmpty()) {
                return errorResult("Agent 未绑定任何数据集");
            }
            Long dataSetId = dataSetIds.stream().findFirst().get();

            // Step 2: 获取数据集 Schema，构建字段描述
            DataSetSchema schema = semanticLayerService.getDataSetSchema(dataSetId);
            if (schema == null) {
                return errorResult("数据集 Schema 获取失败，dataSetId=" + dataSetId);
            }
            // 解析 modelId，供后续获取物理表名和数据库连接复用
            Long resolvedModelId = resolveModelId(schema);
            String schemaInfo = buildSchemaInfo(schema, resolvedModelId);

            // Step 3: 读取提示词模板，渲染占位符
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String promptTemplate = readPromptTemplate();
            String prompt = promptTemplate.replace(PLACEHOLDER_DATE, today)
                    .replace(PLACEHOLDER_SCHEMA, schemaInfo)
                    .replace(PLACEHOLDER_QUERY, chatParseReq.getQueryText());
            log.info("[SUPER_SIMPLE] 提示词已组装，Schema字段数: 维度{}个，指标{}个", schema.getDimensions().size(),
                    schema.getMetrics().size());

            // Step 4: 调用 LLM 生成 SQL
            ChatApp chatApp = agent.getChatAppConfig().get(APP_KEY);
            if (chatApp == null) {
                return errorResult("Agent 未配置 " + APP_KEY + " ChatApp");
            }
            ChatModelConfig modelConfig = ModelConfigHelper.getChatModelConfig(chatApp);
            if (modelConfig == null) {
                return errorResult("ChatApp 模型配置为空");
            }
            long llmStart = System.currentTimeMillis();
            ChatLanguageModel llm = ModelProvider.getChatModel(modelConfig);
            SqlExtractor extractor = AiServices.create(SqlExtractor.class, llm);
            String sql = extractor.generateSql(prompt);
            log.info("[PERFORMANCE] SUPER_SIMPLE LLM生成SQL耗时: {}ms, SQL: {}",
                    System.currentTimeMillis() - llmStart, sql);

            if (StringUtils.isBlank(sql)) {
                return errorResult("LLM 未生成有效 SQL");
            }
            // 清理 LLM 可能输出的 markdown 代码块标记
            sql = cleanSql(sql);

            // Step 5: 将物理 SQL 通过 sqlInfo.querySQL 交给 semanticLayerService 执行
            // queryByReq 检测到 sqlInfo.querySQL 非空时，会直接 setSql + setIsTranslated(true)，
            // 跳过 Calcite 翻译层，JdbcExecutor 用 ontology.getDatabase() 执行，数据库连接配置完全正确
            QuerySqlReq querySqlReq = new QuerySqlReq();
            querySqlReq.setDataSetId(dataSetId);
            querySqlReq.setSql(sql);
            SqlInfo sqlInfo = new SqlInfo();
            sqlInfo.setQuerySQL(sql);
            querySqlReq.setSqlInfo(sqlInfo);
            querySqlReq.setNeedAuth(false);
            User user = chatParseReq.getUser();
            SemanticQueryResp resp = semanticLayerService.queryByReq(querySqlReq, user);

            // Step 6: 转换为 QueryResult
            QueryResult result = new QueryResult();
            result.setQuerySql(sql);
            if (resp != null) {
                result.setQueryColumns(resp.getColumns());
                result.setQueryResults(resp.getResultList());
                if (StringUtils.isBlank(resp.getErrorMsg())) {
                    result.setQueryState(QueryState.SUCCESS);
                } else {
                    result.setQueryState(QueryState.INVALID);
                    result.setErrorMsg(resp.getErrorMsg());
                }
            } else {
                result.setQueryState(QueryState.EMPTY);
            }
            log.info("[SUPER_SIMPLE] 执行完成，总耗时: {}ms，返回行数: {}", System.currentTimeMillis() - start,
                    resp != null && resp.getResultList() != null ? resp.getResultList().size() : 0);
            return result;

        } catch (Exception e) {
            log.error("[SUPER_SIMPLE] 执行失败", e);
            return errorResult("SUPER_SIMPLE 模式执行失败: " + e.getMessage());
        }
    }

    /**
     * 将 DataSetSchema 格式化为 LLM 可理解的字段描述文本。 包含数据集名称、真实物理表名、维度字段、指标字段。
     */
    private String buildSchemaInfo(DataSetSchema schema, Long modelId) {
        StringBuilder sb = new StringBuilder();
        SchemaElement dataSet = schema.getDataSet();
        sb.append("数据集名称: ").append(dataSet.getName()).append("\n");

        // 获取真实物理表名（从 Model 的 tableQuery 字段）
        String physicalTableName = resolvePhysicalTableName(modelId);
        if (StringUtils.isNotBlank(physicalTableName)) {
            // 若含库名前缀（db.table），拆成 `db`.`table` 避免 LLM 用反引号包整个字符串导致语法错误
            String formattedTableName = physicalTableName.contains(".") ? "`"
                    + physicalTableName.substring(0, physicalTableName.lastIndexOf('.')) + "`.`"
                    + physicalTableName.substring(physicalTableName.lastIndexOf('.') + 1) + "`"
                    : "`" + physicalTableName + "`";
            sb.append("物理表名（FROM 子句中直接使用，不要修改格式）: ").append(formattedTableName).append("\n");
        }

        if (!schema.getDimensions().isEmpty()) {
            sb.append("\n维度字段（可用于 WHERE / SELECT）:\n");
            OnePassSCSqlGenStrategy sqlGenStrategy =
                    ContextUtils.getBean(OnePassSCSqlGenStrategy.class);
            for (SchemaElement dim : schema.getDimensions()) {
                sb.append("  `").append(dim.getBizName()).append("`").append("  -- ")
                        .append(dim.getName());
                // 日期类维度：追加格式说明，不追加维度值
                String dimNameLower = dim.getName().toLowerCase();
                if (StringUtils.isNotEmpty(dim.getTimeFormat())) {
                    sb.append("（日期字段，格式：").append(dim.getTimeFormat()).append("）");
                    sb.append("\n");
                    continue;
                }
                // 含 id 的维度跳过维度值
                boolean isIdDim = dimNameLower.contains("id");
                // 日期/时间类维度跳过维度值
                boolean isTimeDim = dimNameLower.contains("日期") || dimNameLower.contains("时间")
                        || dimNameLower.contains("date") || dimNameLower.contains("time");
                if (isIdDim || isTimeDim) {
                    sb.append("\n");
                    continue;
                }
                // 尝试获取维度值示例
                if (sqlGenStrategy != null && (Boolean.TRUE.equals(dim.isHasDimValues())
                        || !CollectionUtils.isEmpty(dim.getSchemaValueMaps()))) {
                    PageInfo<DictValueDimResp> pageInfo =
                            sqlGenStrategy.getDimensionValuesFromDict(dim);
                    if (pageInfo != null && !CollectionUtils.isEmpty(pageInfo.getList())) {
                        // 先拿到全量维度值，用于判断是否含“全国”/“全省”
                        List<String> allValues =
                                pageInfo.getList().stream().map(DictValueDimResp::getValue)
                                        .collect(java.util.stream.Collectors.toList());
                        if (!allValues.isEmpty()) {
                            boolean isProvinceDim = dimNameLower.contains("省份")
                                    || dimNameLower.contains("province");
                            boolean isCityDim = dimNameLower.contains("城市")
                                    || dimNameLower.contains("地市") || dimNameLower.contains("city");
                            if (isProvinceDim && allValues.contains("全国")) {
                                List<String> samples =
                                        allValues.stream().filter(v -> !"全国".equals(v)).limit(3)
                                                .collect(java.util.stream.Collectors.toList());
                                sb.append("，含'全国'");
                                if (!samples.isEmpty()) {
                                    sb.append("和'").append(String.join("'、'", samples))
                                            .append("'等省份数据，全国数据不需要用各省累加");
                                }
                            } else if (isCityDim && allValues.contains("全省")) {
                                List<String> samples =
                                        allValues.stream().filter(v -> !"全省".equals(v)).limit(3)
                                                .collect(java.util.stream.Collectors.toList());
                                sb.append("，含'全省'");
                                if (!samples.isEmpty()) {
                                    sb.append("和'").append(String.join("'、'", samples))
                                            .append("'等城市数据，全省数据不需要用各城市累加");
                                }
                            } else {
                                // 普通维度：取前 10 个作为示例
                                List<String> dimValues = allValues.stream().limit(10)
                                        .collect(java.util.stream.Collectors.toList());
                                sb.append("，维度值示例：").append(String.join("、", dimValues));
                            }
                        }
                    }
                }
                sb.append("\n");
            }
        }

        if (!schema.getMetrics().isEmpty()) {
            sb.append("\n指标字段（可用于 SELECT，禁止聚合）:\n");
            for (SchemaElement metric : schema.getMetrics()) {
                sb.append("  `").append(metric.getBizName()).append("`").append("  -- ")
                        .append(metric.getName()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 从 Schema 中解析出 modelId（三级回退：dataSet → dimensions → metrics → dimensionValues）。
     */
    private Long resolveModelId(DataSetSchema schema) {
        Long modelId = schema.getDataSet().getModel();
        if (modelId == null) {
            modelId = schema.getDimensions().stream().filter(e -> e.getModel() != null)
                    .map(SchemaElement::getModel).findFirst().orElse(null);
        }
        if (modelId == null) {
            modelId = schema.getMetrics().stream().filter(e -> e.getModel() != null)
                    .map(SchemaElement::getModel).findFirst().orElse(null);
        }
        if (modelId == null) {
            modelId = schema.getDimensionValues().stream().filter(e -> e.getModel() != null)
                    .map(SchemaElement::getModel).findFirst().orElse(null);
        }
        if (modelId == null) {
            log.warn("[SUPER_SIMPLE] 无法从 Schema 中找到 modelId");
        }
        return modelId;
    }

    /**
     * 根据 modelId 获取物理表名（tableQuery 字段）。 直接返回原始 tableQuery（含 db.table 格式），由 JdbcExecutor 通过
     * ontology.getDatabase() 正确定位数据库。
     */
    private String resolvePhysicalTableName(Long modelId) {
        if (modelId == null) {
            return null;
        }
        try {
            SchemaService schemaService = ContextUtils.getBean(SchemaService.class);
            List<ModelResp> models = schemaService.getModelList(List.of(modelId));
            if (models != null && !models.isEmpty()) {
                String tableQuery = models.get(0).getModelDetail() != null
                        ? models.get(0).getModelDetail().getTableQuery()
                        : null;
                if (StringUtils.isNotBlank(tableQuery)) {
                    log.info("[SUPER_SIMPLE] 获取到物理表名: {}", tableQuery);
                    return tableQuery;
                }
            }
        } catch (Exception e) {
            log.warn("[SUPER_SIMPLE] 获取物理表名失败，将跳过", e);
        }
        return null;
    }

    /**
     * 读取提示词模板文件。 优先从 {user.dir}/conf/super_simple_prompt.txt 读取（支持线上热更）， 回退到 classpath 内置文件。
     */
    private String readPromptTemplate() throws Exception {
        // 优先从外部 conf 目录读（线上热更）
        String externalPath = System.getProperty("user.dir") + "/conf/" + PROMPT_FILE_NAME;
        java.io.File externalFile = new java.io.File(externalPath);
        if (externalFile.exists()) {
            log.info("[SUPER_SIMPLE] 从外部文件加载提示词: {}", externalPath);
            return new String(Files.readAllBytes(Paths.get(externalPath)), StandardCharsets.UTF_8);
        }
        // 回退 classpath
        ClassPathResource resource = new ClassPathResource(PROMPT_FILE_NAME);
        if (!resource.exists()) {
            throw new RuntimeException("提示词文件不存在: " + PROMPT_FILE_NAME);
        }
        try (InputStream is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 清理 LLM 输出中可能包含的 markdown 代码块标记。
     */
    private String cleanSql(String sql) {
        sql = sql.trim();
        if (sql.startsWith("```")) {
            // 去掉第一行（```sql 或 ```）
            int firstNewline = sql.indexOf('\n');
            if (firstNewline != -1) {
                sql = sql.substring(firstNewline + 1);
            }
        }
        if (sql.endsWith("```")) {
            sql = sql.substring(0, sql.lastIndexOf("```")).trim();
        }
        // 去掉结尾分号
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        return sql;
    }

    private QueryResult errorResult(String msg) {
        log.warn("[SUPER_SIMPLE] {}", msg);
        QueryResult result = new QueryResult();
        result.setQueryState(QueryState.INVALID);
        result.setErrorMsg(msg);
        return result;
    }

    /**
     * LLM 接口定义，直接返回 SQL 字符串（不需要结构化解析）。
     */
    interface SqlExtractor {
        @UserMessage("{{it}}")
        String generateSql(String prompt);
    }
}
