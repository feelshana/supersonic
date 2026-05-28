package com.tencent.supersonic.chat.server.service;

import com.tencent.supersonic.chat.api.pojo.request.CommonChatReq;
import dev.langchain4j.store.embedding.Retrieval;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 通用对话服务接口
 */
public interface CommonChatService {


    Flux<String> streamChat(CommonChatReq input);

    String normalChat(String whereSql);

    List<Map<String, Object>> retrieveQuery(String query, String modelId, Integer topK);

    List<Map<String, Object>> findQuery(String query, String modelId, String dimId);

    Flux<String> flamesStreamChat(CommonChatReq input) throws Exception;

    String flamesChat(String whereSql) throws Exception;
}
