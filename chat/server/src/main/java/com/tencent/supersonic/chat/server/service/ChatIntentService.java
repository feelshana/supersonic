package com.tencent.supersonic.chat.server.service;

import com.tencent.supersonic.chat.api.pojo.request.ChatIntentReq;
import com.tencent.supersonic.chat.api.pojo.response.ChatIntentResp;
import com.tencent.supersonic.common.pojo.User;

public interface ChatIntentService {

    /**
     * 对 askdata 分支请求进行参数校验、意图分类和会话准备。
     *
     * @param req 请求参数
     * @param user 当前用户
     * @return 分类结果
     */
    ChatIntentResp classify(ChatIntentReq req, User user);
}
