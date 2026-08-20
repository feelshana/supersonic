package com.tencent.supersonic.headless.chat.corrector;

import com.tencent.supersonic.common.jsqlparser.SqlAddHelper;
import com.tencent.supersonic.common.jsqlparser.SqlSelectHelper;
import com.tencent.supersonic.common.pojo.BiReportConfigDO;
import com.tencent.supersonic.common.pojo.TermConstants;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.SemanticSchema;
import com.tencent.supersonic.headless.chat.ChatQueryContext;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Supplement dimension default-value filters based on a special Term configuration.
 *
 * <p>
 * When users forget to configure {@code defaultValues} on certain dimensions via BI training, this
 * corrector reads a Term named {@link TermConstants#DEFAULT_DIM_VALUE_CONFIG} whose description is
 * a JSON map ({@code bizName → defaultValue}), and appends {@code dimName = 'defaultValue'} to the
 * WHERE clause for dimensions that the user's query does not mention.
 *
 * <p>
 * Dimensions that already have {@code defaultValues} configured in the dimension table are skipped,
 * leaving them to {@code extractDefaultDimValue} in the translate stage.
 */
@Slf4j
public class TermBasedDefaultDimValueCorrector extends BaseSemanticCorrector {

    @Override
    public void doCorrect(ChatQueryContext chatQueryContext, SemanticParseInfo semanticParseInfo) {
        String correctedS2SQL = semanticParseInfo.getSqlInfo().getCorrectedS2SQL();
        if (StringUtils.isBlank(correctedS2SQL)) {
            return;
        }

        Long dataSetId = semanticParseInfo.getDataSetId();
        SemanticSchema semanticSchema = chatQueryContext.getSemanticSchema();

        Map<String, String> termDefaultValues = findTermDefaultValues(semanticSchema);
        if (termDefaultValues.isEmpty()) {
            return;
        }

        List<SchemaElement> dimensions = semanticSchema.getDimensions(dataSetId);
        if (CollectionUtils.isEmpty(dimensions)) {
            return;
        }

        Set<String> alreadyConfiguredBizNames =
                dimensions.stream().filter(d -> !CollectionUtils.isEmpty(d.getDefaultValues()))
                        .map(SchemaElement::getBizName).filter(StringUtils::isNotBlank)
                        .collect(Collectors.toSet());

        Map<String, SchemaElement> bizNameToDim =
                dimensions.stream().filter(d -> StringUtils.isNotBlank(d.getBizName()))
                        .collect(Collectors.toMap(SchemaElement::getBizName, d -> d, (a, b) -> a));

        // bizName -> name 映射，用于层级关系检查时将 dimRelation 中的 bizName 转换为 WHERE 中使用的 name
        Map<String, String> bizNameToName = dimensions.stream().filter(
                d -> StringUtils.isNotBlank(d.getBizName()) && StringUtils.isNotBlank(d.getName()))
                .collect(Collectors.toMap(SchemaElement::getBizName, SchemaElement::getName,
                        (a, b) -> a));

        Set<String> whereFields = new HashSet<>(SqlSelectHelper.getWhereFields(correctedS2SQL));
        // 用于层级关系检查的原始 WHERE 集合（不包含本 corrector 后续添加的默认値条件）
        Set<String> originalWhereFields = new HashSet<>(whereFields);

        List<String> excludeDefaultDimNames = chatQueryContext.getExcludeDefaultDimNames();
        Set<String> excludeDimNameSet =
                CollectionUtils.isEmpty(excludeDefaultDimNames) ? Collections.emptySet()
                        : new HashSet<>(excludeDefaultDimNames);

        // 解析层级关系（type=1）和同级关系（type=2）
        List<BiReportConfigDO> dimensionRelations = chatQueryContext.getDimensionRelations();
        List<List<String>> childHierarchies = parseRelationGroups(dimensionRelations, 1);
        List<List<String>> siblingGroups = parseRelationGroups(dimensionRelations, 2);

        for (Map.Entry<String, String> entry : termDefaultValues.entrySet()) {
            String bizName = entry.getKey();
            String defaultValue = entry.getValue();

            if (StringUtils.isBlank(bizName) || StringUtils.isBlank(defaultValue)) {
                continue;
            }

            if (alreadyConfiguredBizNames.contains(bizName)) {
                continue;
            }

            SchemaElement dim = bizNameToDim.get(bizName);
            if (dim == null) {
                continue;
            }

            String dimName = dim.getName();
            if (StringUtils.isBlank(dimName)) {
                continue;
            }

            // 只要 WHERE 中出现了该维度（不管什么条件），就跳过
            if (whereFields.contains(dimName)) {
                continue;
            }

            // 层级维度（type=1）：如果层级链中有子级维度已出现在用户指定的 WHERE 中，则跳过该维度（上级）的默认値
            if (hasChildInWhere(bizName, originalWhereFields, bizNameToName, childHierarchies)) {
                log.info("TermBasedDefaultDimValueCorrector skipped [{}]: child dim in WHERE",
                        dimName);
                continue;
            }

            // 同级维度（type=2）：如果同组中有其他维度出现在用户指定的 WHERE 中，则跳过
            if (hasSiblingInWhere(bizName, originalWhereFields, bizNameToName, siblingGroups)) {
                log.info("TermBasedDefaultDimValueCorrector skipped [{}]: sibling dim in WHERE",
                        dimName);
                continue;
            }

            // 省市关系：WHERE 中含有城市相关字段时，跳过省份维度的默认値（与 hasProvinceCityRelation 逻辑一致）
            if (hasProvinceCityRelation(bizName, originalWhereFields)) {
                log.info("TermBasedDefaultDimValueCorrector skipped [{}]: city dim in WHERE",
                        dimName);
                continue;
            }

            String escaped = defaultValue.replace("'", "''");
            String condExpr;
            if (excludeDimNameSet.contains(dimName)) {
                condExpr = dimName + " != '" + escaped + "'";
            } else {
                condExpr = dimName + " = '" + escaped + "'";
            }

            try {
                Expression expression = CCJSqlParserUtil.parseCondExpression(condExpr);
                correctedS2SQL = SqlAddHelper.addWhere(correctedS2SQL, expression);
                whereFields.add(dimName);
                log.info("TermBasedDefaultDimValueCorrector added where: {}", condExpr);
            } catch (JSQLParserException e) {
                log.warn("TermBasedDefaultDimValueCorrector parseCondExpression failed: {}",
                        condExpr, e);
            } catch (Exception e) {
                log.warn("TermBasedDefaultDimValueCorrector add where failed: {}", condExpr, e);
            }
        }

        semanticParseInfo.getSqlInfo().setCorrectedS2SQL(correctedS2SQL);
    }

    /**
     * 解析 BiReportConfigDO 列表，提取指定 type 的维度关系组。 每个元素是一组按顺序排列的 bizName（对 type=1 表示层级链，高级在前）。
     */
    private static List<List<String>> parseRelationGroups(List<BiReportConfigDO> dimensionRelations,
            int type) {
        if (CollectionUtils.isEmpty(dimensionRelations)) {
            return Collections.emptyList();
        }
        return dimensionRelations.stream().filter(c -> c.getType() != null && c.getType() == type)
                .filter(c -> StringUtils.isNotBlank(c.getDimRelation()))
                .flatMap(c -> Arrays.stream(c.getDimRelation().split(",")))
                .filter(StringUtils::isNotBlank)
                .map(relation -> Arrays.asList(relation.trim().split("/")))
                .filter(list -> list.size() > 1).collect(Collectors.toList());
    }

    /**
     * 层级关系检查（与原始 hasChildCondtion 逻辑一致）： 如果当前维度在层级链中存在子级维度（更低层）出现在 whereFields 中，则返回 true。
     * 即：用户指定了子级维度 → 跳过上级维度的默认値。
     */
    private static boolean hasChildInWhere(String bizName, Set<String> whereFields,
            Map<String, String> bizNameToName, List<List<String>> childHierarchies) {
        if (childHierarchies.isEmpty()) {
            return false;
        }
        for (List<String> hierarchy : childHierarchies) {
            if (!hierarchy.contains(bizName)) {
                continue;
            }
            int levelIndex = hierarchy.indexOf(bizName);
            // 已是最层，没有子级
            if (levelIndex == hierarchy.size() - 1) {
                continue;
            }
            // 检查子级 bizName 对应的 name 是否在 WHERE 中
            List<String> childBizNames = hierarchy.subList(levelIndex + 1, hierarchy.size());
            for (String childBizName : childBizNames) {
                String childName = bizNameToName.get(childBizName);
                if (StringUtils.isNotBlank(childName) && whereFields.contains(childName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 省市关系检查（与 SqlBuilder.hasProvinceCityRelation 逻辑一致）： 如果当前维度是省份维度（bizName 匹配
     * province/province_name/provinceName）， 且 whereFields 中存在城市相关字段，则返回 true，跳过省份默认値。 whereFields
     * 中的字段可以是 name（中文）或 bizName（英文），两套模式均覆盖。
     */
    private static boolean hasProvinceCityRelation(String bizName, Set<String> whereFields) {
        if (!(StringUtils.equalsIgnoreCase(bizName, "provinceName")
                || StringUtils.equalsIgnoreCase(bizName, "province_name")
                || StringUtils.equalsIgnoreCase(bizName, "province"))) {
            return false;
        }
        return whereFields.stream()
                .anyMatch(name -> StringUtils.equalsIgnoreCase(name, "city_name")
                        || StringUtils.equalsIgnoreCase(name, "cityName")
                        || StringUtils.equalsIgnoreCase(name, "city")
                        || StringUtils.equalsIgnoreCase(name, "城市")
                        || StringUtils.equalsIgnoreCase(name, "城市名称")
                        || StringUtils.equalsIgnoreCase(name, "地市")
                        || StringUtils.equalsIgnoreCase(name, "地市名称"));
    }

    /**
     * 同级关系检查（与原始 hasSiblingCondition 逻辑一致）： 如果同组中有其他维度出现在 whereFields 中，则返回 true。
     */
    private static boolean hasSiblingInWhere(String bizName, Set<String> whereFields,
            Map<String, String> bizNameToName, List<List<String>> siblingGroups) {
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
                String memberName = bizNameToName.get(memberBizName);
                if (StringUtils.isNotBlank(memberName) && whereFields.contains(memberName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, String> findTermDefaultValues(SemanticSchema semanticSchema) {
        List<SchemaElement> terms = semanticSchema.getTerms();
        if (CollectionUtils.isEmpty(terms)) {
            return Collections.emptyMap();
        }

        SchemaElement configTerm = terms.stream()
                .filter(t -> TermConstants.DEFAULT_DIM_VALUE_CONFIG.equals(t.getName())).findFirst()
                .orElse(null);

        if (configTerm == null || StringUtils.isBlank(configTerm.getDescription())) {
            return Collections.emptyMap();
        }

        try {
            Map<String, String> result =
                    JsonUtil.toMap(configTerm.getDescription(), String.class, String.class);
            return result != null ? result : Collections.emptyMap();
        } catch (Exception e) {
            log.warn("TermBasedDefaultDimValueCorrector failed to parse term description: {}",
                    configTerm.getDescription(), e);
            return Collections.emptyMap();
        }
    }
}
