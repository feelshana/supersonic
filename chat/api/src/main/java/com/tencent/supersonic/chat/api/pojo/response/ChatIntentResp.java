package com.tencent.supersonic.chat.api.pojo.response;

import com.tencent.supersonic.chat.api.pojo.enums.ChatIntentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 意图分类接口响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatIntentResp {

    /**
     * 分类结果：DIRECT_REPLY / DATA_INTERPRETATION / DATA_QUERY
     */
    private ChatIntentType type;

    /**
     * type = DIRECT_REPLY 时的直接回复文本
     */
    private String directReply;

    /**
     * type = DATA_INTERPRETATION 或 DATA_QUERY 时的会话上下文
     */
    private ChatIntentContext chatContext;

    /**
     * LLM 原始意图输出：1=数据解读，0=问数。调试或日志使用。
     */
    private Integer intentType;

    /**
     * 分类/校验原因说明，便于问题排查。
     */
    private String reason;
}
