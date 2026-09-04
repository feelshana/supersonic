package com.tencent.supersonic.chat.server.parser.llmnative;

import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.common.pojo.BiReportConfigDO;
import com.tencent.supersonic.common.pojo.TermConstants;
import com.tencent.supersonic.common.pojo.enums.EngineType;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.api.pojo.DataSetSchema;
import com.tencent.supersonic.headless.api.pojo.Dimension;
import com.tencent.supersonic.headless.api.pojo.Measure;
import com.tencent.supersonic.headless.api.pojo.ModelDetail;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.enums.DimensionType;
import com.tencent.supersonic.headless.api.pojo.response.DictValueDimResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.chat.parser.llm.OnePassSCSqlGenStrategy;
import com.tencent.supersonic.headless.core.utils.SqlVariableParseUtils;
import com.tencent.supersonic.headless.server.facade.service.SemanticLayerService;
import com.tencent.supersonic.headless.server.service.BiReportConfigService;
import com.tencent.supersonic.headless.server.service.SchemaService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 构建 LLM_NATIVE 模式的 Schema 上下文。
 *
 * <p>
 * 元数据取自 {@code ModelResp.getModelDetail()} 而非 DataSetSchema，原因是 SchemaElement 不带 expr 字段， 而 BI
 * 拖拽建模的 customs（新增列）把中文名存在 bizName、真实表达式存在 expr， 只有 ModelDetail 里的 Dimension/Measure 才能同时拿到 expr 和
 * agg。
 *
 * <p>
 * DataSetSchema 仍然会读取，用途有两个：术语"默认值配置"，以及维度值示例。
 */
@Slf4j
public class LlmNativeSchemaBuilder {

    private static final int MAX_DIM_VALUE_SAMPLES = 10;

    public static LlmNativeContext build(Long dataSetId, Agent agent) {
        LlmNativeContext context = new LlmNativeContext();
        context.setDataSetId(dataSetId);

        SemanticLayerService semanticLayerService =
                ContextUtils.getBean(SemanticLayerService.class);
        DataSetSchema dataSetSchema = semanticLayerService.getDataSetSchema(dataSetId);
        if (dataSetSchema == null) {
            throw new IllegalStateException("数据集 Schema 获取失败，dataSetId=" + dataSetId);
        }
        context.setDataSetName(
                dataSetSchema.getDataSet() != null ? dataSetSchema.getDataSet().getName() : "");

        Long modelId = resolveModelId(dataSetSchema);
        if (modelId == null) {
            throw new IllegalStateException("无法从数据集解析出 modelId，dataSetId=" + dataSetId);
        }
        context.setModelId(modelId);

        ModelDetail modelDetail = resolveModelDetail(modelId);
        context.setEngineType(resolveEngineType(modelDetail, dataSetSchema));
        context.setFilterSql(modelDetail.getFilterSql());
        context.setTermDefaultValues(findTermDefaultValues(dataSetSchema));
        context.setDimensionRelations(loadDimensionRelations(agent));

        // FROM 子句：拖拽建模用物理表名，SQL 建模用占位符 + 建模SQL原文
        resolveFrom(context, modelDetail);

        StringBuilder sb = new StringBuilder();
        sb.append("数据集名称: ").append(context.getDataSetName()).append("\n");
        if (StringUtils.isNotBlank(context.getBaseQuerySql())) {
            sb.append("\n【数据来源建模SQL】（仅用于理解字段含义与数据范围，不要修改、不要展开到你的SQL里）:\n");
            sb.append(context.getBaseQuerySql()).append("\n");
            sb.append("\nFROM 子句固定写: ").append(LlmNativeContext.BASE_QUERY_ALIAS).append("\n");
        } else {
            sb.append("物理表名（FROM 子句原样使用，不要改格式）: ").append(context.getFromClause()).append("\n");
        }

        appendDimensions(sb, context, modelDetail, dataSetSchema);
        appendMetrics(sb, context, modelDetail);
        appendTerms(sb, dataSetSchema);
        resolveMandatorySelectFields(dataSetSchema, context);
        fillSegmentInputs(dataSetSchema, modelDetail, context);

        context.setSchemaText(sb.toString());
        return context;
    }

