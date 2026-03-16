package com.tencent.supersonic.chat.server.task;

import com.tencent.supersonic.chat.server.service.BiAgentTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class BiAgentTaskDispatchScheduler {

    @Autowired
    private BiAgentTaskService biAgentTaskService;

    @Scheduled(fixedDelayString = "${s2.bi.agent.task.dispatch-fixed-delay-ms:3000}",
            initialDelayString = "${s2.bi.agent.task.dispatch-initial-delay-ms:10000}")
    public void dispatchPendingTasks() {
        try {
            biAgentTaskService.dispatchPendingTasks();
        } catch (Exception e) {
            log.error("分发BI训练任务异常", e);
        }
    }

    @Scheduled(fixedDelayString = "${s2.bi.agent.task.timeout-scan-fixed-delay-ms:60000}",
            initialDelayString = "${s2.bi.agent.task.timeout-scan-initial-delay-ms:30000}")
    public void markTimeoutRunningTasks() {
        try {
            biAgentTaskService.markTimeoutRunningTasks();
        } catch (Exception e) {
            log.error("处理超时RUNNING的BI训练任务异常", e);
        }
    }

    @Scheduled(cron = "${s2.bi.agent.task.clean-weekly-cron:0 30 3 ? * SUN}")
    public void cleanHistoryNonFailedTasks() {
        try {
            biAgentTaskService.cleanHistoryNonFailedTasks();
        } catch (Exception e) {
            log.error("清理历史非失败BI训练任务异常", e);
        }
    }


    @Scheduled(cron = "${s2.bi.agent.task.clean-monthly-cron:0 0 4 1 * ?}")
    public void cleanHistoryFailedTasks() {
        try {
            biAgentTaskService.cleanHistoryFailedTasks();
        } catch (Exception e) {
            log.error("清理历史失败BI训练任务异常", e);
        }
    }
}

