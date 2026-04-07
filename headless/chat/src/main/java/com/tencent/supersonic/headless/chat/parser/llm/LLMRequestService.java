package com.tencent.supersonic.headless.chat.parser.llm;

import com.tencent.supersonic.common.pojo.Pair;
import com.tencent.supersonic.common.util.DateUtils;
import com.tencent.supersonic.headless.api.pojo.*;
import com.tencent.supersonic.headless.chat.ChatQueryContext;
import com.tencent.supersonic.headless.chat.parser.ParserConfig;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMResp;
import com.tencent.supersonic.headless.chat.utils.ComponentFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;

import static com.tencent.supersonic.headless.chat.parser.ParserConfig.*;

@Slf4j
@Service
public class LLMRequestService {

    private static final int MAX_PROMPT_TERMS = 10;

    @Autowired
    private ParserConfig parserConfig;


    public Long getDataSetId(ChatQueryContext queryCtx) {
        // SIMPLE 模式：跳过了 MAPPING，MapInfo 为空，直接从请求的 dataSetIds 中取第一个
        if (queryCtx.isSimpleMode()) {
            Set<Long> dataSetIds = queryCtx.getRequest().getDataSetIds();
            if (!CollectionUtils.isEmpty(dataSetIds)) {
                Long dataSetId = dataSetIds.iterator().next();
                log.info("[SIMPLE MODE] 直接使用请求中的 dataSetId: {}", dataSetId);
                return dataSetId;
            }
            log.warn("[SIMPLE MODE] 请求中未指定 dataSetIds，无法确定数据集");
            return null;
        }
        DataSetResolver dataSetResolver = ComponentFactory.getModelResolver();
        return dataSetResolver.resolve(queryCtx, queryCtx.getRequest().getDataSetIds());
    }

    public LLMReq getLlmReq(ChatQueryContext queryCtx, Long dataSetId) {
        Map<Long, String> dataSetIdToName = queryCtx.getSemanticSchema().getDataSetIdToName();
        String queryText = queryCtx.getRequest().getQueryText();

        LLMReq.LLMSchema llmSchema = new LLMReq.LLMSchema();
        int fieldCntThreshold =
                Integer.valueOf(parserConfig.getParameterValue(PARSER_FIELDS_COUNT_THRESHOLD));
        if (queryCtx.getMapInfo().getMatchedElements(dataSetId).size() <= fieldCntThreshold) {
            llmSchema.setMetrics(queryCtx.getSemanticSchema().getMetrics());
            llmSchema.setDimensions(queryCtx.getSemanticSchema().getDimensions());
        } else {
            llmSchema.setMetrics(getMappedMetrics(queryCtx, dataSetId));
            llmSchema.setDimensions(getMappedDimensions(queryCtx, dataSetId));
        }

        LLMReq llmReq = new LLMReq();
        llmReq.setQueryText(queryText);
        llmReq.setSchema(llmSchema);
        Pair<String, String> databaseInfo = getDatabaseType(queryCtx, dataSetId);
        llmSchema.setDatabaseType(databaseInfo.first);
        llmSchema.setDatabaseVersion(databaseInfo.second);
        llmSchema.setDataSetId(dataSetId);
        llmSchema.setDataSetName(dataSetIdToName.get(dataSetId));
        llmSchema.setPartitionTime(getPartitionTime(queryCtx, dataSetId));
        llmSchema.setPrimaryKey(getPrimaryKey(queryCtx, dataSetId));

        boolean linkingValueEnabled =
                Boolean.parseBoolean(parserConfig.getParameterValue(PARSER_LINKING_VALUE_ENABLE));
        if (linkingValueEnabled) {
            llmSchema.setValues(getMappedValues(queryCtx, dataSetId));
        }

        llmReq.setCurrentDate(DateUtils.getBeforeDate(0));
        llmReq.setTerms(getMappedTerms(queryCtx, dataSetId));
        llmReq.setSqlGenType(
                LLMReq.SqlGenType.valueOf(parserConfig.getParameterValue(PARSER_STRATEGY_TYPE)));
        llmReq.setChatAppConfig(queryCtx.getRequest().getChatAppConfig());
        llmReq.setDynamicExemplars(queryCtx.getRequest().getDynamicExemplars());

        llmReq.setAgentId(queryCtx.getRequest().getAgentId());
        // SIMPLE 模式标记透传，供 OnePassSCSqlGenStrategy 判断是否跳过向量召回走直接生成路径
        llmReq.setQueryType(queryCtx.getRequest().getQueryType());
        return llmReq;
    }

