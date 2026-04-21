package com.tencent.supersonic.chat.server.service;

import com.tencent.supersonic.chat.api.pojo.request.ChatExecuteReq;
import com.tencent.supersonic.chat.api.pojo.request.CommonChatReq;
import com.tencent.supersonic.common.pojo.User;
import dev.langchain4j.service.SystemMessage;
import jakarta.validation.Valid;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

/**
 * 通用对话服务接口
 */
public interface CommonChatService {


    Flux<String> streamChat(CommonChatReq input);

}
