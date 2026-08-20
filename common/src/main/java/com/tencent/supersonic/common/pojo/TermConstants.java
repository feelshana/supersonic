package com.tencent.supersonic.common.pojo;

/**
 * 特殊术语名常量。
 *
 * <p>
 * 这类术语不是给用户看的业务名词，而是被后端当作"数据集级配置"来读取的：术语的 {@code name} 作为配置项标识，{@code description} 存放 JSON 格式的配置内容。
 * 借用术语表来存配置，好处是运营可以在页面上自助维护，不需要改代码或加表。
 *
 * <p>
 * 常量集中放在 common 模块，是因为使用方跨越多个模块（headless-chat 的 corrector/mapper、chat-server 的 parser/service），
 * 只有共同依赖的 common 才能被它们同时引用。
 */
public class TermConstants {

    private TermConstants() {}

    /**
     * 维度默认值配置。
     *
     * <p>
     * description 为 JSON Map：{@code {"bizName": "默认值", ...}}，例如
     * {@code {"province_name":"全国","city_name":"全省"}}。
     *
     * <p>
     * 用于给未在维度表配置 {@code default_dim_value} 的维度补默认值。当用户问题未涉及该维度时补 {@code dim = '默认值'}；当用户要按该维度拆分时补
     * {@code dim != '默认值'} 以排除汇总行。
     */
    public static final String DEFAULT_DIM_VALUE_CONFIG = "默认值配置";

    /**
     * 必须查询的字段。
     *
     * <p>
     * description 为 JSON 数组，元素是维度中文名，例如 {@code ["产品名称","省份","地市","日期"]}。
     *
     * <p>
     * 用于缓解大模型漏选字段的问题：明细查询缺失这些字段时由代码补进 SELECT。含聚合函数 / GROUP BY / DISTINCT 的查询会跳过补全，避免破坏 SQL 语义。
     */
    public static final String MANDATORY_SELECT_FIELDS = "必须查询的字段";

    /**
     * 无需排除 id 的维度。
     *
     * <p>
     * description 为 JSON Map：{@code {"维度名": limit条数, ...}}，limit 取值范围 1~50，超出按 50 截断。
     *
     * <p>
     * 默认情况下名称含 "id" 的维度会被排除（避免把主键当业务维度用），配置在此的维度即使名称含 "id" 也保留。
     */
    public static final String NO_EXCLUDE_ID_DIMENSIONS = "无需排除id的维度";
}
