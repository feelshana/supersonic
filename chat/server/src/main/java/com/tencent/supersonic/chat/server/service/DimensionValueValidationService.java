package com.tencent.supersonic.chat.server.service;

import com.tencent.supersonic.chat.server.agent.DimensionValueCheckReq;
import com.tencent.supersonic.chat.server.agent.DimensionValueCheckResp;
import com.tencent.supersonic.common.pojo.User;

public interface DimensionValueValidationService {

    /**
     * 校验用户问题中的维度值是否明确
     *
     * @param req 校验请求
     * @param user 用户信息
     * @return 校验结果
     */
    DimensionValueCheckResp validate(DimensionValueCheckReq req, User user);
}
