package com.tencent.supersonic.chat.server.service.impl;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import com.tencent.supersonic.chat.server.service.BiAgentService;
import com.tencent.supersonic.chat.server.service.BiAgentTaskService;
import com.tencent.supersonic.chat.server.task.BiAgentTask;
import com.tencent.supersonic.common.bi.BiAgentConfig;

@Service
public class BiAgentTaskServiceImpl implements BiAgentTaskService {

    @Autowired
    private BiAgentService biAgentService;
    
    @Autowired
    @Qualifier("commonExecutor")
    private ThreadPoolExecutor commonExecutor;
    
    @Override
    public void addBiAgentTask(BiAgentConfig config) {
        BiAgentTask task = new BiAgentTask(biAgentService, config);
        commonExecutor.execute(task);
    }

}
