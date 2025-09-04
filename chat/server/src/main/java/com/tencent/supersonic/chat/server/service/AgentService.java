package com.tencent.supersonic.chat.server.service;

import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;

import java.util.List;

public interface AgentService {
    List<Agent> getAgents(User user, AuthType authType);

    List<Agent> getAgents();

    Agent createAgent(Agent agent, User user);

    Agent updateAgent(Agent agent, User user);

    Agent getAgent(Integer id);

    List<Agent> getAgentByName(String name);

    void deleteAgent(Integer id);

    Agent getAgentDetail(Integer agentId, User user);

    String getAgentPrompt(Integer agentId, String queryText, User user);
}