    public LLMResp runText2SQL(LLMReq llmReq) {
        SqlGenStrategy sqlGenStrategy = SqlGenStrategyFactory.get(llmReq.getSqlGenType());
        String dataSet = llmReq.getSchema().getDataSetName();
        LLMResp result = sqlGenStrategy.generate(llmReq);
        result.setQuery(llmReq.getQueryText());
        result.setDataSet(dataSet);
        return result;
    }

    protected List<LLMReq.Term> getMappedTerms(ChatQueryContext queryCtx, Long dataSetId) {
        Map<Long, SchemaElement> selectedTerms = new LinkedHashMap<>();

        List<SchemaElementMatch> matchedElements =
                queryCtx.getMapInfo().getMatchedElements(dataSetId);
        if (!CollectionUtils.isEmpty(matchedElements)) {
            matchedElements.stream().map(SchemaElementMatch::getElement).filter(Objects::nonNull)
                    .filter(element -> SchemaElementType.TERM.equals(element.getType()))
                    .forEach(element -> selectedTerms.putIfAbsent(element.getId(), element));
        }

        if (selectedTerms.isEmpty()) {
            Map<Long, SchemaElement> allTermsById = getAllTermsById(queryCtx, dataSetId);
            if (!allTermsById.isEmpty()) {
                String queryText = StringUtils.defaultString(queryCtx.getRequest().getQueryText());
                allTermsById.values().stream().filter(term -> isExactTermMatched(queryText, term))
                        .sorted(Comparator
                                .comparingInt(
                                        term -> getTermMatchScore(queryText, (SchemaElement) term))
                                .reversed())
                        .limit(MAX_PROMPT_TERMS)
                        .forEach(term -> selectedTerms.putIfAbsent(term.getId(), term));
            }
        }

        return selectedTerms.values().stream().limit(MAX_PROMPT_TERMS).map(this::convertToReqTerm)
                .collect(Collectors.toList());
    }



    private Map<Long, SchemaElement> getAllTermsById(ChatQueryContext queryCtx, Long dataSetId) {
        SemanticSchema semanticSchema = queryCtx.getSemanticSchema();
        if (Objects.isNull(semanticSchema)
                || Objects.isNull(semanticSchema.getDataSetSchemaMap())) {
            return Collections.emptyMap();
        }
        DataSetSchema dataSetSchema = semanticSchema.getDataSetSchemaMap().get(dataSetId);
        if (Objects.isNull(dataSetSchema) || CollectionUtils.isEmpty(dataSetSchema.getTerms())) {
            return Collections.emptyMap();
        }
        return dataSetSchema.getTerms().stream().filter(Objects::nonNull).collect(Collectors
                .toMap(SchemaElement::getId, term -> term, (t1, t2) -> t1, LinkedHashMap::new));
    }

    private boolean isExactTermMatched(String queryText, SchemaElement term) {
        if (StringUtils.isBlank(queryText) || Objects.isNull(term)) {
            return false;
        }
        String normalizedQueryText = queryText.toLowerCase();
        if (StringUtils.isNotBlank(term.getName())
                && normalizedQueryText.contains(term.getName().toLowerCase())) {
            return true;
        }
        if (CollectionUtils.isEmpty(term.getAlias())) {
            return false;
        }
        return term.getAlias().stream().filter(StringUtils::isNotBlank).map(String::toLowerCase)
                .anyMatch(normalizedQueryText::contains);
    }

    private int getTermMatchScore(String queryText, SchemaElement term) {
        if (StringUtils.isBlank(queryText) || Objects.isNull(term)) {
            return 0;
        }
        String normalizedQueryText = queryText.toLowerCase();
        int score = 0;

        String name = StringUtils.trimToEmpty(term.getName()).toLowerCase();
        if (StringUtils.isNotBlank(name)) {
            if (normalizedQueryText.equals(name)) {
                score += 1000;
            } else if (normalizedQueryText.contains(name)) {
                score += 500 + name.length();
            }
        }

        if (!CollectionUtils.isEmpty(term.getAlias())) {
            for (String alias : term.getAlias()) {
                String normalizedAlias = StringUtils.trimToEmpty(alias).toLowerCase();
                if (StringUtils.isBlank(normalizedAlias)) {
                    continue;
                }
                if (normalizedQueryText.equals(normalizedAlias)) {
                    score += 400;
                } else if (normalizedQueryText.contains(normalizedAlias)) {
                    score += 200 + normalizedAlias.length();
                }
            }
        }
        return score;
    }


