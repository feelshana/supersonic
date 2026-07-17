package com.tencent.supersonic.chat.server.rest;

import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.chat.api.pojo.request.ChatIntentReq;
import com.tencent.supersonic.chat.api.pojo.response.ChatIntentResp;
import com.tencent.supersonic.chat.server.service.ChatIntentService;
import com.tencent.supersonic.common.pojo.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/chat/intent", "/openapi/chat/intent"})
public class ChatIntentController {

    @Autowired
    private ChatIntentService chatIntentService;

    @PostMapping("/classify")
    public ChatIntentResp classify(@RequestBody ChatIntentReq req, HttpServletRequest request,
            HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        return chatIntentService.classify(req, user);
    }
}
