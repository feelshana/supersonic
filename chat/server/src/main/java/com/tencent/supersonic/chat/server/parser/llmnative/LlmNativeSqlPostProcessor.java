package com.tencent.supersonic.chat.server.parser.llmnative;

import com.tencent.supersonic.common.jsqlparser.SqlAddHelper;
import com.tencent.supersonic.common.jsqlparser.SqlSelectHelper;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * LLM 生成物理 SQL 的后处理链。执行顺序有依赖，不要随意调整：
 *
 * <ol>
 * <li>清理 markdown 代码块和结尾分号</li>
 * <li>语法预校验（解析失败直接抛出，交由上层重试）</li>
 * <li>字段白名单校验（用了不存在的字段直接抛出，交由上层重试）</li>
 * <li>必查字段补全（仅明细查询，含聚合/GROUP BY/DISTINCT 跳过）</li>
 * <li>模型固定过滤条件（filterSql）合并</li>
 * <li>默认值注入</li>
 * <li>LIMIT 兜底</li>
 * <li>SQL 建模占位表替换为子查询（必须最后做，替换后 SQL 结构变复杂，前面几步不宜再解析）</li>
 * </ol>
 *
 * <p>
 * 注意：{@code DefaultSemanticTranslator.translate()} 在 {@code isTranslated=true} 时直接返回， 因此
 * ResultLimitOptimizer 等 QueryOptimizer 不会执行，LIMIT 必须在这里补。
 */
@Slf4j
public class LlmNativeSqlPostProcessor {

    private static final int DEFAULT_LIMIT = 100;

    private LlmNativeSqlPostProcessor() {}

    public static String process(String rawSql, LlmNativeContext context) {
        String sql = cleanSql(rawSql);
        if (StringUtils.isBlank(sql)) {
            throw new IllegalStateException("LLM 未生成有效 SQL");
        }
        validateSyntax(sql);
        validateFields(sql, context);
        sql = injectMandatorySelectFields(sql, context);
        sql = mergeFilterSql(sql, context);
        sql = DefaultDimValueInjector.inject(sql, context);
        sql = ensureLimit(sql);
        sql = replaceBaseQuery(sql, context);
        return sql;
    }

