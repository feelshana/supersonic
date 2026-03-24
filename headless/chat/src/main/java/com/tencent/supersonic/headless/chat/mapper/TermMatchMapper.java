package com.tencent.supersonic.headless.chat.mapper;

import com.tencent.supersonic.headless.api.pojo.DataSetSchema;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SchemaElementMatch;
import com.tencent.supersonic.headless.api.pojo.SchemaElementType;
import com.tencent.supersonic.headless.api.pojo.enums.MapModeEnum;
import com.tencent.supersonic.headless.chat.ChatQueryContext;
import com.tencent.supersonic.headless.chat.knowledge.builder.BaseWordBuilder;
import com.tencent.supersonic.headless.chat.utils.EditDistanceUtils;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;


@Slf4j
public class TermMatchMapper extends BaseMapper {

    private static final int MAX_TERM_MATCH_SIZE = 10;
    private static final double FUZZY_TERM_MATCH_THRESHOLD = 0.88;

    @Override
    protected boolean accept(ChatQueryContext chatQueryContext) {
        return !MapModeEnum.ALL.equals(chatQueryContext.getRequest().getMapModeEnum());
    }

    @Override
    protected boolean acceptFilter() {
        return false;
    }

    @Override
    public void doMap(ChatQueryContext chatQueryContext) {
        String queryText = StringUtils.defaultString(chatQueryContext.getRequest().getQueryText());
        if (StringUtils.isBlank(queryText) || Objects.isNull(chatQueryContext.getSemanticSchema())
                || Objects.isNull(chatQueryContext.getSemanticSchema().getDataSetSchemaMap())) {
            return;
        }

        Set<Long> requestedDataSets = chatQueryContext.getRequest().getDataSetIds();
        for (Map.Entry<Long, DataSetSchema> entry : chatQueryContext.getSemanticSchema()
                .getDataSetSchemaMap().entrySet()) {
            Long dataSetId = entry.getKey();
            if (!CollectionUtils.isEmpty(requestedDataSets)
                    && !requestedDataSets.contains(dataSetId)) {
                continue;
            }

            List<SchemaElement> terms = new ArrayList<>(entry.getValue().getTerms());

            if (CollectionUtils.isEmpty(terms)) {
                continue;
            }

            List<TermMatchCandidate> candidates = collectCandidates(queryText, terms);
            if (CollectionUtils.isEmpty(candidates)) {
                continue;
            }

            Map<Long, TermMatchCandidate> bestCandidateByTermId = new HashMap<>();
            for (TermMatchCandidate candidate : candidates) {
                Long termId = candidate.getTerm().getId();
                TermMatchCandidate exist = bestCandidateByTermId.get(termId);
                if (Objects.isNull(exist) || candidate.getScore() > exist.getScore() || (Objects
                        .equals(candidate.getScore(), exist.getScore())
                        && candidate.getMatchedText().length() > exist.getMatchedText().length())) {
                    bestCandidateByTermId.put(termId, candidate);
                }
            }

            List<TermMatchCandidate> sortedCandidates = bestCandidateByTermId.values().stream()
                    .sorted(Comparator.comparingDouble(TermMatchCandidate::getScore).reversed()
                            .thenComparing((TermMatchCandidate candidate) -> candidate
                                    .getMatchedText().length(), Comparator.reverseOrder()))
                    .limit(MAX_TERM_MATCH_SIZE).collect(Collectors.toList());


            for (TermMatchCandidate candidate : sortedCandidates) {
                SchemaElementMatch schemaElementMatch = SchemaElementMatch.builder()
                        .element(candidate.getTerm()).word(candidate.getMatchedText())
                        .detectWord(candidate.getMatchedText())
                        .frequency(BaseWordBuilder.DEFAULT_FREQUENCY)
                        .similarity(candidate.getScore()).build();
                addToSchemaMap(chatQueryContext.getMapInfo(), dataSetId, schemaElementMatch);
            }

            log.info("term match mapper datasetId:{}, matched terms:{}", dataSetId,
                    sortedCandidates.stream().map(candidate -> candidate.getTerm().getName())
                            .collect(Collectors.toList()));
        }
    }

    private List<TermMatchCandidate> collectCandidates(String queryText,
            List<SchemaElement> terms) {
        String normalizedQueryText = queryText.toLowerCase();
        String normalizedCompactQuery = normalizeCompact(normalizedQueryText);
        Map<String, TermMatchCandidate> candidates = new LinkedHashMap<>();

        for (SchemaElement term : terms) {
            if (Objects.isNull(term) || !SchemaElementType.TERM.equals(term.getType())) {
                continue;
            }
            collectOneCandidate(candidates, term, term.getName(), normalizedQueryText,
                    normalizedCompactQuery, true);
            if (!CollectionUtils.isEmpty(term.getAlias())) {
                for (String alias : term.getAlias()) {
                    collectOneCandidate(candidates, term, alias, normalizedQueryText,
                            normalizedCompactQuery, false);
                }
            }
        }
        return new ArrayList<>(candidates.values());
    }

    private void collectOneCandidate(Map<String, TermMatchCandidate> candidates, SchemaElement term,
            String phrase, String normalizedQueryText, String normalizedCompactQuery,
            boolean fromName) {
        String normalizedPhrase = normalize(phrase);
        if (StringUtils.isBlank(normalizedPhrase)) {
            return;
        }
        double score = calculateScore(normalizedQueryText, normalizedCompactQuery, normalizedPhrase,
                fromName);
        if (score <= 0D) {
            return;
        }
        String candidateKey = term.getId() + "#" + normalizedPhrase;
        TermMatchCandidate candidate = new TermMatchCandidate(term, phrase, score);
        TermMatchCandidate exist = candidates.get(candidateKey);
        if (Objects.isNull(exist) || exist.getScore() < candidate.getScore()) {
            candidates.put(candidateKey, candidate);
        }
    }

    private double calculateScore(String normalizedQueryText, String normalizedCompactQuery,
            String normalizedPhrase, boolean fromName) {
        if (normalizedQueryText.equals(normalizedPhrase)) {
            return fromName ? 1D : 0.98D;
        }
        if (normalizedQueryText.contains(normalizedPhrase)) {
            return fromName ? 0.96D : 0.92D;
        }

        String compactPhrase = normalizeCompact(normalizedPhrase);
        if (StringUtils.isNotBlank(compactPhrase)
                && normalizedCompactQuery.contains(compactPhrase)) {
            return fromName ? 0.9D : 0.88D;
        }

        double similarity = EditDistanceUtils.getSimilarity(normalizedQueryText, normalizedPhrase);
        if (similarity >= FUZZY_TERM_MATCH_THRESHOLD) {
            return fromName ? similarity * 0.9 : similarity * 0.85;
        }
        return 0D;
    }

    private String normalize(String text) {
        return StringUtils.trimToEmpty(text).toLowerCase();
    }

    private String normalizeCompact(String text) {
        return normalize(text).replaceAll("\\s+", "");
    }

    @Getter
    @AllArgsConstructor
    private static class TermMatchCandidate {
        private SchemaElement term;
        private String matchedText;
        private double score;
    }
}
