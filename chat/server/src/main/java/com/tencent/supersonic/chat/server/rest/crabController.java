package com.tencent.supersonic.chat.server.rest;

import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.chat.api.pojo.request.ChatExecuteReq;
import com.tencent.supersonic.chat.server.config.CrabConfig;
import com.tencent.supersonic.chat.server.service.DeepSeekService;
import com.tencent.supersonic.common.util.MiguApiUrlUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/chat/crab")
public class crabController {
    @Autowired
    private DeepSeekService deepSeekService;

    @PostMapping(value = "/deepSeekStream")
    public SseEmitter streamChat(@RequestBody ChatExecuteReq chatExecuteReq,
            HttpServletRequest request, HttpServletResponse response) {
        chatExecuteReq.setUser(UserHolder.findUser(request, response));
        return deepSeekService.streamChat(chatExecuteReq);
    }

    @PostMapping(value = "/stopStream")
    public Boolean stopStream(@RequestBody ChatExecuteReq chatExecuteReq) {
        deepSeekService.stopStream(chatExecuteReq.getQueryId());
        return true;
    }

    @Autowired
    private CrabConfig crabConfig;
    @GetMapping(value = "/testSignature")
    public void test() {
        Map<String, Object> map = new HashMap<>();
        String urlpath = MiguApiUrlUtils.doSignature(crabConfig.getDeepseekUrl(), "post", map,
                "kuaesoba", "e66a04b5e44748ebbeb119adc047f02d");
        System.out.println(urlpath);
    }
}
