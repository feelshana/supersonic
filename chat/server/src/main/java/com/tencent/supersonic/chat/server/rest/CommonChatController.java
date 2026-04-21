
package com.tencent.supersonic.chat.server.rest;

import javax.annotation.Resource;

import com.alibaba.fastjson.JSON;
import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.chat.api.pojo.request.ChatExecuteReq;
import com.tencent.supersonic.chat.api.pojo.request.CommonChatReq;
import com.tencent.supersonic.chat.server.service.CommonChatService;
import com.tencent.supersonic.common.pojo.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import net.minidev.json.JSONUtil;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 通用对话 Controller
 */
@RestController
@RequestMapping({"/api/chat/common"})
@Slf4j
public class CommonChatController {

    @Resource
    private CommonChatService commonChatService;

    /**
     * 流式对话（SSE）
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(@RequestBody @Valid CommonChatReq input) {

        return commonChatService.streamChat(input)
            .map(chunk -> {
                Map<String, String> wrapper = Map.of("data", chunk);
                String jsonData = JSON.toJSONString(wrapper);
              return   ServerSentEvent.<String>builder()
                        .data(jsonData)
                        .build();
            })
                .concatWith(Mono.just(
                        // 发送结束事件
                        ServerSentEvent.<String>builder()
                                .event("complete")
                                .data("")
                                .build()
                ))
            .doOnComplete(() -> log.info("SSE stream completed"))
            .doOnError(error -> log.error("SSE stream error", error));
    }
}
