package com.tencent.supersonic.chat.server.parser.llmnative;

import com.tencent.supersonic.common.pojo.BiReportConfigDO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 默认值注入规则验证。用户给出的业务场景： 报表有产品名称（默认"全部"）、省份（默认"全国"）、地市（默认"全省"）三个维度。
 *
 * <ul>
 * <li>问"各省数据" → 产品名称='全部'、省份!='全国'、地市='全省'</li>
 * <li>问"视频产品的数据" → 产品名称保持用户筛选、省份='全国'、地市='全省'</li>
 * </ul>
 */
public class DefaultDimValueInjectorTest {

    private static final String TABLE = "`db`.`report_tbl`";

    private LlmNativeContext buildContext() {
        LlmNativeContext context = new LlmNativeContext();
        context.getDimensions().add(dim("产品名称", "product_name", "全部"));
        context.getDimensions().add(dim("省份", "province_name", "全国"));
        context.getDimensions().add(dim("地市", "city_name", "全省"));
        context.getDimensions().add(dim("日期", "dt", null));
        return context;
    }

    private LlmNativeContext.DimMeta dim(String name, String bizName, String defaultValue) {
        LlmNativeContext.DimMeta meta = new LlmNativeContext.DimMeta();
        meta.setName(name);
        meta.setBizName(bizName);
        meta.setSqlFragment(bizName);
        meta.setCustom(false);
        meta.setDefaultValues(
                defaultValue == null ? new ArrayList<>() : Collections.singletonList(defaultValue));
        return meta;
    }

    @Test
    public void testGroupByDimExcludesDefaultValue() {
        // 用户问"各省的播放量"：省份进 GROUP BY，应排除汇总行"全国"
        String sql = "SELECT province_name, SUM(vv) AS `播放量` FROM " + TABLE
                + " WHERE dt = '20260801' GROUP BY province_name";
        String result = DefaultDimValueInjector.inject(sql, buildContext());

        Assertions.assertTrue(containsCond(result, "province_name", "!=", "全国"),
                "省份进GROUP BY应生成 != 全国");
        Assertions.assertTrue(containsCond(result, "product_name", "=", "全部"), "产品名称未出现应补默认值全部");
        Assertions.assertTrue(containsCond(result, "city_name", "=", "全省"), "地市未出现应补默认值全省");
        Assertions.assertFalse(containsCond(result, "province_name", "=", "全国"), "省份不应同时出现 = 全国");
    }

    @Test
    public void testUserFilteredDimKeepsUserValue() {
        // 用户问"视频产品的数据"：产品名称已被用户筛选，不注入；省份/地市补默认值
        String sql = "SELECT SUM(vv) AS `播放量` FROM " + TABLE
                + " WHERE dt = '20260801' AND product_name = '视频产品'";
        String result = DefaultDimValueInjector.inject(sql, buildContext());

        Assertions.assertTrue(containsCond(result, "product_name", "=", "视频产品"), "用户筛选值应保留");
        Assertions.assertFalse(containsCond(result, "product_name", "=", "全部"), "产品名称已被筛选，不应注入默认值");
        Assertions.assertTrue(containsCond(result, "province_name", "=", "全国"), "省份应补默认值全国");
        Assertions.assertTrue(containsCond(result, "city_name", "=", "全省"), "地市应补默认值全省");
    }

    @Test
    public void testNoDimensionMentionedAppliesAllDefaults() {
        String sql = "SELECT SUM(vv) AS `播放量` FROM " + TABLE + " WHERE dt = '20260801'";
        String result = DefaultDimValueInjector.inject(sql, buildContext());

        Assertions.assertTrue(containsCond(result, "product_name", "=", "全部"));
        Assertions.assertTrue(containsCond(result, "province_name", "=", "全国"));
        Assertions.assertTrue(containsCond(result, "city_name", "=", "全省"));
    }

    @Test
    public void testHierarchyRelationSkipsParentDim() {
        // 省份/地市配为层级关系，用户按地市拆分时应跳过省份的默认值
        LlmNativeContext context = buildContext();
        context.setDimensionRelations(relations(1, "province_name/city_name"));

        String sql = "SELECT city_name, SUM(vv) AS `播放量` FROM " + TABLE
                + " WHERE dt = '20260801' GROUP BY city_name";
        String result = DefaultDimValueInjector.inject(sql, context);

        Assertions.assertTrue(containsCond(result, "city_name", "!=", "全省"),
                "地市进GROUP BY应生成 != 全省");
        Assertions.assertFalse(containsCond(result, "province_name", "=", "全国"),
                "下级地市已使用，省份不应补默认值");
    }

