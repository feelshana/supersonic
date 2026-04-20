package com.tencent.supersonic.chat.server.service;

import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.agent.AgentDataSetInfoDTO;
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

    String getAgentDataSetInfo(Integer agentId, String queryText, User user);

    // 接收多个agentId，返回每个agent的数据集描述信息列表
    // queryType=detail 时返回完整信息（含dataSetInfo），不传或queryType=brief 时只返回基本信息
    List<AgentDataSetInfoDTO> getRedSeaDataSetInfo(List<Integer> agentIds, String queryText,
            String queryType, User user);
}
