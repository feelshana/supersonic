package com.tencent.supersonic.chat.server.agent;

import lombok.Data;

import java.util.List;

@Data
public class DimensionValueCheckResp {

    /**
     * 是否所有查询条件都已确认（可以直接查询）
     */
    private boolean allConfirmed;

    /**
     * 是否需要用户手动确认（有 candidates 类型的不确定项）
     */
    private boolean needUserConfirm;

    /**
     * 未匹配到任何候选值的维度值（完全查不到）
     */
    private String unmatchedValue;

    /**
     * 维度值校验明细列表
     */
    private List<DimValueCheckItem> checkItems;

    /**
     * 简要汇总信息
     */
    private String enrichedContext;

    /**
     * 已确认的信息汇总（指标、维度、维度值、日期范围等） 当 allConfirmed=true 时，将此信息传给下一个节点，帮助理解用户意图
     */
    private String confirmedInfo;

    /**
     * 不确定的信息汇总（哪个维度不确定 + 用户输入值 + 候选选项） 当 needUserConfirm=true 时，将此信息放入人工介入节点让用户选择
     */
    private String unconfirmedInfo;

    /**
     * 需要用户确认的维度数量（即 needConfirm=true 的 checkItems 数量） 用于 Dify
     * 代码节点判断走哪个条件分支（0=无候选值、1/2/3=对应数量的人工介入节点）
     */
    private int candidateCount;

    @Data
    public static class DimValueCheckItem {

        /**
         * 维度名称，如"产品"、"省份"
         */
        private String dimensionName;

        /**
         * 用户输入的值
         */
        private String userInput;

        /**
         * 维度字段名（bizName）
         */
        private String bizName;

        /**
         * 校验级别：llm_confirmed / llm_candidates / llm_not_matched
         */
        private String checkLevel;

        /**
         * 匹配到的值（confirmed 时有效）
         */
        private String matchedValue;

        /**
         * 候选值列表（candidates 时有效，供用户选择）
         */
        private List<String> candidates;

        /**
         * 是否需要用户确认
         */
        private boolean needConfirm;
    }
}
