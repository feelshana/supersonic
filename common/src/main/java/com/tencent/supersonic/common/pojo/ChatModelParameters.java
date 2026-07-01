package com.tencent.supersonic.common.pojo;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import dev.langchain4j.provider.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ChatModelParameters {
    private static final String MODULE_NAME = "对话模型配置";

    public static final Parameter CHAT_MODEL_PROVIDER =
            new Parameter("provider", ModelProvider.DEMO_CHAT_MODEL.getProvider(), "接口协议", "",
                    "list", MODULE_NAME, getCandidateProviders());

    public static final Parameter CHAT_MODEL_BASE_URL =
            new Parameter("baseUrl", ModelProvider.DEMO_CHAT_MODEL.getBaseUrl(), "BaseUrl", "",
                    "string", MODULE_NAME, null, getBaseUrlDependency());

    public static final Parameter CHAT_MODEL_NAME =
            new Parameter("modelName", ModelProvider.DEMO_CHAT_MODEL.getModelName(), "ModelName",
                    "", "string", MODULE_NAME, null, getModelNameDependency());

    public static final Parameter CHAT_MODEL_API_KEY = new Parameter("apiKey", "", "ApiKey", "",
            "password", MODULE_NAME, null, getApiKeyDependency());

    public static final Parameter CHAT_MODEL_API_VERSION = new Parameter("apiVersion", "2024-02-01",
            "ApiVersion", "", "string", MODULE_NAME, null, getApiVersionDependency());

    public static final Parameter CHAT_MODEL_TEMPERATURE =
            new Parameter("temperature", "0.0", "Temperature", "", "slider", MODULE_NAME);

    public static final Parameter CHAT_MODEL_TIMEOUT =
            new Parameter("timeOut", "60", "超时时间(秒)", "", "number", MODULE_NAME);

    public static final Parameter CHAT_MODEL_THINKING_TYPE =
            new Parameter("thinkingType", "disabled", "思考模式", "", "list", MODULE_NAME,
                    Lists.newArrayList("disabled", "enabled"), getThinkingTypeDependency());

    public static final Parameter CHAT_MODEL_THINKING_BUDGET =
            new Parameter("thinkingBudgetTokens", "8000", "思考Token预算", "", "number", MODULE_NAME,
                    null, getThinkingBudgetDependency());

    public static final Parameter CHAT_MODEL_REASONING_EFFORT =
            new Parameter("reasoningEffort", "high", "思考强度", "", "list", MODULE_NAME,
                    Lists.newArrayList("high", "max"), getReasoningEffortDependency());

    public static List<Parameter> getParameters() {
        return Lists.newArrayList(CHAT_MODEL_PROVIDER, CHAT_MODEL_BASE_URL, CHAT_MODEL_API_KEY,
                CHAT_MODEL_NAME, CHAT_MODEL_API_VERSION, CHAT_MODEL_TEMPERATURE,
                CHAT_MODEL_TIMEOUT, CHAT_MODEL_THINKING_TYPE, CHAT_MODEL_THINKING_BUDGET,
                CHAT_MODEL_REASONING_EFFORT);
    }

    private static List<String> getCandidateProviders() {
        return Lists.newArrayList(OpenAiModelFactory.PROVIDER, OllamaModelFactory.PROVIDER,
                DifyModelFactory.PROVIDER, DeepSeekModelFactory.PROVIDER);
    }

    private static List<Parameter.Dependency> getBaseUrlDependency() {
        return getDependency(CHAT_MODEL_PROVIDER.getName(), getCandidateProviders(),
                ImmutableMap.of(OpenAiModelFactory.PROVIDER, OpenAiModelFactory.DEFAULT_BASE_URL,
                        OllamaModelFactory.PROVIDER, OllamaModelFactory.DEFAULT_BASE_URL,
                        DifyModelFactory.PROVIDER, DifyModelFactory.DEFAULT_BASE_URL,
                        DeepSeekModelFactory.PROVIDER, DeepSeekModelFactory.DEFAULT_BASE_URL));
    }

    private static List<Parameter.Dependency> getApiKeyDependency() {
        return getDependency(CHAT_MODEL_PROVIDER.getName(),
                Lists.newArrayList(OpenAiModelFactory.PROVIDER, DifyModelFactory.PROVIDER,
                        DeepSeekModelFactory.PROVIDER),
                ImmutableMap.of(OpenAiModelFactory.PROVIDER,
                        ModelProvider.DEMO_CHAT_MODEL.getApiKey(), DifyModelFactory.PROVIDER,
                        ModelProvider.DEMO_CHAT_MODEL.getApiKey(),
                        DeepSeekModelFactory.PROVIDER,
                        ModelProvider.DEMO_CHAT_MODEL.getApiKey()));
    }

    private static List<Parameter.Dependency> getApiVersionDependency() {
        return getDependency(CHAT_MODEL_PROVIDER.getName(),
                Lists.newArrayList(OpenAiModelFactory.PROVIDER), ImmutableMap
                        .of(OpenAiModelFactory.PROVIDER, OpenAiModelFactory.DEFAULT_API_VERSION));
    }

    private static List<Parameter.Dependency> getModelNameDependency() {
        return getDependency(CHAT_MODEL_PROVIDER.getName(), getCandidateProviders(),
                ImmutableMap.of(OpenAiModelFactory.PROVIDER, OpenAiModelFactory.DEFAULT_MODEL_NAME,
                        OllamaModelFactory.PROVIDER, OllamaModelFactory.DEFAULT_MODEL_NAME,
                        DifyModelFactory.PROVIDER, DifyModelFactory.DEFAULT_MODEL_NAME,
                        DeepSeekModelFactory.PROVIDER, DeepSeekModelFactory.DEFAULT_MODEL_NAME));
    }

    private static List<Parameter.Dependency> getEndpointDependency() {
        return getDependency(CHAT_MODEL_PROVIDER.getName(),
                Lists.newArrayList(OpenAiModelFactory.PROVIDER), ImmutableMap
                        .of(OpenAiModelFactory.PROVIDER, OpenAiModelFactory.DEFAULT_MODEL_NAME));
    }

    private static List<Parameter.Dependency> getEnableSearchDependency() {
        return getDependency(CHAT_MODEL_PROVIDER.getName(),
                Lists.newArrayList(OpenAiModelFactory.PROVIDER),
                ImmutableMap.of(OpenAiModelFactory.PROVIDER, "false"));
    }

    private static List<Parameter.Dependency> getSecretKeyDependency() {
        return getDependency(CHAT_MODEL_PROVIDER.getName(),
                Lists.newArrayList(OpenAiModelFactory.PROVIDER), ImmutableMap.of(
                        OpenAiModelFactory.PROVIDER, ModelProvider.DEMO_CHAT_MODEL.getApiKey()));
    }

    private static List<Parameter.Dependency> getThinkingTypeDependency() {
        return getDependency(CHAT_MODEL_PROVIDER.getName(),
                Lists.newArrayList(DeepSeekModelFactory.PROVIDER),
                ImmutableMap.of(DeepSeekModelFactory.PROVIDER, "disabled"));
    }

    private static List<Parameter.Dependency> getReasoningEffortDependency() {
        return getDependency(CHAT_MODEL_PROVIDER.getName(),
                Lists.newArrayList(DeepSeekModelFactory.PROVIDER),
                ImmutableMap.of(DeepSeekModelFactory.PROVIDER, "high"));
    }

    private static List<Parameter.Dependency> getThinkingBudgetDependency() {
        return getDependency(CHAT_MODEL_PROVIDER.getName(),
                Lists.newArrayList(DeepSeekModelFactory.PROVIDER),
                ImmutableMap.of(DeepSeekModelFactory.PROVIDER, "8000"));
    }

    private static List<Parameter.Dependency> getDependency(String dependencyParameterName,
            List<String> includesValue, Map<String, String> setDefaultValue) {

        Parameter.Dependency.Show show = new Parameter.Dependency.Show();
        show.setIncludesValue(includesValue);

        Parameter.Dependency dependency = new Parameter.Dependency();
        dependency.setName(dependencyParameterName);
        dependency.setShow(show);
        dependency.setSetDefaultValue(setDefaultValue);
        List<Parameter.Dependency> dependencies = new ArrayList<>();
        dependencies.add(dependency);
        return dependencies;
    }
}
