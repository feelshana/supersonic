
package com.tencent.supersonic.chat.server.rest;

import javax.annotation.Resource;

import com.alibaba.fastjson.JSON;
import com.tencent.supersonic.chat.api.pojo.request.CommonChatReq;
import com.tencent.supersonic.chat.server.service.CommonChatService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
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
    public SseEmitter streamChat(@RequestBody @Valid CommonChatReq input) {
        SseEmitter emitter = new SseEmitter(120000L);

        commonChatService.streamChat(input).subscribe(
                chunk -> {
                    try {
                        Map<String, String> wrapper = Map.of("data", chunk);
                        String jsonData = JSON.toJSONString(wrapper);
                        emitter.send(SseEmitter.event().data(jsonData, MediaType.APPLICATION_JSON));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                },
                error -> {
                    log.error("SSE stream error", error);
                    emitter.completeWithError(error);
                },
                () -> {
                    try {
                        // 发送结束事件
                        emitter.send(SseEmitter.event().name("complete").data(""));
                        emitter.complete();
                        log.info("SSE stream completed");
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                }
        );

        emitter.onTimeout(() -> {
            log.warn("SSE stream timeout");
            emitter.complete();
        });

        return emitter;
    }


    /**
     * 流式对话（SSE）
     */
    @PostMapping(value = "/normalChat")
    public String normalChat(String whereSql) {
        return commonChatService.normalChat(whereSql);

    }

    @GetMapping(value = "/recall")
    public List<Map<String, Object>> retrieveQuery(String query, String modelId, Integer topK) {
        return commonChatService.retrieveQuery(query, modelId, topK);
    }

    @GetMapping(value = "/find")
    public List<Map<String, Object>> findQuery(String query, String modelId, String dimId) {
        return commonChatService.findQuery(query, modelId, dimId);
    }
}