    /**
     * 填充分词所需的元数据：全部术语（termInfo）和指标中文名列表。 供 {@link LlmNativeSegmentService} 的提示词使用，对齐原 useLLMSplit 的
     * variable 组装。
     */
    private static void fillSegmentInputs(DataSetSchema dataSetSchema, ModelDetail modelDetail,
            LlmNativeContext context) {
        if (!CollectionUtils.isEmpty(dataSetSchema.getTerms())) {
            Map<String, String> termInfoMap = new LinkedHashMap<>();
            dataSetSchema.getTerms().stream().filter(t -> StringUtils.isNotBlank(t.getName()))
                    .forEach(t -> termInfoMap.put(t.getName(),
                            StringUtils.defaultString(t.getDescription())));
            context.setTermInfoMap(termInfoMap);
        }
        if (!CollectionUtils.isEmpty(modelDetail.getMeasures())) {
            List<String> metricNames = modelDetail.getMeasures().stream()
                    .map(measure -> StringUtils.defaultIfBlank(measure.getName(),
                            measure.getBizName()))
                    .filter(StringUtils::isNotBlank).distinct().collect(Collectors.toList());
            context.setMetricNames(metricNames);
        }
    }

    /**
     * 追加业务术语说明（口径解释/别名等价），帮助 LLM 把用户业务语言映射到正确字段与口径。
     *
     * <p>
     * 过滤口径与 {@code AgentServiceImpl.getAgentDataSetInfo} 一致：排除 alias 含 "rule" 的术语。 规则术语走
     * CUSTOM_RULES 注入；配置类术语（默认值配置/必须查询的字段/无需排除id的维度）的 alias 也配为 rule， 一并被排除，避免配置 JSON
     * 进入提示词、与代码注入冲突。其余纯业务术语以 "name: description" 注入。
     */
    private static void appendTerms(StringBuilder sb, DataSetSchema dataSetSchema) {
        if (CollectionUtils.isEmpty(dataSetSchema.getTerms())) {
            return;
        }
        List<SchemaElement> businessTerms =
                dataSetSchema.getTerms().stream().filter(t -> StringUtils.isNotBlank(t.getName()))
                        .filter(t -> t.getAlias() == null || t.getAlias().stream()
                                .noneMatch(a -> a != null && a.toLowerCase().contains("rule")))
                        .collect(Collectors.toList());
        if (businessTerms.isEmpty()) {
            return;
        }
        sb.append("\n术语说明（业务口径/别名等价，供理解用户问题与字段的对应关系）:\n");
        for (SchemaElement term : businessTerms) {
            sb.append("   - ").append(term.getName());
            if (StringUtils.isNotBlank(term.getDescription())) {
                sb.append(": ").append(term.getDescription());
            }
            sb.append("\n");
        }
    }

    /** 拼接维度字段说明，同时填充 DimMeta 和字段白名单。 */
    private static void appendDimensions(StringBuilder sb, LlmNativeContext context,
            ModelDetail modelDetail, DataSetSchema dataSetSchema) {
        List<Dimension> dimensions = modelDetail.getDimensions();
        if (CollectionUtils.isEmpty(dimensions)) {
            return;
        }
        // 数据集维度按 bizName 建索引，用于取默认值和维度值示例
        Map<String, SchemaElement> bizNameToElement = dataSetSchema.getDimensions().stream()
                .filter(e -> StringUtils.isNotBlank(e.getBizName()))
                .collect(Collectors.toMap(SchemaElement::getBizName, e -> e, (a, b) -> a));

        sb.append("\n维度字段（可用于 SELECT / WHERE / GROUP BY）:\n");
        for (Dimension dim : dimensions) {
            String bizName = dim.getBizName();
            if (StringUtils.isBlank(bizName)) {
                continue;
            }
            boolean custom = isExpression(dim.getExpr(), bizName);
            String sqlFragment = custom ? dim.getExpr() : bizName;
            String displayName = StringUtils.defaultIfBlank(dim.getName(), bizName);
            boolean timeDim = DimensionType.time.equals(dim.getType())
                    || DimensionType.partition_time.equals(dim.getType());

            LlmNativeContext.DimMeta meta = new LlmNativeContext.DimMeta();
            meta.setName(displayName);
            meta.setBizName(bizName);
            meta.setSqlFragment(sqlFragment);
            meta.setCustom(custom);
            meta.setTimeDim(timeDim);

            SchemaElement element = bizNameToElement.get(bizName);
            meta.setDefaultValues(resolveDefaultValues(bizName, element, context));
            context.getDimensions().add(meta);

            if (!custom) {
                context.getAllowedFields().add(normalizeField(bizName));
            }

            sb.append("  ").append(sqlFragment).append("  -- ").append(displayName);
            if (timeDim) {
                String format = StringUtils.defaultIfBlank(dim.getDateFormat(), "yyyyMMdd");
                sb.append("（日期字段，格式 ").append(format).append("，WHERE 条件需加单引号）");
            } else {
                appendDimValueSamples(sb, element);
            }
            sb.append("\n");
        }
    }

