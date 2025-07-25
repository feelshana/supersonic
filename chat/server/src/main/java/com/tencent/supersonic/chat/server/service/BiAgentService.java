package com.tencent.supersonic.chat.server.service;

import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.common.bi.BiModelConfig;

public interface BiAgentService {
    
    Agent createBiAgent(BiModelConfig config) throws Exception;

}
