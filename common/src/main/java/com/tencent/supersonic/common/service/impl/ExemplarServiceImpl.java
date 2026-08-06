package com.tencent.supersonic.common.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.tencent.supersonic.common.config.EmbeddingConfig;
import com.tencent.supersonic.common.pojo.Text2SQLExemplar;
import com.tencent.supersonic.common.service.EmbeddingService;
import com.tencent.supersonic.common.service.ExemplarService;
import com.tencent.supersonic.common.util.JsonUtil;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.RetrieveQuery;
import dev.langchain4j.store.embedding.RetrieveQueryResult;
import dev.langchain4j.store.embedding.TextSegmentConvert;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;

@Service
@Slf4j
@Order(0)
public class ExemplarServiceImpl implements ExemplarService, CommandLineRunner {

    private static final String SYS_EXEMPLAR_FILE = "s2-exemplar.json";

    private TypeReference<List<Text2SQLExemplar>> valueTypeRef =
            new TypeReference<List<Text2SQLExemplar>>() {};

    private final ObjectMapper objectMapper = JsonUtil.INSTANCE.getObjectMapper();

    /**
     * 系统兜底 FewShot 示例，启动时从 s2-exemplar.json 加载到内存。 当向量召回失败时，直接返回这些兜底示例，避免整个查询流程中断。
     */
    private List<Text2SQLExemplar> sysExemplars = Lists.newArrayList();

    @Autowired
    private EmbeddingConfig embeddingConfig;

    @Autowired
    private EmbeddingService embeddingService;

    public void storeExemplar(String collection, Text2SQLExemplar exemplar) {
        Metadata metadata = Metadata
                .from(JsonUtil.toMap(JsonUtil.toString(exemplar), String.class, Object.class));
        TextSegment segment = TextSegment.from(exemplar.getQuestion(), metadata);
        TextSegmentConvert.addQueryId(segment, exemplar.getQuestion());

        embeddingService.addQuery(collection, Lists.newArrayList(segment));
    }

    public void removeExemplar(String collection, Text2SQLExemplar exemplar) {
        Metadata metadata = Metadata
                .from(JsonUtil.toMap(JsonUtil.toString(exemplar), String.class, Object.class));
        TextSegment segment = TextSegment.from(exemplar.getQuestion(), metadata);
        TextSegmentConvert.addQueryId(segment, exemplar.getQuestion());

        embeddingService.deleteQuery(collection, Lists.newArrayList(segment));
    }

    public List<Text2SQLExemplar> recallExemplars(String query, int num) {
        String collection = embeddingConfig.getText2sqlCollectionName();
        return recallExemplars(collection, query, num);
    }

    public List<Text2SQLExemplar> recallExemplars(String collection, String query, int num) {
        try {
            List<Text2SQLExemplar> exemplars = Lists.newArrayList();
            RetrieveQuery retrieveQuery =
                    RetrieveQuery.builder().queryTextsList(Lists.newArrayList(query)).build();
            List<RetrieveQueryResult> results =
                    embeddingService.retrieveQuery(collection, retrieveQuery, num);
            results.forEach(ret -> {
                ret.getRetrieval().forEach(r -> {
                    Text2SQLExemplar tmp = // 传递相似度，可以作为样本筛选的依据
                            JsonUtil.mapToObject(r.getMetadata(), Text2SQLExemplar.class);
                    tmp.setSimilarity(r.getSimilarity());
                    exemplars.add(tmp);
                });
            });
            return exemplars;
        } catch (Exception e) {
            log.warn("[RAG] 向量召回失败，返回系统兜底示例，collection={}，query={}，num={}", collection, query, num,
                    e);
            return Lists.newArrayList(sysExemplars);
        }
    }

    @Override
    public void run(String... args) {
        loadSysExemplars();
    }

    public void loadSysExemplars() {
        try {
            ClassPathResource resource = new ClassPathResource(SYS_EXEMPLAR_FILE);
            InputStream inputStream = resource.getInputStream();
            List<Text2SQLExemplar> exemplars = objectMapper.readValue(inputStream, valueTypeRef);
            // 同时保留到内存，作为向量召回失败时的兜底
            sysExemplars = Lists.newArrayList(exemplars);
            String collection = embeddingConfig.getText2sqlCollectionName();
            exemplars.stream().forEach(e -> storeExemplar(collection, e));
            log.info("[RAG] 系统兜底 FewShot 示例已加载，数量: {}", sysExemplars.size());
        } catch (Exception e) {
            log.error("Failed to load system exemplars", e);
        }
    }
}
