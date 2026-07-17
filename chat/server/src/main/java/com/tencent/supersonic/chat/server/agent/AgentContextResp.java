package com.tencent.supersonic.chat.server.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 合并 getAgentDataSetInfo + getAgentTerms 的统一响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentContextResp {

    /**
     * 数据集信息文本（维度列表、指标列表、术语说明、当前日期、维度值映射等）。
     */
    private String dataSetInfo;

    /**
     * 术语列表（结构化）。
     */
    private List<TermDTO> terms;

    /**
     * 当前日期，格式：yyyy年MM月dd日。
     */
    private String currentDate;
}
