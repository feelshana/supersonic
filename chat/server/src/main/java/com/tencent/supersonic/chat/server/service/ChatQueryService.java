package com.tencent.supersonic.chat.server.service;

import com.tencent.supersonic.chat.api.pojo.request.ChatBatchParseReq;
import com.tencent.supersonic.chat.api.pojo.request.ChatExecuteReq;
import com.tencent.supersonic.chat.api.pojo.request.ChatParseReq;
import com.tencent.supersonic.chat.api.pojo.request.ChatQueryDataReq;
import com.tencent.supersonic.chat.api.pojo.response.ChatBatchParseResp;
import com.tencent.supersonic.chat.api.pojo.response.ChatParseResp;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.request.DimensionValueReq;
import com.tencent.supersonic.headless.api.pojo.response.SearchResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

public interface ChatQueryService {

    List<SearchResult> search(ChatParseReq chatParseReq);

    ChatParseResp parse(ChatParseReq chatParseReq);

    QueryResult execute(ChatExecuteReq chatExecuteReq) throws Exception;

    SseEmitter streamExecute(ChatExecuteReq chatExecuteReq) throws Exception;

    QueryResult parseAndExecute(ChatParseReq chatParseReq);

    /**
     * 批量并发执行多个子任务，可选合并到原始结果中。
     * <p>
     * 非合并模式：只传 queryTexts，返回各子任务结果（results）。 合并模式：额外传 originalResults + indexMap，按序号替换/追加，返回
     * finalResults 和 structuredResult。
     */
    ChatBatchParseResp batchParseAndExecute(ChatBatchParseReq batchReq);

    Object queryData(ChatQueryDataReq chatQueryDataReq, User user) throws Exception;

    Object queryDimensionValue(DimensionValueReq dimensionValueReq, User user) throws Exception;

    QueryResult dataInterpret(ChatExecuteReq chatExecuteReq);

    SseEmitter streamParse(ChatParseReq chatParseReq);
}
