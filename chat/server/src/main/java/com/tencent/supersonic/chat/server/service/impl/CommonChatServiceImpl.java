package com.tencent.supersonic.chat.server.service.impl;

import com.amazonaws.services.dynamodbv2.xspec.S;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.supersonic.chat.api.pojo.request.ChatExecuteReq;
import com.tencent.supersonic.chat.api.pojo.request.CommonChatReq;
import com.tencent.supersonic.chat.server.config.CrabConfig;
import com.tencent.supersonic.chat.server.executor.PlainTextExecutor;
import com.tencent.supersonic.chat.server.service.ChatManageService;
import com.tencent.supersonic.chat.server.service.CommonChatService;
import com.tencent.supersonic.common.config.ChatModel;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.common.pojo.FileInfo;
import com.tencent.supersonic.common.service.ChatModelService;
import com.tencent.supersonic.common.util.MiguApiUrlUtils;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.provider.ModelProvider;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.Resource;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用对话服务实现
 */
@Slf4j
@Service
public class CommonChatServiceImpl implements CommonChatService {

    @Value("${s2.bi.model-id:1}")
    private Integer modelId;

    /**
     * 系统提示词模板：根据 type 和 description 生成对应内容
     */
    private static final String REPORT_USER_PROMPT = """
            
            当前任务类型：%s
            任务描述：%s
            where条件：%s
            生成一个20个字的理由
            注意：理由要简洁明了，避免使用专业术语，类型要求如下：
                 取数申请理由：用户提供的任务描述为sql执行语句，需要将sql语句提炼一下生成理由，where条件不需要参考
                 报表申请理由：用户提供的任务描述为报表生成需求，where条件是查询数据的条件描述，需要将需求提炼一下生成理由
            """;


    private static final String TYPE_REPORT = "报表申请理由";
    private static final String TYPE_DATA = "取数申请理由";





    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final CrabConfig crabConfig;
    @Autowired
    public CommonChatServiceImpl(WebClient.Builder webClientBuilder, ObjectMapper objectMapper,
                               CrabConfig crabConfig, ChatQueryServiceImpl chatQueryService,
                               ChatManageService chatManageService) {
        this.objectMapper = objectMapper;
        this.crabConfig = crabConfig;
        this.webClient = webClientBuilder.baseUrl(crabConfig.getHost()).build();
    }



//    @Override
//    public Flux<String> streamChat(CommonChatReq input) {
//        // 1. 构建提示词
//        String typeName = input.getType() == 1 ? TYPE_REPORT : TYPE_DATA;
//        String whereClause = input.getWhere() != null ? input.getWhere() : "无";
//        String prompt = String.format(REPORT_USER_PROMPT, typeName, input.getDescription(), whereClause);
//
//        log.info("生成申请理由的prompt: {}", prompt);
//        // 2. 获取流式模型
//        StreamingChatLanguageModel streamChatModel;
//        try {
//            ChatModel chatModel = chatModelService.getChatModel(modelId);
//            ChatModelConfig config = chatModel.getConfig();
//            streamChatModel = ModelProvider.getStreamingChatModel(config);
//        } catch (Exception e) {
//            log.error("无法获取到流式模型的配置", e);
//            throw new RuntimeException("未正确获大模配置，无法使用自动生成申请理由");
//        }
//
//        // 3. 创建流式解析器
//
//        GenerateApplyReasonStreamExtractor generateApplyReasonStreamExtractor = AiServices.create(GenerateApplyReasonStreamExtractor.class, streamChatModel);
//
//
//        return generateApplyReasonStreamExtractor.generateApplyReasonStream(prompt);
//    }

    public interface GenerateApplyReasonStreamExtractor {
        /**
         * 生成申请理由
         *
         * @param userMessage 用户消息
         * @return 生成申请理由 流式结果
         */
        @SystemMessage(value = "你是一个申请理由生成助手。理由要简洁明了，避免使用专业术语")
        Flux<String> generateApplyReasonStream(String userMessage);
    }


