package com.tencent.supersonic.chat.server.agent;

import lombok.Data;

@Data
public class AgentDataSetInfoDTO {

    private Integer agentId;
    private String description;
    // agent的name，给AgentScope侧作为description标题用
    private String agentName;
    // 原getAgentDataSetInfo返回的文本内容
    private String dataSetInfo;

}
