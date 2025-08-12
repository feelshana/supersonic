package com.tencent.supersonic.chat.server.task;

import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.service.BiAgentService;
import com.tencent.supersonic.common.bi.BiAgentConfig;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BiAgentTask implements Runnable {

    private BiAgentService service;
    
    private BiAgentConfig config;
    
    public BiAgentTask(BiAgentService service, BiAgentConfig config) {
        this.service = service;
        this.config = config;
    }

    @Override
    public void run() {
        try {
            log.info("开始执行BI助手任务：{}", config.getReportId());
            Agent agent = service.createBiAgent(config);
            log.info("执行BI助手任务完成：{}", config.getReportId());
            service.biAgentCallback(agent, config);
            log.info("BI助手回调完成：{}", config.getReportId());
        } catch (Exception e) {
            log.error("创建BI助手失败", e);
        }
    }
    
}