    /**
     * 拼接指标字段说明。直接使用 BI 存储的原始字段/表达式，**不读** aggregationType。
     *
     * <p>
     * 不用 aggregationType 的原因：
     * <ul>
     * <li>用它去包 {@code SUM(bizName)} 属于代码做语义推断，正是本模式要摆脱的。</li>
     * <li>它不可信：结果表（adm_ 层）的 UV 类指标 BI 也可能传 SUM，而 UV 不可加，包上去既冗余又会连带触发 GROUP BY。</li>
     * <li>{@code columnName}（→ expr/bizName）才是可信源：它就是 BI 自己生成报表 SQL 时用的东西。</li>
     * <li>BI 需要聚合时会自己写进表达式（如 {@code sum(shtvd_vvuv)}、比率型的 CASE WHEN sum(a)/sum(b)），
     * 因此“没写聚合”就等于“不需要聚合”，这个契约在明细表和结果表上都成立。</li>
     * </ul>
     *
     * <p>
     * SELECT 里到底要不要跟 GROUP BY，由提示词根据“表达式里实际有没有聚合函数”判定， LLM 能直接从 {@code vvuv} / {@code sum(vvuv)}
     * 的字面看出来。
     */
    private static void appendMetrics(StringBuilder sb, LlmNativeContext context,
            ModelDetail modelDetail) {
        List<Measure> measures = modelDetail.getMeasures();
        if (CollectionUtils.isEmpty(measures)) {
            return;
        }
        sb.append("\n指标字段（下方写法已是完整语义，原样放入 SELECT，不要自行增加或删除聚合函数）:\n");
        for (Measure measure : measures) {
            String bizName = measure.getBizName();
            if (StringUtils.isBlank(bizName)) {
                continue;
            }
            boolean custom = isExpression(measure.getExpr(), bizName);
            String displayName = StringUtils.defaultIfBlank(measure.getName(), bizName);
            // customs 指标用 BI 传入的原始表达式，普通指标用裸物理字段，两者都不再加工
            String sqlFragment = custom ? measure.getExpr() : bizName;

            if (!custom) {
                context.getAllowedFields().add(normalizeField(bizName));
            }

            sb.append("  ").append(sqlFragment).append("  -- ").append(displayName);
            sb.append("\n");
        }
    }

    /** 维度值示例：为 LLM 提供 WHERE 取值参考，含"全国/全省"汇总值时特别提示。 */
    private static void appendDimValueSamples(StringBuilder sb, SchemaElement element) {
        if (element == null) {
            return;
        }
        boolean hasValues = Boolean.TRUE.equals(element.isHasDimValues())
                || !CollectionUtils.isEmpty(element.getSchemaValueMaps());
        if (!hasValues) {
            return;
        }
        try {
            OnePassSCSqlGenStrategy sqlGenStrategy =
                    ContextUtils.getBean(OnePassSCSqlGenStrategy.class);
            if (sqlGenStrategy == null) {
                return;
            }
            PageInfo<DictValueDimResp> pageInfo =
                    sqlGenStrategy.getDimensionValuesFromDict(element);
            if (pageInfo == null || CollectionUtils.isEmpty(pageInfo.getList())) {
                return;
            }
            List<String> values = pageInfo.getList().stream().map(DictValueDimResp::getValue)
                    .filter(StringUtils::isNotBlank).distinct().collect(Collectors.toList());
            if (values.isEmpty()) {
                return;
            }
            List<String> samples =
                    values.stream().limit(MAX_DIM_VALUE_SAMPLES).collect(Collectors.toList());
            sb.append("，维度值示例：").append(String.join("、", samples));
        } catch (Exception e) {
            log.warn("[LLM_NATIVE] 获取维度值示例失败，dim={}", element.getBizName(), e);
        }
    }

    /**
     * 默认值来源有两处，优先级：维度表 default_dim_value > 术语"默认值配置"。 与 TermBasedDefaultDimValueCorrector 中
     * alreadyConfiguredBizNames 的跳过逻辑保持一致。
     */
    private static List<String> resolveDefaultValues(String bizName, SchemaElement element,
            LlmNativeContext context) {
        if (element != null && !CollectionUtils.isEmpty(element.getDefaultValues())) {
            return new ArrayList<>(element.getDefaultValues());
        }
        String termDefault = context.getTermDefaultValues().get(bizName);
        if (StringUtils.isNotBlank(termDefault)) {
            return Collections.singletonList(termDefault);
        }
        return new ArrayList<>();
    }

