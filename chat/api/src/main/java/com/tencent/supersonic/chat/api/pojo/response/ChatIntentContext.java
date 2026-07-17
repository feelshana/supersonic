package com.tencent.supersonic.chat.api.pojo.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 问数/解读所需的会话上下文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatIntentContext {

    /**
     * Supersonic 侧 chatId。数据解读场景可能为 null。
     */
    private Long chatId;

    /**
     * 当前 Dify 会话 ID，原样返回便于工作流对齐。
     */
    private String conversationId;

    /**
     * 当前日期，格式：yyyy年MM月dd日，用于后续 prompt 组装。
     */
    private String currentDate;
}
