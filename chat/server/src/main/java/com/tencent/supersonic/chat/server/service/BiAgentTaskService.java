package com.tencent.supersonic.chat.server.service;

import com.tencent.supersonic.common.bi.BiAgentConfig;

public interface BiAgentTaskService {

    void addBiAgentTask(BiAgentConfig config);

    void dispatchPendingTasks();

    void markTimeoutRunningTasks();

    void cleanHistoryNonFailedTasks();

    void cleanHistoryFailedTasks();

}
