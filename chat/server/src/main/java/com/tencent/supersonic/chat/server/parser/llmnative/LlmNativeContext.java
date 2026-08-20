package com.tencent.supersonic.chat.server.parser.llmnative;

import com.tencent.supersonic.common.pojo.BiReportConfigDO;
import com.tencent.supersonic.common.pojo.enums.EngineType;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LLM_NATIVE 模式的上下文，由 {@link LlmNativeSchemaBuilder} 构建， 贯穿"提示词渲染 → LLM 生成 → 后处理"整个流程。
 *
 * <p>
 * 与 DataSetSchema 的区别：这里的元数据直接取自 ModelResp.getModelDetail()， 因此能拿到 customs（新增列）的 expr 表达式和 measure
 * 的 agg 聚合方式， 这是 SchemaElement 无法提供的信息。
 */
@Data
public class LlmNativeContext {

    /** SQL 建模场景下，提示词中要求 LLM 使用的占位表名。 */
    public static final String BASE_QUERY_ALIAS = "base_query";

    private Long dataSetId;

    private Long modelId;

    private String dataSetName;

    /** 数据库引擎类型，用于提示词中声明 SQL 方言。 */
    private EngineType engineType;

    /** 给 LLM 的字段描述文本。 */
    private String schemaText;

    /**
     * FROM 子句内容：拖拽建模为格式化后的物理表名（如 {@code `db`.`tbl`}）， SQL 建模为 {@link #BASE_QUERY_ALIAS} 占位符。
     */
    private String fromClause;

    /** SQL 建模的建模 SQL（sqlVariables 已解析）；拖拽建模时为 null。 */
    private String baseQuerySql;

    /** 模型级别的固定过滤条件，非空时需要合并到最终 SQL 的 WHERE。 */
    private String filterSql;

    /** 字段白名单（已去反引号、小写），用于校验 LLM 是否用了不存在的字段。 */
    private Set<String> allowedFields = new HashSet<>();

    /** 维度元信息，供默认值注入使用。 */
    private List<DimMeta> dimensions = new ArrayList<>();

    /**
     * 术语“必须查询的字段”声明的必查维度，元素为 {@link #dimensions} 中的 DimMeta 引用。 仅对明细查询生效（含聚合/GROUP BY/DISTINCT
     * 的查询会跳过补全）。
     */
    private List<DimMeta> mandatorySelectDims = new ArrayList<>();

    /** 维度联动关系配置（type=1 层级，type=2 同级）。 */
    private List<BiReportConfigDO> dimensionRelations = new ArrayList<>();

    /**
     * 分词调用识别出的"分布维度"bizName 集合（已归一化：去反引号、小写）， 语义等价于原链路 MAPPING 阶段
     * {@code chatQueryContext.excludeDefaultDimNames}，是默认值注入判断"该维度排除默认汇总行（!=）"的权威信号。 来源是与 SQL
     * 生成**独立**的一次分词 LLM 调用（{@link LlmNativeSegmentService}），SQL 生成职责保持单一。
     */
    private Set<String> excludeDefaultDimBizNames = new HashSet<>();

    /** 术语"默认值配置"中的 bizName -> defaultValue 映射。 */
    private Map<String, String> termDefaultValues = new HashMap<>();

    /** 全部术语 name -> description，供分词提示词的业务含义部分使用（对齐原分词逻辑的 termInfo）。 */
    private Map<String, String> termInfoMap = new HashMap<>();

    /** 指标中文名列表，供分词提示词的指标列表使用。 */
    private List<String> metricNames = new ArrayList<>();

    /** 维度元信息。 */
    @Data
    public static class DimMeta {

        /** 中文名。 */
        private String name;

        /** 维度 bizName：普通维度为物理字段名，customs 维度为中文名。 */
        private String bizName;

        /** 该维度在 SQL 中的实际写法：普通维度为 bizName，customs 维度为 expr 表达式。 */
        private String sqlFragment;

        /** 是否为 customs（新增列）维度，即 sqlFragment 是表达式而非裸字段。 */
        private boolean custom;

        /** 默认值列表，来源为维度表 default_dim_value 或术语配置。 */
        private List<String> defaultValues = new ArrayList<>();

        /** 是否为时间维度。 */
        private boolean timeDim;
    }
}
