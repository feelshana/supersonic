package com.tencent.supersonic.chat.server.parser.llmnative;

import com.tencent.supersonic.common.jsqlparser.SqlAddHelper;
import com.tencent.supersonic.common.jsqlparser.SqlSelectHelper;
import com.tencent.supersonic.common.pojo.BiReportConfigDO;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 向 LLM 生成的物理 SQL 中注入维度默认值条件。
 *
 * <p>
 * 判定规则（对每个配置了默认值的维度）：
 * <ul>
 * <li>维度在 WHERE（用户已给具体筛选）→ 保留用户条件，不注入</li>
 * <li>维度是"分布维度"（LLM 报告的分布维度 ∪ GROUP BY 字段）→ 追加 {@code dim != 默认值}，排除汇总行</li>
 * <li>维度完全未使用 → 追加 {@code dim = 默认值}</li>
 * </ul>
 *
 * <p>
 * 另有三条跳过规则，与 {@code SqlBuilder.extractDefaultDimValue} 保持一致：层级维度（type=1）的
 * 子级已被使用时跳过上级、同级维度（type=2）组内有其他维度被使用时跳过、省市关系 （已使用城市字段时跳过省份默认值）。
 *
 * <p>
 * "该不该排除默认值"的核心信号是{@code context.excludeDefaultDimBizNames}——由 SQL 生成前一次**独立**的分词 LLM 调用
 * （{@code LlmNativeSegmentService}）识别出的分布维度，语义等价于原链路 MAPPING 阶段的 {@code excludeDefaultDims}。
 * 之所以不用"维度是否出现在 SELECT"来判断：SELECT 里可能含仅展示/必查而非要拆分的维度， 对它们排除默认值会让结果错误地按其展开。GROUP BY
 * 字段是确定性的分布信号，一并纳入。
 */
@Slf4j
public class DefaultDimValueInjector {

    private DefaultDimValueInjector() {}

    public static String inject(String sql, LlmNativeContext context) {
        List<LlmNativeContext.DimMeta> dimsWithDefault = context.getDimensions().stream()
                .filter(d -> !CollectionUtils.isEmpty(d.getDefaultValues()))
                .collect(Collectors.toList());
        if (dimsWithDefault.isEmpty()) {
            return sql;
        }

        Set<String> whereFields = normalizeAll(safeGetWhereFields(sql));
        Set<String> groupByFields = normalizeAll(safeGetGroupByFields(sql));
        // "按某维度拆分"的信号 = 分词识别的分布维度 ∪ SQL 的 GROUP BY 字段。
        // 不用 SELECT 成员判断：SELECT 里可能含"仅展示/必查"而非"要拆分"的维度，
        // 对这些维度排除默认值会导致结果错误地按它们展开，与用户意图不符。
        Set<String> excludeFields = new HashSet<>(groupByFields);
        excludeFields.addAll(context.getExcludeDefaultDimBizNames());
        // 层级/同级/省市跳过检查的"已被使用"范围：用户在 WHERE 筛选的 + 要拆分的
        Set<String> usedFields = new HashSet<>(whereFields);
        usedFields.addAll(excludeFields);

        // customs 维度 sqlFragment 是表达式，字段集取不到，退化为 WHERE 文本匹配判断是否被用户筛选
        String whereText = normalize(extractWhereText(sql));

        List<List<String>> childHierarchies =
                parseRelationGroups(context.getDimensionRelations(), 1);
        List<List<String>> siblingGroups = parseRelationGroups(context.getDimensionRelations(), 2);
        Map<String, String> bizNameToSqlFragment =
                context.getDimensions().stream().filter(d -> StringUtils.isNotBlank(d.getBizName()))
                        .collect(Collectors.toMap(LlmNativeContext.DimMeta::getBizName,
                                LlmNativeContext.DimMeta::getSqlFragment, (a, b) -> a));

        String result = sql;
        for (LlmNativeContext.DimMeta dim : dimsWithDefault) {
            String bizName = dim.getBizName();
            String sqlFragment = dim.getSqlFragment();
            String defaultValue = dim.getDefaultValues().get(0);
            if (StringUtils.isBlank(sqlFragment) || StringUtils.isBlank(defaultValue)) {
                continue;
            }
            boolean inWhere = dim.isCustom() ? whereText.contains(normalize(sqlFragment))
                    : whereFields.contains(normalize(bizName));
            // 分布维度按 bizName 匹配；customs 维度的 bizName 即中文名，分词报告的名字已在解析阶段映射为 bizName
            boolean isExclude = excludeFields.contains(normalize(bizName));

            if (inWhere) {
                // 用户已显式筛选该维度 → 保留用户条件，不注入
                log.info("[LLM_NATIVE] 维度 [{}] 已被用户筛选，跳过默认值注入", dim.getName());
                continue;
            }
            if (isExclude) {
                // 用户要按该维度拆分 → 排除默认汇总行
                result = appendCondition(result, sqlFragment, defaultValue, false);
                continue;
            }
            if (hasChildInScope(bizName, usedFields, bizNameToSqlFragment, childHierarchies)) {
                log.info("[LLM_NATIVE] 维度 [{}] 的下级维度已被使用，跳过默认值注入", dim.getName());
                continue;
            }
            if (hasSiblingInScope(bizName, usedFields, bizNameToSqlFragment, siblingGroups)) {
                log.info("[LLM_NATIVE] 维度 [{}] 的同级维度已被使用，跳过默认值注入", dim.getName());
                continue;
            }
            if (hasProvinceCityRelation(bizName, usedFields)) {
                log.info("[LLM_NATIVE] 维度 [{}] 因存在城市维度筛选，跳过默认值注入", dim.getName());
                continue;
            }
            result = appendCondition(result, sqlFragment, defaultValue, true);
        }
        return result;
    }

