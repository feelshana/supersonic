package com.tencent.supersonic.chat.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * LLM_NATIVE 模式的启用配置。
 *
 * <p>
 * 该模式绕过语义层直接由 LLM 生成物理 SQL，是否启用属于"部署期决策"而非"每次请求决策"： 同一个 BI 报表 Agent
 * 建好之后就固定用一种模式。因此这里用配置文件维护，改配置即可灰度， 不需要调用方改传参，也不需要动数据库表结构。
 *
 * <p>
 * 配置示例（application.yaml）：
 *
 * <pre>
 * s2:
 *   llm-native:
 *     # 显式启用的 agentId 白名单，逗号分隔
 *     agent-ids: 43,58
 *     # 是否对所有 BI 报表 Agent（isBi=1）启用，为 true 时上面的白名单仍然生效（取并集）
 *     enable-all-bi-agents: false
 * </pre>
 */
@Component
public class LlmNativeParserConfig {

    @Value("${s2.llm-native.agent-ids:}")
    private List<Integer> agentIds;

    @Value("${s2.llm-native.enable-all-bi-agents:true}")
    private boolean enableAllBiAgents;

    public List<Integer> getAgentIds() {
        return agentIds == null ? Collections.emptyList() : agentIds;
    }

    public boolean isEnableAllBiAgents() {
        return enableAllBiAgents;
    }
}
