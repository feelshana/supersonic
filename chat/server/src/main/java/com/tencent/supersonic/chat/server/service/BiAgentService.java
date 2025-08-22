package com.tencent.supersonic.chat.server.service;

import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.common.bi.BiAgentConfig;

public interface BiAgentService {
    
    Agent createBiAgent(BiAgentConfig config) throws Exception;

    void biAgentCallback(Agent agent, BiAgentConfig config);

}