    /** 解析 FROM 子句：拖拽建模用物理表名，SQL 建模用占位符并保留建模 SQL 原文。 */
    private static void resolveFrom(LlmNativeContext context, ModelDetail modelDetail) {
        String tableQuery = modelDetail.getTableQuery();
        if (StringUtils.isNotBlank(tableQuery)) {
            context.setFromClause(formatTableName(tableQuery));
            return;
        }
        String sqlQuery = modelDetail.getSqlQuery();
        if (StringUtils.isBlank(sqlQuery)) {
            throw new IllegalStateException(
                    "模型既无 tableQuery 也无 sqlQuery，modelId=" + context.getModelId());
        }
        // SQL 建模可能带变量占位符，先解析再交给 LLM
        if (!CollectionUtils.isEmpty(modelDetail.getSqlVariables())) {
            try {
                sqlQuery = SqlVariableParseUtils.parse(sqlQuery, modelDetail.getSqlVariables(),
                        new ArrayList<>());
            } catch (Exception e) {
                log.warn("[LLM_NATIVE] 解析 sqlVariables 失败，使用原始 sqlQuery", e);
            }
        }
        context.setBaseQuerySql(sqlQuery);
        context.setFromClause(LlmNativeContext.BASE_QUERY_ALIAS);
    }

    /** 把 {@code db.table} 拆成 {@code `db`.`table`}，避免 LLM 用反引号包整个字符串。 */
    private static String formatTableName(String tableQuery) {
        String trimmed = tableQuery.trim();
        int idx = trimmed.lastIndexOf('.');
        if (idx <= 0 || idx == trimmed.length() - 1) {
            return "`" + trimmed + "`";
        }
        return "`" + trimmed.substring(0, idx) + "`.`" + trimmed.substring(idx + 1) + "`";
    }

    private static ModelDetail resolveModelDetail(Long modelId) {
        SchemaService schemaService = ContextUtils.getBean(SchemaService.class);
        List<ModelResp> models = schemaService.getModelList(List.of(modelId));
        if (CollectionUtils.isEmpty(models) || models.get(0).getModelDetail() == null) {
            throw new IllegalStateException("模型详情获取失败，modelId=" + modelId);
        }
        return models.get(0).getModelDetail();
    }

    private static EngineType resolveEngineType(ModelDetail modelDetail,
            DataSetSchema dataSetSchema) {
        String dbType = modelDetail.getDbType();
        if (StringUtils.isBlank(dbType)) {
            dbType = dataSetSchema.getDatabaseType();
        }
        if (StringUtils.isBlank(dbType)) {
            return EngineType.MYSQL;
        }
        try {
            return EngineType.fromString(dbType);
        } catch (Exception e) {
            log.warn("[LLM_NATIVE] 未识别的数据库类型 {}，回退 MYSQL", dbType);
            return EngineType.MYSQL;
        }
    }

    /** 维度联动关系只对 BI 报表 Agent 有效，reportId 即 BI 的报表标识。 */
    private static List<BiReportConfigDO> loadDimensionRelations(Agent agent) {
        if (agent == null || !Objects.equals(agent.getIsBi(), 1)
                || StringUtils.isBlank(agent.getReportId())) {
            return new ArrayList<>();
        }
        try {
            BiReportConfigService biReportConfigService =
                    ContextUtils.getBean(BiReportConfigService.class);
            List<BiReportConfigDO> relations =
                    biReportConfigService.getBiReportConfig(agent.getReportId());
            return CollectionUtils.isEmpty(relations) ? new ArrayList<>() : relations;
        } catch (Exception e) {
            log.warn("[LLM_NATIVE] 获取维度联动关系失败，reportId={}", agent.getReportId(), e);
            return new ArrayList<>();
        }
    }

