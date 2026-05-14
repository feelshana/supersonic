package com.tencent.supersonic.headless.chat.corrector;

import com.tencent.supersonic.common.jsqlparser.SqlAddHelper;
import com.tencent.supersonic.common.jsqlparser.SqlSelectHelper;
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
 * corrector reads a Term named {@value #TERM_NAME} whose description is a JSON map
 * ({@code bizName → defaultValue}), and appends {@code dimName = 'defaultValue'} to the WHERE
 * clause for dimensions that the user's query does not mention.
 *
 * <p>
 * Dimensions that already have {@code defaultValues} configured in the dimension table are skipped,
 * leaving them to {@code extractDefaultDimValue} in the translate stage.
 */
@Slf4j
public class TermBasedDefaultDimValueCorrector extends BaseSemanticCorrector {

    private static final String TERM_NAME = "默认值配置";

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

        Set<String> whereFields = new HashSet<>(SqlSelectHelper.getWhereFields(correctedS2SQL));
        List<String> excludeDefaultDimNames = chatQueryContext.getExcludeDefaultDimNames();
        Set<String> excludeDimNameSet =
                CollectionUtils.isEmpty(excludeDefaultDimNames) ? Collections.emptySet()
                        : new HashSet<>(excludeDefaultDimNames);

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

    private static Map<String, String> findTermDefaultValues(SemanticSchema semanticSchema) {
        List<SchemaElement> terms = semanticSchema.getTerms();
        if (CollectionUtils.isEmpty(terms)) {
            return Collections.emptyMap();
        }

        SchemaElement configTerm =
                terms.stream().filter(t -> TERM_NAME.equals(t.getName())).findFirst().orElse(null);

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
