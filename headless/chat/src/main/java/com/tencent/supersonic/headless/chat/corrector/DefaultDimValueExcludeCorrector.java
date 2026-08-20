package com.tencent.supersonic.headless.chat.corrector;

import com.tencent.supersonic.common.jsqlparser.SqlAddHelper;
import com.tencent.supersonic.common.jsqlparser.SqlSelectHelper;
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
 * Enforce excluding dimension default values in S2SQL when upstream (e.g. LLM words segmentation)
 * indicates this is a distribution/ranking/topN question.
 *
 * <p>
 * Logic:
 * <ul>
 * <li>If {@link ChatQueryContext#getExcludeDefaultDimNames()} is empty: no-op</li>
 * <li>Otherwise, for each dimension name, if SQL doesn't already filter this dimension, append
 * {@code dim NOT IN ('default1','default2',...)} to WHERE</li>
 * </ul>
 */
@Slf4j
public class DefaultDimValueExcludeCorrector extends BaseSemanticCorrector {

    @Override
    public void doCorrect(ChatQueryContext chatQueryContext, SemanticParseInfo semanticParseInfo) {
        List<String> excludeDimNames = chatQueryContext.getExcludeDefaultDimNames();
        if (CollectionUtils.isEmpty(excludeDimNames)) {
            return;
        }

        String correctedS2SQL = semanticParseInfo.getSqlInfo().getCorrectedS2SQL();
        if (StringUtils.isBlank(correctedS2SQL)) {
            return;
        }

        Long dataSetId = semanticParseInfo.getDataSetId();
        SemanticSchema semanticSchema = chatQueryContext.getSemanticSchema();
        List<SchemaElement> dimensions = semanticSchema.getDimensions(dataSetId);
        if (CollectionUtils.isEmpty(dimensions)) {
            return;
        }
        Map<String, String> termDefaultValues = findTermDefaultValues(semanticSchema);

        Set<String> whereFields = new HashSet<>(SqlSelectHelper.getWhereFields(correctedS2SQL));

        boolean whereWrapped = false;

        // Only process dimensions that exist and have defaultValues.
        for (String dimHint : excludeDimNames) {
            if (StringUtils.isBlank(dimHint)) {
                continue;
            }

            SchemaElement dim = findDimension(dimensions, dimHint.trim());
            if (dim == null) {
                continue;
            }

            List<String> defaultValues = dim.getDefaultValues();
            if (CollectionUtils.isEmpty(defaultValues)) {
                String bizName = dim.getBizName();
                if (StringUtils.isNotBlank(bizName) && termDefaultValues.containsKey(bizName)) {
                    defaultValues = Collections.singletonList(termDefaultValues.get(bizName));
                }
            }
            if (CollectionUtils.isEmpty(defaultValues)) {
                continue;
            }

            String dimName = Objects.toString(dim.getName(), "");
            if (StringUtils.isBlank(dimName)) {
                continue;
            }

            // If LLM already applied any filter on this dimension, don't force add another.
            if (whereFields.contains(dimName)) {
                continue;
            }

            String notInExpr = buildNotInExpr(dimName, defaultValues);
            if (StringUtils.isBlank(notInExpr)) {
                continue;
            }

            try {
                // Preserve existing OR semantics by wrapping the original WHERE once.
                if (!whereWrapped) {
                    correctedS2SQL = SqlAddHelper.addParenthesisToWhere(correctedS2SQL);
                    whereWrapped = true;
                }

                Expression expression = CCJSqlParserUtil.parseCondExpression(notInExpr);
                correctedS2SQL = SqlAddHelper.addWhere(correctedS2SQL, expression);
                // refresh where fields after modification
                whereFields = new HashSet<>(SqlSelectHelper.getWhereFields(correctedS2SQL));
                log.info("DefaultDimValueExcludeCorrector added where: {}", notInExpr);
            } catch (JSQLParserException e) {
                log.warn("DefaultDimValueExcludeCorrector parseCondExpression failed: {}",
                        notInExpr, e);
            } catch (Exception e) {
                log.warn("DefaultDimValueExcludeCorrector add where failed: {}", notInExpr, e);
            }
        }


        semanticParseInfo.getSqlInfo().setCorrectedS2SQL(correctedS2SQL);
    }

    private static SchemaElement findDimension(List<SchemaElement> dimensions, String hint) {
        for (SchemaElement dim : dimensions) {
            if (dim == null) {
                continue;
            }
            if (hint.equals(dim.getName()) || hint.equals(dim.getBizName())) {
                return dim;
            }
            if (!CollectionUtils.isEmpty(dim.getAlias()) && dim.getAlias().contains(hint)) {
                return dim;
            }
        }
        return null;
    }

    private static String buildNotInExpr(String fieldName, List<String> defaultValues) {
        if (StringUtils.isBlank(fieldName) || CollectionUtils.isEmpty(defaultValues)) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (String v : defaultValues) {
            if (StringUtils.isBlank(v)) {
                continue;
            }
            // escape single quote in string literal
            String escaped = v.replace("'", "''");
            values.add("'" + escaped + "'");
        }
        if (values.isEmpty()) {
            return null;
        }
        String joined = values.stream().distinct().collect(Collectors.joining(","));
        return fieldName + " NOT IN (" + joined + ")";
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
            log.warn("DefaultDimValueExcludeCorrector failed to parse term description: {}",
                    configTerm.getDescription(), e);
            return Collections.emptyMap();
        }
    }
}