    private LLMReq.Term convertToReqTerm(SchemaElement schemaElement) {
        LLMReq.Term term = new LLMReq.Term();
        term.setName(schemaElement.getName());
        term.setDescription(schemaElement.getDescription());
        term.setAlias(schemaElement.getAlias());
        return term;
    }


    protected List<LLMReq.ElementValue> getMappedValues(@NotNull ChatQueryContext queryCtx,
            Long dataSetId) {
        List<SchemaElementMatch> matchedElements =
                queryCtx.getMapInfo().getMatchedElements(dataSetId);
        if (CollectionUtils.isEmpty(matchedElements)) {
            return new ArrayList<>();
        }
        Set<LLMReq.ElementValue> valueMatches = matchedElements.stream()
                .filter(elementMatch -> !elementMatch.isInherited()).filter(schemaElementMatch -> {
                    SchemaElementType type = schemaElementMatch.getElement().getType();
                    return SchemaElementType.VALUE.equals(type)
                            || SchemaElementType.ID.equals(type);
                }).map(elementMatch -> {
                    LLMReq.ElementValue elementValue = new LLMReq.ElementValue();
                    elementValue.setFieldName(elementMatch.getElement().getName());
                    elementValue.setFieldValue(elementMatch.getWord());
                    elementValue.setMatchType(elementMatch.getMatchType());
                    return elementValue;
                }).collect(Collectors.toSet());
        return new ArrayList<>(valueMatches);
    }

    protected List<SchemaElement> getMappedMetrics(@NotNull ChatQueryContext queryCtx,
            Long dataSetId) {
        List<SchemaElementMatch> matchedElements =
                queryCtx.getMapInfo().getMatchedElements(dataSetId);
        if (CollectionUtils.isEmpty(matchedElements)) {
            return Collections.emptyList();
        }
        return matchedElements.stream().filter(schemaElementMatch -> {
            SchemaElementType elementType = schemaElementMatch.getElement().getType();
            return SchemaElementType.METRIC.equals(elementType);
        }).map(SchemaElementMatch::getElement).collect(Collectors.toList());
    }

    protected List<SchemaElement> getMappedDimensions(@NotNull ChatQueryContext queryCtx,
            Long dataSetId) {

        List<SchemaElementMatch> matchedElements =
                queryCtx.getMapInfo().getMatchedElements(dataSetId);
        List<SchemaElement> dimensionElements = matchedElements.stream().filter(
                element -> SchemaElementType.DIMENSION.equals(element.getElement().getType()))
                .map(SchemaElementMatch::getElement).collect(Collectors.toList());

        return new ArrayList<>(dimensionElements);
    }

    protected SchemaElement getPartitionTime(@NotNull ChatQueryContext queryCtx, Long dataSetId) {
        SemanticSchema semanticSchema = queryCtx.getSemanticSchema();
        if (semanticSchema == null || semanticSchema.getDataSetSchemaMap() == null) {
            return null;
        }
        Map<Long, DataSetSchema> dataSetSchemaMap = semanticSchema.getDataSetSchemaMap();
        DataSetSchema dataSetSchema = dataSetSchemaMap.get(dataSetId);
        return dataSetSchema.getPartitionDimension();
    }

    protected SchemaElement getPrimaryKey(@NotNull ChatQueryContext queryCtx, Long dataSetId) {
        SemanticSchema semanticSchema = queryCtx.getSemanticSchema();
        if (semanticSchema == null || semanticSchema.getDataSetSchemaMap() == null) {
            return null;
        }
        Map<Long, DataSetSchema> dataSetSchemaMap = semanticSchema.getDataSetSchemaMap();
        DataSetSchema dataSetSchema = dataSetSchemaMap.get(dataSetId);
        return dataSetSchema.getPrimaryKey();
    }

    protected Pair<String, String> getDatabaseType(@NotNull ChatQueryContext queryCtx,
            Long dataSetId) {
        SemanticSchema semanticSchema = queryCtx.getSemanticSchema();
        if (semanticSchema == null || semanticSchema.getDataSetSchemaMap() == null) {
            return null;
        }
        Map<Long, DataSetSchema> dataSetSchemaMap = semanticSchema.getDataSetSchemaMap();
        DataSetSchema dataSetSchema = dataSetSchemaMap.get(dataSetId);
        return new Pair(dataSetSchema.getDatabaseType(), dataSetSchema.getDatabaseVersion());
    }
}
