package com.tencent.supersonic.chat.server.service;

import com.tencent.supersonic.chat.api.pojo.request.EasybiPureDataReq;
import com.tencent.supersonic.chat.api.pojo.response.EasybiPureDataResp;

public interface EasybiDataService {

    /**
     * 查询 EasyBI 原始数据，并在 token 预算内截断后返回。
     */
    EasybiPureDataResp fetchPureData(EasybiPureDataReq req);
}