    /** 追加 {@code fragment = 'value'} 或 {@code fragment != 'value'} 到 WHERE。 */
    private static String appendCondition(String sql, String sqlFragment, String value,
            boolean equals) {
        String escaped = value.replace("'", "''");
        String condExpr = sqlFragment + (equals ? " = '" : " != '") + escaped + "'";
        try {
            Expression expression = CCJSqlParserUtil.parseCondExpression(condExpr);
            // 原 WHERE 可能含 OR，加括号保住原有优先级
            String wrapped = SqlAddHelper.addParenthesisToWhere(sql);
            String added = SqlAddHelper.addWhere(wrapped, expression);
            log.info("[LLM_NATIVE] 注入默认值条件: {}", condExpr);
            return added;
        } catch (Exception e) {
            log.warn("[LLM_NATIVE] 注入默认值条件失败，跳过: {}", condExpr, e);
            return sql;
        }
    }

    /**
     * 层级关系（type=1）：若层级链中当前维度的下级维度已被使用（WHERE/GROUP BY/分布维度）， 则跳过当前（上级）维度的默认值。
     */
    private static boolean hasChildInScope(String bizName, Set<String> usedFields,
            Map<String, String> bizNameToSqlFragment, List<List<String>> childHierarchies) {
        if (childHierarchies.isEmpty()) {
            return false;
        }
        for (List<String> hierarchy : childHierarchies) {
            int levelIndex = hierarchy.indexOf(bizName);
            if (levelIndex < 0 || levelIndex == hierarchy.size() - 1) {
                continue;
            }
            for (String childBizName : hierarchy.subList(levelIndex + 1, hierarchy.size())) {
                if (isDimUsed(childBizName, usedFields, bizNameToSqlFragment)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 同级关系（type=2）：同组内有其他维度已被使用则跳过。 */
    private static boolean hasSiblingInScope(String bizName, Set<String> usedFields,
            Map<String, String> bizNameToSqlFragment, List<List<String>> siblingGroups) {
        if (siblingGroups.isEmpty()) {
            return false;
        }
        for (List<String> group : siblingGroups) {
            if (!group.contains(bizName)) {
                continue;
            }
            for (String memberBizName : group) {
                if (memberBizName.equals(bizName)) {
                    continue;
                }
                if (isDimUsed(memberBizName, usedFields, bizNameToSqlFragment)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 省市关系：当前维度是省份且 SQL 中已使用城市相关字段时跳过省份默认值。 与 {@code SqlBuilder.hasProvinceCityRelation} 的判定口径一致。
     */
    private static boolean hasProvinceCityRelation(String bizName, Set<String> usedFields) {
        if (!(StringUtils.equalsIgnoreCase(bizName, "provinceName")
                || StringUtils.equalsIgnoreCase(bizName, "province_name")
                || StringUtils.equalsIgnoreCase(bizName, "province"))) {
            return false;
        }
        return usedFields.stream()
                .anyMatch(name -> StringUtils.equalsIgnoreCase(name, "city_name")
                        || StringUtils.equalsIgnoreCase(name, "cityname")
                        || StringUtils.equalsIgnoreCase(name, "city"));
    }

    /** 判断某个 bizName 对应的维度是否已被使用（在 WHERE / GROUP BY / 分布维度 任一处）。 */
    private static boolean isDimUsed(String bizName, Set<String> usedFields,
            Map<String, String> bizNameToSqlFragment) {
        String normalized = normalize(bizName);
        if (usedFields.contains(normalized)) {
            return true;
        }
        // 关系配置里的 bizName 可能对应 customs 维度，退化为按其 SQL 片段匹配
        String fragment = bizNameToSqlFragment.get(bizName);
        if (StringUtils.isNotBlank(fragment) && !StringUtils.equals(fragment, bizName)) {
            return usedFields.contains(normalize(fragment));
        }
        return false;
    }

    /** 解析维度关系配置，dimRelation 形如 {@code "a/b/c,d/e"}，逗号分组、斜杠分级。 */
    private static List<List<String>> parseRelationGroups(List<BiReportConfigDO> relations,
            int type) {
        if (CollectionUtils.isEmpty(relations)) {
            return Collections.emptyList();
        }
        return relations.stream().filter(c -> c.getType() != null && c.getType() == type)
                .filter(c -> StringUtils.isNotBlank(c.getDimRelation()))
                .flatMap(c -> Arrays.stream(c.getDimRelation().split(",")))
                .filter(StringUtils::isNotBlank)
                .map(relation -> Arrays.asList(relation.trim().split("/")))
                .filter(list -> list.size() > 1).collect(Collectors.toList());
    }

    /** 提取 WHERE 子句文本，供 customs 维度（表达式，无列名）判断是否已被用户筛选。 */
    private static String extractWhereText(String sql) {
        try {
            Select select = (Select) CCJSqlParserUtil.parse(sql);
            PlainSelect ps = select.getPlainSelect();
            if (ps == null || ps.getWhere() == null) {
                return "";
            }
            return ps.getWhere().toString();
        } catch (Exception e) {
            log.warn("[LLM_NATIVE] 解析 WHERE 文本失败", e);
            return "";
        }
    }

    private static Set<String> normalizeAll(List<String> fields) {
        if (CollectionUtils.isEmpty(fields)) {
            return new HashSet<>();
        }
        return fields.stream().map(DefaultDimValueInjector::normalize)
                .filter(StringUtils::isNotBlank).collect(Collectors.toSet());
    }

    private static String normalize(String field) {
        return LlmNativeSchemaBuilder.normalizeField(field);
    }

    private static List<String> safeGetWhereFields(String sql) {
        try {
            return SqlSelectHelper.getWhereFields(sql);
        } catch (Exception e) {
            log.warn("[LLM_NATIVE] 解析 WHERE 字段失败", e);
            return new ArrayList<>();
        }
    }

    private static List<String> safeGetGroupByFields(String sql) {
        try {
            return SqlSelectHelper.getGroupByFields(sql);
        } catch (Exception e) {
            log.warn("[LLM_NATIVE] 解析 GROUP BY 字段失败", e);
            return new ArrayList<>();
        }
    }
}