    @Test
    public void testSiblingRelationSkipsPeerDim() {
        LlmNativeContext context = buildContext();
        context.setDimensionRelations(relations(2, "product_name/province_name"));

        String sql = "SELECT product_name, SUM(vv) AS `播放量` FROM " + TABLE
                + " WHERE dt = '20260801' GROUP BY product_name";
        String result = DefaultDimValueInjector.inject(sql, context);

        Assertions.assertTrue(containsCond(result, "product_name", "!=", "全部"));
        Assertions.assertFalse(containsCond(result, "province_name", "=", "全国"),
                "同级维度已使用，省份不应补默认值");
    }

    @Test
    public void testProvinceCityRelationSkipsProvince() {
        // 未配关系时，靠内置省市口径：WHERE 含 city_name 则跳过省份默认值
        LlmNativeContext context = buildContext();
        String sql = "SELECT SUM(vv) AS `播放量` FROM " + TABLE
                + " WHERE dt = '20260801' AND city_name = '深圳'";
        String result = DefaultDimValueInjector.inject(sql, context);

        Assertions.assertFalse(containsCond(result, "province_name", "=", "全国"),
                "WHERE含城市时省份不应补默认值");
    }

    @Test
    public void testOrConditionPreserved() {
        // 原 WHERE 含 OR 时，追加条件必须加括号保住优先级
        String sql = "SELECT SUM(vv) AS `播放量` FROM " + TABLE
                + " WHERE dt = '20260801' OR dt = '20260802'";
        String result = DefaultDimValueInjector.inject(sql, buildContext());

        Assertions.assertTrue(result.replace(" ", "").contains("(dt='20260801'ORdt='20260802')"),
                "原OR条件应被括号包裹");
    }

    @Test
    public void testNoDefaultValuesReturnsOriginal() {
        LlmNativeContext context = new LlmNativeContext();
        context.getDimensions().add(dim("日期", "dt", null));
        String sql = "SELECT SUM(vv) FROM " + TABLE + " WHERE dt = '20260801'";
        Assertions.assertEquals(sql, DefaultDimValueInjector.inject(sql, context));
    }

    @Test
    public void testBreakdownDimSignalExcludesDefaultWithoutGroupBy() {
        // 复现线上 bug：结果表"各省的在订用户数"，省份在 SELECT 但无 GROUP BY。
        // 靠 LLM 报告的分布维度信号识别，省份应 != 全国，其余维度补默认值。
        LlmNativeContext context = buildContext();
        context.getExcludeDefaultDimBizNames().add("province_name");
        String sql = "SELECT province_name, order_uv AS `在订用户数` FROM " + TABLE
                + " WHERE dt = '20260801'";
        String result = DefaultDimValueInjector.inject(sql, context);

        Assertions.assertTrue(containsCond(result, "province_name", "!=", "全国"), "分布维度省份应生成 != 全国");
        Assertions.assertTrue(containsCond(result, "city_name", "=", "全省"), "非分布维度地市补默认值");
        Assertions.assertTrue(containsCond(result, "product_name", "=", "全部"), "非分布维度产品补默认值");
        Assertions.assertFalse(containsCond(result, "province_name", "=", "全国"), "省份不应 = 全国");
    }

    @Test
    public void testOverSelectedDimNotTreatedAsBreakdown() {
        // 用户只问"各省"，但 LLM 把地市也放进了 SELECT（过度查询）。
        // 地市不是分布维度，应正常 = 全省，而不是被误判为拆分维度加 != 全省。
        LlmNativeContext context = buildContext();
        context.getExcludeDefaultDimBizNames().add("province_name");
        String sql = "SELECT province_name, city_name, order_uv FROM " + TABLE
                + " WHERE dt = '20260801'";
        String result = DefaultDimValueInjector.inject(sql, context);

        Assertions.assertTrue(containsCond(result, "province_name", "!=", "全国"), "省份是分布维度 → != 全国");
        Assertions.assertTrue(containsCond(result, "city_name", "=", "全省"),
                "地市在SELECT但非分布维度 → = 全省");
        Assertions.assertFalse(containsCond(result, "city_name", "!=", "全省"), "地市不应被误判为拆分");
    }

    private List<BiReportConfigDO> relations(int type, String dimRelation) {
        BiReportConfigDO configDO = new BiReportConfigDO();
        configDO.setType(type);
        configDO.setDimRelation(dimRelation);
        return Arrays.asList(configDO);
    }

    /** 忽略空格差异地判断 SQL 中是否包含某个条件。 */
    private boolean containsCond(String sql, String field, String operator, String value) {
        String normalized = sql.replace(" ", "").replace("`", "");
        return normalized.contains(field + operator + "'" + value + "'");
    }
}
