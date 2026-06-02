package com.tencent.supersonic.chat.server.xflamesplatform;

import com.iflytek.flames.data.chat.agent.AgentResPayload;
import com.iflytek.flames.data.chat.base.ChatTextData;
import com.iflytek.flames.data.chat.base.ContentType;
import com.iflytek.flames.data.common.FlamesResponse;
import com.iflytek.flames.sdk.client.OpenApi;
import com.iflytek.flames.sdk.model.StreamingResponseHandler;
import com.iflytek.flames.sdk.model.agent.AgentStreamingLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

@Slf4j
@Component
public class ClientOpenApi extends OpenApi {

    @Value("${s2.flames.platform.base-url:}")
    private String baseUrl;

    @Value("${s2.flames.platform.app-id:}")
    private String appId;

    @Value("${s2.flames.platform.app-secret:}")
    private String appSecret;

    @Value("${s2.flames.platform.assistant-code:}")
    private String assistantCode;


    /** 打字机効果：每个字符/字符串片段之间的间隔（毫秒）。可按需提到配置。 */
    private static final long TYPING_INTERVAL_MS = 35L;

    public Flux<String> chat(String content) {
        // 1) 原始上游流：flames 返回的 chunk 往往是“一整句”
        Flux<String> rawChunks = Flux.create(sink -> {
            AgentStreamingLanguageModel model;
            try {
                model = AgentStreamingLanguageModel.builder()
                        .baseUrl(baseUrl)
                        .appId(appId)
                        .appSecret(appSecret)
                        .modelId("x-key")
                        .modelSource("x-source")
                        .assistantCode(assistantCode)
                        .build();
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                sink.error(new RuntimeException(e));
                return;
            }

            try {
                model.generate(content, new StreamingResponseHandler<AgentResPayload>() {
                    @Override
                    public void onResponse(FlamesResponse<AgentResPayload> response) {
                        log.info("[chat-stream] onResponse, response={}", response);
                        for (ChatTextData text : response.getPayload().getChoices().getText()) {
                            if (ContentType.TEXT == text.contentType) {
                                sink.next(text.getContent());
                            }
                        }
                        if (response.getHeader().getCode() != 0
                                || (response.getPayload().getChoices() != null
                                    && response.getPayload().getChoices().isFinish())) {
                            log.info("[chat-stream] receive last message, complete sink");
                            sink.complete();
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        log.error("[chat-stream] onError: {}", t.getMessage(), t);
                        sink.error(t);
                    }

                    @Override
                    public void onCompleted() {
                        log.info("[chat-stream] onCompleted");
                        sink.complete();
                    }
                });
            } catch (Exception e) {
                sink.error(new RuntimeException(e));
            }
        }, FluxSink.OverflowStrategy.BUFFER);

        // 2) 将每个大 chunk 拆成“字符粒度”，并以固定间隔向下游推送，营造打字机效果
        return rawChunks
                .concatMap(chunk -> Flux.fromIterable(splitToGraphemes(chunk))
                        .delayElements(Duration.ofMillis(TYPING_INTERVAL_MS)));
    }

    /**
     * 按 Unicode code point 拆分，避免 emoji / 补充平面字符被拆出乱码。
     * 如果只需ASCII中文，也可以直接 chunk.split("")。
     */
    private static List<String> splitToGraphemes(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(chunk.length());
        int i = 0;
        while (i < chunk.length()) {
            int cp = chunk.codePointAt(i);
            int charCount = Character.charCount(cp);
            result.add(chunk.substring(i, i + charCount));
            i += charCount;
        }
        return result;
    }

    public String chatAsString(String content) throws Exception {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        StringBuilder responseContent = new StringBuilder();

        AgentStreamingLanguageModel model = AgentStreamingLanguageModel.builder()
                .baseUrl(baseUrl)
                .appId(appId)
                .appSecret(appSecret)
                .modelId("x-key")
                .modelSource("x-source")
                .assistantCode(assistantCode)
                .build();

        model.generate(content, new StreamingResponseHandler<AgentResPayload>() {
            @Override
            public void onResponse(FlamesResponse<AgentResPayload> response) {
                log.info("[chat-sync] onResponse, response={}", response);
                    for (ChatTextData text : response.getPayload().getChoices().getText()) {
                    if (ContentType.TEXT == text.contentType) {
                        responseContent.append(text.getContent());
                    }
                }
                if (response.getHeader().getCode() != 0 || (response.getPayload().getChoices() != null && response.getPayload().getChoices().isFinish())) {
                    log.info("[chat-sync] receive full message content: {}", responseContent);
                    countDownLatch.countDown();
                }
            }

            @Override
            public void onError(Throwable t) {
                log.warn("[chat-sync] onError: {}", t.getMessage(), t);
                countDownLatch.countDown();
            }

            @Override
            public void onCompleted() {
                log.info("[chat-sync] onCompleted");
                countDownLatch.countDown();
            }
        });

        countDownLatch.await();
        return responseContent.toString();
    }
}
