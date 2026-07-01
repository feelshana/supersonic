package dev.langchain4j.model.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek synchronous (non-streaming) chat model with thinking mode support. Uses Java 11+
 * HttpClient to bypass langchain4j/openai4j limitations. Supports DeepSeek V4 Pro thinking
 * parameter (enabled/disabled).
 */
@Slf4j
public class DeepSeekChatModel implements ChatLanguageModel {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final Double temperature;
    private final long timeoutSeconds;
    /** "disabled" or "enabled" */
    private final String thinkingType;
    private final int thinkingBudgetTokens;
    private final String reasoningEffort;
    private final Integer maxTokens;

    @Builder
    public DeepSeekChatModel(String baseUrl, String apiKey, String modelName, Double temperature,
            Long timeoutSeconds, String thinkingType, Integer thinkingBudgetTokens,
            String reasoningEffort, Integer maxTokens) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.temperature = temperature != null ? temperature : 0.0;
        this.timeoutSeconds = timeoutSeconds != null ? timeoutSeconds : 60L;
        this.thinkingType = thinkingType;
        this.thinkingBudgetTokens = thinkingBudgetTokens != null ? thinkingBudgetTokens : 8000;
        this.reasoningEffort = reasoningEffort != null ? reasoningEffort : "high";
        this.maxTokens = maxTokens;
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        try {
            String requestBody = buildRequestBody(messages);
            log.debug("DeepSeekChatModel request body: {}", requestBody);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();

            HttpRequest request =
                    HttpRequest.newBuilder().uri(URI.create(baseUrl + "/chat/completions"))
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(timeoutSeconds))
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody)).build();

            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException(
                        "DeepSeek API error " + response.statusCode() + ": " + response.body());
            }

            String content = parseContent(response.body());
            return Response.from(AiMessage.from(content));

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("DeepSeekChatModel generate failed", e);
        }
    }

    private String parseContent(String responseBody) {
        try {
            Map<?, ?> json = MAPPER.readValue(responseBody, Map.class);
            List<?> choices = (List<?>) json.get("choices");
            if (choices == null || choices.isEmpty()) {
                return "";
            }
            Map<?, ?> message = (Map<?, ?>) ((Map<?, ?>) choices.get(0)).get("message");
            if (message == null) {
                return "";
            }
            Object content = message.get("content");
            return content instanceof String ? (String) content : "";
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse DeepSeek response: " + responseBody, e);
        }
    }

    private String buildRequestBody(List<ChatMessage> messages) {
        try {
            List<Map<String, String>> msgList = new ArrayList<>();
            for (ChatMessage msg : messages) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("role", roleOf(msg.type()));
                m.put("content", msg.text());
                msgList.add(m);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", modelName);
            body.put("messages", msgList);
            body.put("stream", false);

            if (maxTokens != null) {
                body.put("max_tokens", maxTokens);
            }

            if (thinkingType != null) {
                Map<String, Object> thinking = new LinkedHashMap<>();
                thinking.put("type", thinkingType);
                if ("enabled".equals(thinkingType)) {
                    thinking.put("budget_tokens", thinkingBudgetTokens);
                    // V4 thinking mode requires reasoning_effort (high/max)
                    body.put("reasoning_effort", reasoningEffort);
                    // thinking mode ignores temperature, but set to 1 for compatibility
                    body.put("temperature", 1.0);
                } else {
                    // disabled: use configured temperature
                    body.put("temperature", temperature);
                }
                body.put("thinking", thinking);
            } else {
                body.put("temperature", temperature);
            }

            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build DeepSeek request body", e);
        }
    }

    private String roleOf(ChatMessageType type) {
        switch (type) {
            case SYSTEM:
                return "system";
            case AI:
                return "assistant";
            case USER:
            default:
                return "user";
        }
    }
}