    /** 读取术语"默认值配置"，其 description 是 bizName -> defaultValue 的 JSON。 */
    private static Map<String, String> findTermDefaultValues(DataSetSchema dataSetSchema) {
        if (CollectionUtils.isEmpty(dataSetSchema.getTerms())) {
            return new LinkedHashMap<>();
        }
        SchemaElement configTerm = dataSetSchema.getTerms().stream()
                .filter(t -> TermConstants.DEFAULT_DIM_VALUE_CONFIG.equals(t.getName())).findFirst()
                .orElse(null);
        if (configTerm == null || StringUtils.isBlank(configTerm.getDescription())) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, String> result =
                    JsonUtil.toMap(configTerm.getDescription(), String.class, String.class);
            return result != null ? result : new LinkedHashMap<>();
        } catch (Exception e) {
            log.warn("[LLM_NATIVE] 解析术语 {} 失败: {}", TermConstants.DEFAULT_DIM_VALUE_CONFIG,
                    configTerm.getDescription(), e);
            return new LinkedHashMap<>();
        }
    }

    /**
     * 解析术语“必须查询的字段”（description 为维度中文名的 JSON 数组）， 把每个必查名映射到已构建的 DimMeta（含物理写法 sqlFragment）。 必须在
     * appendDimensions 之后调用，因为依赖 context.getDimensions() 已填充。
     */
    private static void resolveMandatorySelectFields(DataSetSchema dataSetSchema,
            LlmNativeContext context) {
        List<String> mandatoryNames = findMandatoryFieldNames(dataSetSchema);
        if (CollectionUtils.isEmpty(mandatoryNames)) {
            return;
        }
        for (String name : mandatoryNames) {
            LlmNativeContext.DimMeta meta = context.getDimensions().stream()
                    .filter(d -> StringUtils.equalsIgnoreCase(d.getName(), name)
                            || StringUtils.equalsIgnoreCase(d.getBizName(), name))
                    .findFirst().orElse(null);
            if (meta == null) {
                log.warn("[LLM_NATIVE] 必查字段 [{}] 未在维度中找到，跳过", name);
                continue;
            }
            if (!context.getMandatorySelectDims().contains(meta)) {
                context.getMandatorySelectDims().add(meta);
            }
        }
    }

    /** 读取术语“必须查询的字段”，description 是维度中文名的 JSON 数组，保序去重。 */
    private static List<String> findMandatoryFieldNames(DataSetSchema dataSetSchema) {
        if (CollectionUtils.isEmpty(dataSetSchema.getTerms())) {
            return new ArrayList<>();
        }
        SchemaElement configTerm = dataSetSchema.getTerms().stream()
                .filter(t -> TermConstants.MANDATORY_SELECT_FIELDS.equals(t.getName())).findFirst()
                .orElse(null);
        if (configTerm == null || StringUtils.isBlank(configTerm.getDescription())) {
            return new ArrayList<>();
        }
        try {
            List<String> rawList = JsonUtil.toList(configTerm.getDescription(), String.class);
            if (CollectionUtils.isEmpty(rawList)) {
                return new ArrayList<>();
            }
            LinkedHashSet<String> dedup = new LinkedHashSet<>();
            for (String s : rawList) {
                if (StringUtils.isNotBlank(s)) {
                    dedup.add(s.trim());
                }
            }
            return new ArrayList<>(dedup);
        } catch (Exception e) {
            log.warn("[LLM_NATIVE] 解析术语 {} 失败: {}", TermConstants.MANDATORY_SELECT_FIELDS,
                    configTerm.getDescription(), e);
            return new ArrayList<>();
        }
    }

    /** 从 Schema 中解析 modelId，逐级回退。 */
    private static Long resolveModelId(DataSetSchema schema) {
        Long modelId = schema.getDataSet() != null ? schema.getDataSet().getModel() : null;
        if (modelId == null) {
            modelId = firstModelId(schema.getDimensions());
        }
        if (modelId == null) {
            modelId = firstModelId(schema.getMetrics());
        }
        if (modelId == null) {
            modelId = firstModelId(schema.getDimensionValues());
        }
        return modelId;
    }

    private static Long firstModelId(java.util.Collection<SchemaElement> elements) {
        if (CollectionUtils.isEmpty(elements)) {
            return null;
        }
        return elements.stream().filter(e -> e.getModel() != null).map(SchemaElement::getModel)
                .findFirst().orElse(null);
    }

    /**
     * 判断 expr 是否为"表达式"而非裸字段。 普通维度/指标在建模时会把 expr 设为 bizName（见 Dimension/Measure 构造函数）， customs 则显式
     * setExpr(表达式)。
     */
    private static boolean isExpression(String expr, String bizName) {
        return StringUtils.isNotBlank(expr) && !StringUtils.equalsIgnoreCase(expr, bizName);
    }

    public static String normalizeField(String field) {
        if (StringUtils.isBlank(field)) {
            return "";
        }
        return field.replace("`", "").replace("\"", "").trim().toLowerCase();
    }
}
