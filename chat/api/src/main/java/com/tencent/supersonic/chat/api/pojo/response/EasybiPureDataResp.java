package com.tencent.supersonic.chat.api.pojo.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * EasyBI 原始数据查询响应。 字段命名尽量与 Dify Python 节点输出保持一致，方便工作流直接消费。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EasybiPureDataResp {

    /**
     * 0：成功（可能被截断）；1：调用/解析失败；2：查无数据
     */
    private int success;

    /**
     * 截断后的数据文本（JSON Lines 格式）。
     */
    private String result;

    /**
     * 已组装好的 prompt（如果请求传了 queryText）。 Dify 可直接把该字段注入 LLM 节点上下文。
     */
    private String prompt;

    /**
     * 实际返回的数据行数（不含表头）。
     */
    private int number;

    /**
     * 实际 token 数（按 OpenAI cl100k_base 口径）。
     */
    private int token;

    /**
     * 是否发生过截断。
     */
    private boolean truncated;

    /**
     * 错误信息，success=1 时返回。
     */
    private String error;
}