    @Override
    public SseEmitter chat(CommonChatReq input) {

        // 创建SSE发射器（180秒超时）
        SseEmitter emitter = new SseEmitter(180_000L);
        // 1. 构建提示词
        String typeName = input.getType() == 1 ? TYPE_REPORT : TYPE_DATA;
        String whereClause = input.getWhere() != null ? input.getWhere() : "无";
        String prompt = String.format(REPORT_USER_PROMPT, typeName, input.getDescription(), whereClause);

        log.info("生成申请理由的prompt: {}", prompt);

        String urlPath = buildSignedUrl();
        String requestBody = buildRequestBody(prompt);
        StringBuilder contentAccumulator = new StringBuilder();

        // 调用DeepSeek API
        webClient.post().uri(urlPath)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(requestBody).retrieve()
                .onStatus(HttpStatusCode::isError,
                        response -> Mono.error(new RuntimeException("API request failed")))
                .bodyToFlux(JsonNode.class).doOnSubscribe(sub -> log.info("Subscription started"))
                .onBackpressureBuffer(crabConfig.getOnBackpressureBuffer())
                .delayElements(Duration.ofMillis(crabConfig.getDelayElements()))
                .flatMap(response -> processStreamResponse(response, contentAccumulator))
                .doOnCancel(() -> log.warn("Downstream cancelled"))
                .subscribe(chunk -> sendSseChunk(emitter, chunk),
                        error -> handleStreamError(emitter, error),
                        () -> completeStream(emitter, contentAccumulator));
        return emitter;
    }

    private void completeStream(SseEmitter emitter, StringBuilder contentAccumulator) {
        emitter.complete();
        log.info("Stream completed successfully, full content: {}", contentAccumulator.toString());
    }

    private void handleStreamError(SseEmitter emitter, Throwable error) {
        log.error("Stream processing error", error);
        emitter.completeWithError(error);
    }
    private void sendSseChunk(SseEmitter emitter, String chunk) {
        try {
            emitter.send(SseEmitter.event().data(chunk));
        } catch (IOException e) {
            log.error("Failed to send SSE chunk", e);
            throw new RuntimeException(e);
        }
    }
    private Flux<String> processStreamResponse(JsonNode response, StringBuilder accumulator) {
        try {
            // 错误处理
            if (response.has("errorMessage")) {
                String errorMsg = response.path("errorMessage").asText();
                log.error("API error: {}", errorMsg);
                return Flux.error(new RuntimeException(errorMsg));
            }

            JsonNode body = response.path("body");
            // 处理结束标志
            if (body.has("endFlag") && "1".equals(body.path("endFlag").asText())) {
                ObjectNode result = objectMapper.createObjectNode();
                result.put("type", "endFlag");
                result.put("message", "1");
                return Flux.just(objectMapper.writeValueAsString(result));
            }
            // 累积content
            if (body.has("content")) {
                String content = body.path("content").asText();
                if (!content.isEmpty()) {
                    accumulator.append(content);
                }
            }

            // 构建返回给前端的JSON
            ObjectNode result = objectMapper.createObjectNode();
            if (body.has("reasonContent") && !body.path("reasonContent").isNull()) {
                String reasonContent = body.path("reasonContent").asText();
                if (!reasonContent.isEmpty()) {
                    result.put("type", "reason");
                    result.put("message", reasonContent);
                    return Flux.just(objectMapper.writeValueAsString(result));
                }
            }

            if (body.has("content") && !body.path("content").isNull()) {
                String content = body.path("content").asText();
                if (!content.isEmpty()) {
                    result.put("type", "answer");
                    result.put("message", content);
                    return Flux.just(objectMapper.writeValueAsString(result));
                }
            }

            return Flux.empty();
        } catch (JsonProcessingException e) {
            return Flux.error(new RuntimeException("JSON processing error", e));
        }
    }

    private String buildRequestBody(String prompt) {
        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("serviceName", crabConfig.getDsServiceName());
            request.put("serviceType", crabConfig.getDsServiceType());
            request.put("requestId", UUID.randomUUID().toString());
            request.put("sessionId", UUID.randomUUID().toString());

            ObjectNode params = request.putObject("params");
            params.set("messages", buildMessagesArray(prompt));
            params.put("model", crabConfig.getDsModel(crabConfig.getDsServiceName()));
            params.put("stream", true);

            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
    private String buildSignedUrl() {
        Map<String, Object> map = new HashMap<>();
        return MiguApiUrlUtils.doSignature(crabConfig.getDeepseekUrl(), "post", map, crabConfig.getAppId(), crabConfig.getSecretKey());
    }


    private JsonNode buildMessagesArray(String message) {
        ArrayNode messages = objectMapper.createArrayNode();
        messages.add(objectMapper.createObjectNode().put("role", "user").put("content", message));
        return messages;
    }
}