    /** 清理 LLM 输出中可能包含的 markdown 代码块标记与结尾分号。 */
    static String cleanSql(String sql) {
        if (StringUtils.isBlank(sql)) {
            return sql;
        }
        String result = sql.trim();
        if (result.startsWith("```")) {
            int firstNewline = result.indexOf('\n');
            if (firstNewline != -1) {
                result = result.substring(firstNewline + 1);
            } else {
                result = result.substring(3);
            }
        }
        if (result.endsWith("```")) {
            result = result.substring(0, result.lastIndexOf("```"));
        }
        result = result.trim();
        while (result.endsWith(";")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result;
    }

    private static void validateSyntax(String sql) {
        try {
            CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            throw new IllegalStateException("生成的SQL语法错误: " + e.getMessage(), e);
        }
    }

    /**
     * 字段白名单校验。Calcite 链路本来会保证字段合法性，本模式绕过了 Calcite， 少了这道校验，用错字段会以"结果不对"而非报错的形式暴露，更难排查。
     *
     * <p>
     * 只校验 SELECT/WHERE/GROUP BY 中出现的字段，别名和 SQL 函数名不参与校验。
     */
    private static void validateFields(String sql, LlmNativeContext context) {
        Set<String> allowed = context.getAllowedFields();
        if (CollectionUtils.isEmpty(allowed)) {
            return;
        }
        Set<String> used = new HashSet<>();
        used.addAll(safeCollect(() -> SqlSelectHelper.getSelectFields(sql)));
        used.addAll(safeCollect(() -> SqlSelectHelper.getWhereFields(sql)));
        used.addAll(safeCollect(() -> SqlSelectHelper.getGroupByFields(sql)));

        // 别名会被当成字段采集到，需要排除
        Set<String> aliases = safeCollectSet(() -> SqlSelectHelper.getAliasFields(sql)).stream()
                .map(LlmNativeSchemaBuilder::normalizeField).collect(Collectors.toSet());

        List<String> unknown = used.stream().map(LlmNativeSchemaBuilder::normalizeField)
                .filter(StringUtils::isNotBlank).distinct().filter(f -> !allowed.contains(f))
                .filter(f -> !aliases.contains(f))
                // customs 维度/指标的表达式内部引用的物理字段未必在白名单里（白名单只收裸字段），
                // 这里再放宽一层：只要该字段名出现在任一 customs 表达式中就认为合法
                .filter(f -> !appearsInCustomExpr(f, context)).collect(Collectors.toList());

        if (!unknown.isEmpty()) {
            throw new IllegalStateException("生成的SQL使用了未定义的字段: " + String.join(", ", unknown));
        }
    }

    private static boolean appearsInCustomExpr(String field, LlmNativeContext context) {
        return context.getDimensions().stream().filter(LlmNativeContext.DimMeta::isCustom)
                .map(LlmNativeContext.DimMeta::getSqlFragment).filter(StringUtils::isNotBlank)
                .anyMatch(expr -> LlmNativeSchemaBuilder.normalizeField(expr).contains(field));
    }

    /** 常见聚合函数名，用于判定“单行汇总”查询。 */
    private static final Set<String> AGGREGATE_FUNCTIONS =
            new HashSet<>(Arrays.asList("sum", "count", "avg", "min", "max", "count_distinct"));

    /**
     * 补全术语“必须查询的字段”声明的必查维度。逻辑对齐 MandatorySelectFieldCorrector： 仅处理单层 SELECT，且跳过 GROUP BY / DISTINCT
     * / SELECT 含聚合函数的查询（那些是汇总查询， 补裸字段会导致非法 SQL 或需进 GROUP BY）。补入时用物理写法 + 中文别名，与其他列保持一致。
     */
    private static String injectMandatorySelectFields(String sql, LlmNativeContext context) {
        List<LlmNativeContext.DimMeta> mandatoryDims = context.getMandatorySelectDims();
        if (CollectionUtils.isEmpty(mandatoryDims)) {
            return sql;
        }
        try {
            Select select = (Select) CCJSqlParserUtil.parse(sql);
            if (!(select instanceof PlainSelect)) {
                log.info("[LLM_NATIVE] 非单层SELECT，跳过必查字段补全");
                return sql;
            }
            PlainSelect plainSelect = (PlainSelect) select;
            if (plainSelect.getGroupBy() != null) {
                log.info("[LLM_NATIVE] SQL含GROUP BY，跳过必查字段补全");
                return sql;
            }
            if (plainSelect.getDistinct() != null) {
                log.info("[LLM_NATIVE] SQL含DISTINCT，跳过必查字段补全");
                return sql;
            }
            if (containsAggregateFunction(plainSelect)) {
                log.info("[LLM_NATIVE] SELECT含聚合函数，跳过必查字段补全");
                return sql;
            }
            Set<String> existingFields = safeCollect(() -> SqlSelectHelper.getSelectFields(sql))
                    .stream().map(LlmNativeSchemaBuilder::normalizeField)
                    .collect(Collectors.toSet());
            String selectText = plainSelect.getSelectItems() == null ? ""
                    : LlmNativeSchemaBuilder
                            .normalizeField(plainSelect.getSelectItems().toString());
            boolean changed = false;
            for (LlmNativeContext.DimMeta dim : mandatoryDims) {
                String fragment = dim.getSqlFragment();
                if (StringUtils.isBlank(fragment)) {
                    continue;
                }
                String normalizedFragment = LlmNativeSchemaBuilder.normalizeField(fragment);
                // 普通字段比列名，customs 表达式退化为文本包含判断
                if (existingFields.contains(normalizedFragment)
                        || selectText.contains(normalizedFragment)) {
                    continue;
                }
                try {
                    Expression expr = CCJSqlParserUtil.parseExpression(fragment);
                    SelectItem<Expression> item = new SelectItem<>(expr);
                    item.setAlias(new Alias("`" + dim.getName() + "`", true));
                    plainSelect.addSelectItems(item);
                    existingFields.add(normalizedFragment);
                    changed = true;
                    log.info("[LLM_NATIVE] 补全必查字段: {} AS {}", fragment, dim.getName());
                } catch (Exception e) {
                    log.warn("[LLM_NATIVE] 补全必查字段失败，跳过: {}", fragment, e);
                }
            }
            return changed ? plainSelect.toString() : sql;
        } catch (Exception e) {
            log.warn("[LLM_NATIVE] 必查字段补全解析失败，跳过", e);
            return sql;
        }
    }

    /** 判断 SELECT 子句是否含聚合函数。 */
    private static boolean containsAggregateFunction(PlainSelect plainSelect) {
        List<SelectItem<?>> selectItems = plainSelect.getSelectItems();
        if (CollectionUtils.isEmpty(selectItems)) {
            return false;
        }
        for (SelectItem<?> item : selectItems) {
            if (item.getExpression() instanceof Function) {
                Function fn = (Function) item.getExpression();
                String name = fn.getName();
                if (StringUtils.isNotBlank(name)
                        && AGGREGATE_FUNCTIONS.contains(name.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 模型上配置的固定过滤条件需要合并进 WHERE。 */
    private static String mergeFilterSql(String sql, LlmNativeContext context) {
        String filterSql = context.getFilterSql();
        if (StringUtils.isBlank(filterSql)) {
            return sql;
        }
        try {
            Expression expression = CCJSqlParserUtil.parseCondExpression(filterSql);
            String wrapped = SqlAddHelper.addParenthesisToWhere(sql);
            String result = SqlAddHelper.addWhere(wrapped, expression);
            log.info("[LLM_NATIVE] 合并模型固定过滤条件: {}", filterSql);
            return result;
        } catch (Exception e) {
            log.warn("[LLM_NATIVE] 合并 filterSql 失败，跳过: {}", filterSql, e);
            return sql;
        }
    }

    /** 与 ResultLimitOptimizer 一致，直接字符串追加。 */
    private static String ensureLimit(String sql) {
        try {
            if (Boolean.TRUE.equals(SqlSelectHelper.hasLimit(sql))) {
                return sql;
            }
        } catch (Exception e) {
            log.warn("[LLM_NATIVE] 判断 LIMIT 失败，按无 LIMIT 处理", e);
        }
        return sql + " LIMIT " + DEFAULT_LIMIT;
    }

    /**
     * SQL 建模场景：把 FROM 中的占位表替换为建模 SQL 子查询。
     *
     * <p>
     * 不用 {@code SqlReplaceHelper.replaceTable}，因为它会把 FROM 里所有表名无条件改写成 目标字符串（见
     * {@code TableNameReplaceVisitor}），且无法安全地塞入一个子查询。 这里直接定位 FromItem 并换成
     * ParenthesedSelect，精确且不会误伤别名。
     */
    private static String replaceBaseQuery(String sql, LlmNativeContext context) {
        String baseQuerySql = context.getBaseQuerySql();
        if (StringUtils.isBlank(baseQuerySql)) {
            return sql;
        }
        try {
            Select select = (Select) CCJSqlParserUtil.parse(sql);
            PlainSelect plainSelect = select.getPlainSelect();
            if (plainSelect == null) {
                throw new IllegalStateException("生成的SQL不是单一SELECT，无法替换建模SQL占位表");
            }
            FromItem fromItem = plainSelect.getFromItem();
            if (!(fromItem instanceof Table)) {
                throw new IllegalStateException("生成的SQL FROM 子句不是占位表，无法替换建模SQL");
            }
            String fromName = LlmNativeSchemaBuilder.normalizeField(((Table) fromItem).getName());
            if (!LlmNativeContext.BASE_QUERY_ALIAS.equals(fromName)) {
                throw new IllegalStateException("生成的SQL FROM 子句应为 "
                        + LlmNativeContext.BASE_QUERY_ALIAS + "，实际为 " + fromName);
            }
            ParenthesedSelect subSelect = new ParenthesedSelect();
            subSelect.setSelect((Select) CCJSqlParserUtil.parse(baseQuerySql));
            subSelect.setAlias(new Alias(LlmNativeContext.BASE_QUERY_ALIAS));
            plainSelect.setFromItem(subSelect);
            return select.toString();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("替换建模SQL占位表失败: " + e.getMessage(), e);
        }
    }

    private static Set<String> safeCollect(FieldSupplier supplier) {
        try {
            List<String> fields = supplier.get();
            return CollectionUtils.isEmpty(fields) ? new HashSet<>() : new HashSet<>(fields);
        } catch (Exception e) {
            log.warn("[LLM_NATIVE] 采集SQL字段失败", e);
            return new HashSet<>();
        }
    }

    private static Set<String> safeCollectSet(SetSupplier supplier) {
        try {
            Set<String> fields = supplier.get();
            return CollectionUtils.isEmpty(fields) ? new HashSet<>() : new HashSet<>(fields);
        } catch (Exception e) {
            log.warn("[LLM_NATIVE] 采集SQL别名失败", e);
            return new HashSet<>();
        }
    }

    @FunctionalInterface
    private interface FieldSupplier {
        List<String> get() throws Exception;
    }

    @FunctionalInterface
    private interface SetSupplier {
        Set<String> get() throws Exception;
    }
}
