package com.tencent.supersonic.chat.server.service;

import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.agent.AgentDataSetInfoDTO;
import com.tencent.supersonic.chat.server.agent.TermDTO;
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

    /**
     * 专为维度值校验服务提供的数据集信息 与 getAgentDataSetInfo 的区别：维度信息中包含字段名（bizName，即实际数据库列名）
     */
    String getAgentDataSetInfoForValidation(Integer agentId, String queryText, User user);

    // 接收多个agentId，返回每个agent的数据集描述信息列表
    // queryType=detail 时返回完整信息（含dataSetInfo），不传或queryType=brief 时只返回基本信息
    List<AgentDataSetInfoDTO> getRedSeaDataSetInfo(List<Integer> agentIds, String queryText,
            String queryType, User user);

    /**
     * 获取指定agent下数据集中的术语信息
     * 
     * @param agentId agent ID
     * @param termName 术语名称（可选），不传则返回全部术语
     * @param alias 别名关键词（可选），传入则筛选别名中包含该值的术语
     * @param user 当前用户
     * @return 术语信息列表
     */
    List<TermDTO> getAgentTerms(Integer agentId, String termName, String alias, User user);
}
