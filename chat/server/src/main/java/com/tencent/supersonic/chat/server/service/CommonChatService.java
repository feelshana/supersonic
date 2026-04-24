package com.tencent.supersonic.chat.server.service;

import com.amazonaws.services.dynamodbv2.xspec.S;
import com.tencent.supersonic.chat.api.pojo.request.ChatExecuteReq;
import com.tencent.supersonic.chat.api.pojo.request.CommonChatReq;
import com.tencent.supersonic.common.pojo.User;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.store.embedding.Retrieval;
import jakarta.validation.Valid;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 通用对话服务接口
 */
public interface CommonChatService {


    Flux<String> streamChat(CommonChatReq input);

    String normalChat(String whereSql);

    List<Retrieval> retrieveQuery(String query,String modelId);

    List<Retrieval> findQuery(String query, String modelId,String dimId);
}
