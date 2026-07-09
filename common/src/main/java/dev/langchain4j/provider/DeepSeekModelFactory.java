package dev.langchain4j.provider;

import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.common.pojo.EmbeddingModelConfig;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.DeepSeekChatModel;
import dev.langchain4j.model.openai.DeepSeekStreamingChatModel;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

@Service
public class DeepSeekModelFactory implements ModelFactory, InitializingBean {

    public static final String PROVIDER = "DEEP_SEEK";
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    public static final String DEFAULT_MODEL_NAME = "deepseek-v4-flash";

    @Override
    public ChatLanguageModel createChatModel(ChatModelConfig modelConfig) {
        return DeepSeekChatModel.builder().baseUrl(modelConfig.getBaseUrl())
                .apiKey(modelConfig.keyDecrypt()).modelName(modelConfig.getModelName())
                .temperature(modelConfig.getTemperature()).timeoutSeconds(modelConfig.getTimeOut())
                .thinkingType(modelConfig.getThinkingType())
                .thinkingBudgetTokens(modelConfig.getThinkingBudgetTokens())
                .reasoningEffort(modelConfig.getReasoningEffort()).build();
    }

    @Override
    public EmbeddingModel createEmbeddingModel(EmbeddingModelConfig embeddingModel) {
        throw new UnsupportedOperationException(
                "DeepSeek does not provide embedding models, use another provider for embedding");
    }

    @Override
    public StreamingChatLanguageModel createStreamChatModel(ChatModelConfig modelConfig) {
        return DeepSeekStreamingChatModel.builder().baseUrl(modelConfig.getBaseUrl())
                .apiKey(modelConfig.keyDecrypt()).modelName(modelConfig.getModelName())
                .temperature(modelConfig.getTemperature()).timeoutSeconds(modelConfig.getTimeOut())
                .thinkingType(modelConfig.getThinkingType())
                .thinkingBudgetTokens(modelConfig.getThinkingBudgetTokens())
                .reasoningEffort(modelConfig.getReasoningEffort()).build();
    }

    @Override
    public void afterPropertiesSet() {
        ModelProvider.add(PROVIDER, this);
    }
}
