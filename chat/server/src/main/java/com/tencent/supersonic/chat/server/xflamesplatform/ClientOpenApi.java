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


    public Flux<String> chat(String content) {
        return Flux.create(sink -> {
            AgentStreamingLanguageModel model = null;
            try {
                model = AgentStreamingLanguageModel.builder()
                        .baseUrl(baseUrl)
                        .appId(appId)
                        .appSecret(appSecret)
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
                        log.debug("response={}", response);
                        for (ChatTextData text : response.getPayload().getChoices().getText()) {
                            if (ContentType.TEXT == text.contentType) {
                                sink.next(text.getContent());
                            }
                        }
                        if (response.getHeader().getCode() != 0
                                || (response.getPayload().getChoices() != null
                                    && response.getPayload().getChoices().isFinish())) {
                            log.debug("the last one message");
                            sink.complete();
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        log.error(t.getMessage());
                        sink.error(t);
                    }

                    @Override
                    public void onCompleted() {
                        log.debug("onComplete");
                        sink.complete();
                    }
                });
            } catch (Exception e) {
                sink.error(new RuntimeException(e));
            }
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    public String chatAsString(String content) throws Exception {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        StringBuilder responseContent = new StringBuilder();

        AgentStreamingLanguageModel model = AgentStreamingLanguageModel.builder()
                .baseUrl(baseUrl)
                .appId(appId)
                .appSecret(appSecret)
                .assistantCode(assistantCode)
                .build();

        model.generate(content, new StreamingResponseHandler<AgentResPayload>() {
            @Override
            public void onResponse(FlamesResponse<AgentResPayload> response) {
                log.debug("response={}", response);
                for (ChatTextData text : response.getPayload().getChoices().getText()) {
                    if (ContentType.TEXT == text.contentType) {
                        responseContent.append(text.getContent());
                    }
                }
                if (response.getHeader().getCode() != 0
                        || (response.getPayload().getChoices() != null
                            && response.getPayload().getChoices().isFinish())) {
                    log.debug("the last one message");
                    log.debug("receive message content:{}", responseContent);
                    countDownLatch.countDown();
                }
            }

            @Override
            public void onError(Throwable t) {
                log.error(t.getMessage());
                countDownLatch.countDown();
            }

            @Override
            public void onCompleted() {
                log.debug("onComplete");
                countDownLatch.countDown();
            }
        });

        countDownLatch.await();
        return responseContent.toString();
    }
}
