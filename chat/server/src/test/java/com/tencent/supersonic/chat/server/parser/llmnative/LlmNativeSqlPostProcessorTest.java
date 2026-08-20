package com.tencent.supersonic.chat.server.parser.llmnative;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

/** 后处理链验证：markdown 清理、字段白名单、LIMIT 兜底、SQL 建模占位表替换。 */
public class LlmNativeSqlPostProcessorTest {

    private LlmNativeContext dragContext() {
        LlmNativeContext context = new LlmNativeContext();
        context.setDataSetId(1L);
        context.setModelId(1L);
        context.setFromClause("`db`.`report_tbl`");
        context.getAllowedFields().addAll(Arrays.asList("dt", "province_name", "vv"));
        return context;
    }

    @Test
    public void testCleanMarkdownAndSemicolon() {
        String raw =
                "```sql\nSELECT vv FROM `db`.`report_tbl` WHERE dt = '20260801' LIMIT 10;\n```";
        String result = LlmNativeSqlPostProcessor.process(raw, dragContext());

        Assertions.assertFalse(result.contains("```"), "markdown 标记应被清理");
        Assertions.assertFalse(result.trim().endsWith(";"), "结尾分号应被清理");
    }

    @Test
    public void testLimitAppendedWhenMissing() {
        String raw = "SELECT vv FROM `db`.`report_tbl` WHERE dt = '20260801'";
        String result = LlmNativeSqlPostProcessor.process(raw, dragContext());

        Assertions.assertTrue(result.toUpperCase().contains("LIMIT 100"), "缺失 LIMIT 应补 100");
    }

    @Test
    public void testExistingLimitKept() {
        String raw = "SELECT vv FROM `db`.`report_tbl` WHERE dt = '20260801' LIMIT 5";
        String result = LlmNativeSqlPostProcessor.process(raw, dragContext());

        Assertions.assertTrue(result.contains("LIMIT 5"), "已有 LIMIT 应保留");
        Assertions.assertFalse(result.contains("LIMIT 100"), "不应重复追加 LIMIT");
    }

    @Test
    public void testUnknownFieldRejected() {
        String raw = "SELECT not_exist_col FROM `db`.`report_tbl` WHERE dt = '20260801' LIMIT 10";
        IllegalStateException ex = Assertions.assertThrows(IllegalStateException.class,
                () -> LlmNativeSqlPostProcessor.process(raw, dragContext()));

        Assertions.assertTrue(ex.getMessage().contains("not_exist_col"), "错误信息应指出未定义字段");
    }

    @Test
    public void testChineseAliasNotTreatedAsField() {
        String raw = "SELECT SUM(vv) AS `播放量` FROM `db`.`report_tbl` "
                + "WHERE dt = '20260801' LIMIT 10";
        String result = LlmNativeSqlPostProcessor.process(raw, dragContext());

        Assertions.assertTrue(result.contains("播放量"), "中文别名应保留且不被当作未定义字段");
    }

    @Test
    public void testSyntaxErrorRejected() {
        String raw = "SELECT FROM WHERE dt";
        Assertions.assertThrows(IllegalStateException.class,
                () -> LlmNativeSqlPostProcessor.process(raw, dragContext()));
    }

    @Test
    public void testFilterSqlMerged() {
        LlmNativeContext context = dragContext();
        context.setFilterSql("dt >= '20260101'");
        String raw = "SELECT vv FROM `db`.`report_tbl` WHERE dt = '20260801' LIMIT 10";
        String result = LlmNativeSqlPostProcessor.process(raw, context);

        Assertions.assertTrue(result.replace(" ", "").contains("dt>='20260101'"), "模型固定过滤条件应合并");
    }

    @Test
    public void testBaseQueryPlaceholderReplaced() {
        LlmNativeContext context = new LlmNativeContext();
        context.setDataSetId(2L);
        context.setModelId(2L);
        context.setFromClause(LlmNativeContext.BASE_QUERY_ALIAS);
        context.setBaseQuerySql("SELECT a.dt, a.vv, b.province_name FROM raw_a a "
                + "LEFT JOIN dim_b b ON a.pid = b.id WHERE a.status = 1");
        context.getAllowedFields().addAll(Arrays.asList("dt", "vv", "province_name"));

        String raw = "SELECT province_name, SUM(vv) AS `播放量` FROM base_query "
                + "WHERE dt = '20260801' GROUP BY province_name LIMIT 10";
        String result = LlmNativeSqlPostProcessor.process(raw, context);

        Assertions.assertTrue(result.contains("LEFT JOIN dim_b"), "建模SQL应被内联为子查询");
        Assertions.assertTrue(result.contains("AS base_query") || result.contains("base_query"),
                "子查询应带占位别名");
        Assertions.assertTrue(result.contains("GROUP BY"), "外层 GROUP BY 应保留");
        // 建模SQL自身的 WHERE 不能被外层条件污染
        Assertions.assertTrue(result.replace(" ", "").contains("a.status=1"), "建模SQL原有条件应保留");
    }

