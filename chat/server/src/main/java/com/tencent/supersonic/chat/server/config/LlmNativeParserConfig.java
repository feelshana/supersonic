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
 * 判定顺序（未显式传 queryType 时）：排除名单优先，其次看全量开关。 典型用法：{@code enable-all-bi-agents: true} 全量启用，个别不兼容的报表把
 * agentId 配进排除名单，继续走原 NL2SQLParser。
 *
 * <p>
 * 配置示例（application.yaml）：
 *
 * <pre>
 * s2:
 *   llm-native:
 *     # 是否对所有 BI 报表 Agent（isBi=1）启用
 *     enable-all-bi-agents: true
 *     # 排除名单：配置的 agentId 不启用本模式，回退原 NL2SQLParser，逗号分隔
 *     exclude-agent-ids: 43,58
 * </pre>
 */
@Component
public class LlmNativeParserConfig {

    @Value("${s2.llm-native.exclude-agent-ids:}")
    private List<Integer> excludeAgentIds;

    @Value("${s2.llm-native.enable-all-bi-agents:true}")
    private boolean enableAllBiAgents;

    public List<Integer> getExcludeAgentIds() {
        return excludeAgentIds == null ? Collections.emptyList() : excludeAgentIds;
    }

    public boolean isEnableAllBiAgents() {
        return enableAllBiAgents;
    }
}
