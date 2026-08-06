package dev.langchain4j.store.embedding;

import com.tencent.supersonic.common.config.EmbeddingStoreParameterConfig;
import com.tencent.supersonic.common.pojo.EmbeddingStoreConfig;
import com.tencent.supersonic.common.util.ContextUtils;
import dev.langchain4j.chroma.spring.ChromaEmbeddingStoreFactory;
import dev.langchain4j.inmemory.spring.InMemoryEmbeddingStoreFactory;
import dev.langchain4j.milvus.spring.MilvusEmbeddingStoreFactory;
import dev.langchain4j.opensearch.spring.OpenSearchEmbeddingStoreFactory;
import dev.langchain4j.pgvector.spring.PgvectorEmbeddingStoreFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class EmbeddingStoreFactoryProvider {
    protected static final Map<EmbeddingStoreConfig, EmbeddingStoreFactory> factoryMap =
            new ConcurrentHashMap<>();

    public static EmbeddingStoreFactory getFactory() {
        EmbeddingStoreParameterConfig parameterConfig =
                ContextUtils.getBean(EmbeddingStoreParameterConfig.class);
        EmbeddingStoreConfig config = parameterConfig.convert();
        log.info("[PERFORMANCE] getFactory config hash:{}, factoryMap size:{}, containsKey:{}",
                config.hashCode(), factoryMap.size(), factoryMap.containsKey(config));
        return getFactory(config);
    }

    public static EmbeddingStoreFactory getFactory(EmbeddingStoreConfig embeddingStoreConfig) {
        if (embeddingStoreConfig == null
                || StringUtils.isBlank(embeddingStoreConfig.getProvider())) {
            return ContextUtils.getBean(EmbeddingStoreFactory.class);
        }
        if (EmbeddingStoreType.CHROMA.name().equalsIgnoreCase(embeddingStoreConfig.getProvider())) {
            return factoryMap.computeIfAbsent(embeddingStoreConfig,
                    storeConfig -> new ChromaEmbeddingStoreFactory(storeConfig));
        }
        if (EmbeddingStoreType.MILVUS.name().equalsIgnoreCase(embeddingStoreConfig.getProvider())) {
            return factoryMap.computeIfAbsent(embeddingStoreConfig,
                    storeConfig -> new MilvusEmbeddingStoreFactory(storeConfig));
        }
        if (EmbeddingStoreType.PGVECTOR.name()
                .equalsIgnoreCase(embeddingStoreConfig.getProvider())) {
            return factoryMap.computeIfAbsent(embeddingStoreConfig,
                    storeConfig -> new PgvectorEmbeddingStoreFactory(storeConfig));
        }
        if (EmbeddingStoreType.IN_MEMORY.name()
                .equalsIgnoreCase(embeddingStoreConfig.getProvider())) {
            return factoryMap.computeIfAbsent(embeddingStoreConfig,
                    storeConfig -> new InMemoryEmbeddingStoreFactory(storeConfig));
        }
        if (EmbeddingStoreType.OPENSEARCH.name()
                .equalsIgnoreCase(embeddingStoreConfig.getProvider())) {
            return factoryMap.computeIfAbsent(embeddingStoreConfig,
                    storeConfig -> new OpenSearchEmbeddingStoreFactory(storeConfig));
        }
        throw new RuntimeException("Unsupported EmbeddingStoreFactory provider: "
                + embeddingStoreConfig.getProvider());
    }
}