    @Test
    public void testBaseQueryWrongFromRejected() {
        LlmNativeContext context = new LlmNativeContext();
        context.setFromClause(LlmNativeContext.BASE_QUERY_ALIAS);
        context.setBaseQuerySql("SELECT dt, vv FROM raw_a");
        context.getAllowedFields().addAll(Arrays.asList("dt", "vv"));

        // LLM 没按要求用占位表名，应该报错触发重试而不是静默生成错 SQL
        String raw = "SELECT vv FROM some_other_table WHERE dt = '20260801' LIMIT 10";
        Assertions.assertThrows(IllegalStateException.class,
                () -> LlmNativeSqlPostProcessor.process(raw, context));
    }

    /** 构造带必查字段的上下文：产品名称(product_name)、日期(dt) 为必查维度。 */
    private LlmNativeContext contextWithMandatory() {
        LlmNativeContext context = dragContext();
        context.getAllowedFields().add("product_name");
        context.getMandatorySelectDims().add(dim("产品名称", "product_name"));
        context.getMandatorySelectDims().add(dim("日期", "dt"));
        return context;
    }

    private LlmNativeContext.DimMeta dim(String name, String bizName) {
        LlmNativeContext.DimMeta meta = new LlmNativeContext.DimMeta();
        meta.setName(name);
        meta.setBizName(bizName);
        meta.setSqlFragment(bizName);
        meta.setCustom(false);
        return meta;
    }

    @Test
    public void testMandatoryFieldAppendedInDetailQuery() {
        // 明细查询且缺失必查字段 product_name，应补入并带中文别名
        String raw = "SELECT dt, vv FROM `db`.`report_tbl` WHERE dt = '20260801' LIMIT 10";
        String result = LlmNativeSqlPostProcessor.process(raw, contextWithMandatory());

        Assertions.assertTrue(result.contains("product_name"), "缺失的必查字段应被补入");
        Assertions.assertTrue(result.contains("产品名称"), "补入的必查字段应带中文别名");
    }

    @Test
    public void testMandatoryFieldNotDuplicatedWhenPresent() {
        // dt 已在 SELECT 中，不应重复补入
        String raw = "SELECT dt, product_name, vv FROM `db`.`report_tbl` "
                + "WHERE dt = '20260801' LIMIT 10";
        String result = LlmNativeSqlPostProcessor.process(raw, contextWithMandatory());

        // dt 出现在 SELECT、WHERE 各一次，补全不应再增加
        Assertions.assertEquals(2, countOccurrences(result, "dt"), "已存在的必查字段不应重复补入");
    }

    @Test
    public void testMandatoryFieldSkippedWhenAggregate() {
        // 含聚合函数（单行汇总），跳过补全，避免非法 SQL
        String raw = "SELECT SUM(vv) AS `播放量` FROM `db`.`report_tbl` "
                + "WHERE dt = '20260801' LIMIT 10";
        String result = LlmNativeSqlPostProcessor.process(raw, contextWithMandatory());

        Assertions.assertFalse(result.contains("product_name"), "聚合查询不应补入必查字段");
    }

    @Test
    public void testMandatoryFieldSkippedWhenGroupBy() {
        // 含 GROUP BY，跳过补全（补裸字段需进 GROUP BY）
        String raw = "SELECT province_name, SUM(vv) AS `播放量` FROM `db`.`report_tbl` "
                + "WHERE dt = '20260801' GROUP BY province_name LIMIT 10";
        String result = LlmNativeSqlPostProcessor.process(raw, contextWithMandatory());

        Assertions.assertFalse(result.contains("product_name"), "GROUP BY 查询不应补入必查字段");
    }

    private int countOccurrences(String text, String token) {
        return text.split(token, -1).length - 1;
    }
}
