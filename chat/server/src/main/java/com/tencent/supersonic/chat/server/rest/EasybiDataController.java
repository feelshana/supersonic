package com.tencent.supersonic.chat.server.rest;

import com.tencent.supersonic.chat.api.pojo.request.EasybiPureDataReq;
import com.tencent.supersonic.chat.api.pojo.response.EasybiPureDataResp;
import com.tencent.supersonic.chat.server.service.EasybiDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * EasyBI 数据相关接口。 供 Dify 工作流调用，完成取数、token 计算与截断，不执行 LLM 数据解读。
 */
@RestController
@RequestMapping({"/api/chat/easybi", "/openapi/chat/easybi"})
public class EasybiDataController {

    @Autowired
    private EasybiDataService easybiDataService;

    /**
     * 获取 EasyBI 原始数据，并按 token 预算截断后返回。 同时支持返回已组装好的 prompt，可直接注入 Dify LLM 节点上下文。
     */
    @PostMapping("/getPureData4ChatBI")
    public EasybiPureDataResp getPureData4ChatBI(@RequestBody EasybiPureDataReq req) {
        return easybiDataService.fetchPureData(req);
    }
}
