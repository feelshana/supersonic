package com.tencent.supersonic.headless.chat.corrector;

import com.tencent.supersonic.common.jsqlparser.SqlSelectHelper;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.SemanticSchema;
import com.tencent.supersonic.headless.chat.ChatQueryContext;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 术语驱动的 SELECT 字段强制补全校正器。
 *
 * <p>
 * 通过名为 {@value #TERM_NAME} 的术语（description 为 JSON 数组，元素为维度 name， 例如
 * {@code ["产品名称","省份","地市","日期"]}）来声明该数据集"必须查询的字段"。
 *
 * <p>
 * 校正阶段读取 correctedS2SQL 的 SELECT 子句，把术语中缺失的字段补全到 SELECT 末尾， 以缓解大模型未严格遵循提示词导致的字段缺失问题。
 *
 * <p>
 * 为避免破坏 SQL 语义/语法，以下三类查询会跳过补全：
 * <ul>
 * <li>含 {@code GROUP BY} 的查询（分组明细 / TopN）；</li>
 * <li>含 {@code DISTINCT} 的查询（去重枚举）；</li>
 * <li>SELECT 含聚合函数（SUM/COUNT/AVG/MIN/MAX 等）但无 GROUP BY 的"单行汇总"查询。</li>
 * </ul>
 */
@Slf4j
public class MandatorySelectFieldCorrector extends BaseSemanticCorrector {

    private static final String TERM_NAME = "必须查询的字段";

    /** 常见聚合函数名 */
    private static final Set<String> AGGREGATE_FUNCTIONS =
            new HashSet<>(Arrays.asList("sum", "count", "avg", "min", "max", "count_distinct"));

    @Override
    public void doCorrect(ChatQueryContext chatQueryContext, SemanticParseInfo semanticParseInfo) {
        String correctedS2SQL = semanticParseInfo.getSqlInfo().getCorrectedS2SQL();
        if (StringUtils.isBlank(correctedS2SQL)) {
            return;
        }

        SemanticSchema semanticSchema = chatQueryContext.getSemanticSchema();
        List<String> mandatoryFields = findMandatoryFields(semanticSchema);
        if (CollectionUtils.isEmpty(mandatoryFields)) {
            return;
        }

        Select selectStatement;
        try {
            selectStatement = SqlSelectHelper.getSelect(correctedS2SQL);
        } catch (Exception e) {
            log.warn("MandatorySelectFieldCorrector parse SQL failed: {}", correctedS2SQL, e);
            return;
        }
        if (!(selectStatement instanceof PlainSelect)) {
            // SET 操作（UNION 等）暂不处理
            if (selectStatement instanceof SetOperationList) {
                log.info("MandatorySelectFieldCorrector skipped: SetOperation SQL");
            }
            return;
        }

        PlainSelect plainSelect = (PlainSelect) selectStatement;

        // 跳过：有 GROUP BY
        if (plainSelect.getGroupBy() != null) {
            log.info("MandatorySelectFieldCorrector skipped: SQL has GROUP BY");
            return;
        }

        // 跳过：DISTINCT
        if (plainSelect.getDistinct() != null) {
            log.info("MandatorySelectFieldCorrector skipped: SQL has DISTINCT");
            return;
        }

        // 跳过：SELECT 含聚合函数（无 GROUP BY 时为单行汇总，加裸字段会导致非法 SQL）
        if (containsAggregateFunction(plainSelect)) {
            log.info("MandatorySelectFieldCorrector skipped: SELECT contains aggregate function");
            return;
        }

        // 已存在的字段集合（保留原始大小写做匹配）
        Set<String> existingSelectFields = new HashSet<>();
        try {
            existingSelectFields.addAll(SqlSelectHelper.getSelectFields(correctedS2SQL));
        } catch (Exception e) {
            log.warn("MandatorySelectFieldCorrector getSelectFields failed: {}", correctedS2SQL, e);
        }

        boolean changed = false;
        for (String mandatoryField : mandatoryFields) {
            if (StringUtils.isBlank(mandatoryField)) {
                continue;
            }
            if (containsIgnoreCase(existingSelectFields, mandatoryField)) {
                continue;
            }
            try {
                SelectItem<Column> newItem = new SelectItem<>(new Column(mandatoryField));
                plainSelect.addSelectItems(newItem);
                existingSelectFields.add(mandatoryField);
                changed = true;
                log.info("MandatorySelectFieldCorrector added select field: {}", mandatoryField);
            } catch (Exception e) {
                log.warn("MandatorySelectFieldCorrector add select field failed: {}",
                        mandatoryField, e);
            }
        }

        if (changed) {
            semanticParseInfo.getSqlInfo().setCorrectedS2SQL(plainSelect.toString());
        }
    }

    /**
     * 判断 SELECT 子句是否含聚合函数。
     */
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

    private static boolean containsIgnoreCase(Set<String> set, String target) {
        if (CollectionUtils.isEmpty(set) || StringUtils.isBlank(target)) {
            return false;
        }
        for (String s : set) {
            if (s != null && s.equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析术语 "必须查询的字段" 的 description（JSON 数组），返回去重后的字段列表（保留原始顺序）。
     */
    private static List<String> findMandatoryFields(SemanticSchema semanticSchema) {
        if (semanticSchema == null) {
            return Collections.emptyList();
        }
        List<SchemaElement> terms = semanticSchema.getTerms();
        if (CollectionUtils.isEmpty(terms)) {
            return Collections.emptyList();
        }
        SchemaElement configTerm =
                terms.stream().filter(t -> TERM_NAME.equals(t.getName())).findFirst().orElse(null);
        if (configTerm == null || StringUtils.isBlank(configTerm.getDescription())) {
            return Collections.emptyList();
        }
        try {
            List<String> rawList = JsonUtil.toList(configTerm.getDescription(), String.class);
            if (CollectionUtils.isEmpty(rawList)) {
                return Collections.emptyList();
            }
            // 保持顺序、去除空白、去重
            Set<String> dedup = new LinkedHashSet<>();
            for (String s : rawList) {
                if (StringUtils.isNotBlank(s)) {
                    dedup.add(s.trim());
                }
            }
            return new java.util.ArrayList<>(dedup);
        } catch (Exception e) {
            log.warn("MandatorySelectFieldCorrector failed to parse term description: {}",
                    configTerm.getDescription(), e);
            return Collections.emptyList();
        }
    }
}
