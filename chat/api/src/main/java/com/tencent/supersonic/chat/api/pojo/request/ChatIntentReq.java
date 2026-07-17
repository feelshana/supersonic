package com.tencent.supersonic.chat.api.pojo.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Dify 工作流进入 askdata 分支后，调用该接口进行参数校验、意图分类和会话准备。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatIntentReq {

    /**
     * 用户当前提问文本
     */
    private String queryText;

    /**
     * 外部助手返回的路由类型。非 askdata 时直接返回 DIRECT_REPLY。
     */
    private String route;

    /**
     * 报表 ID
     */
    private String reportId;

    /**
     * 报表名称
     */
    private String reportName;

    /**
     * 仪表盘 ID
     */
    private String dashboardId;

    /**
     * 仪表盘名称
     */
    private String dashboardName;

    /**
     * Agent ID
     */
    private Integer agentId;

    /**
     * 查询 limit，业务上同时作为“是否已训练/是否有权限”的标志位
     */
    private Integer limit;

    /**
     * 当前 Dify 会话 ID
     */
    private String conversationId;

    /**
     * Dify 会话变量中保存的上一次会话 ID，用于判断是否需要创建新 chat
     */
    private String lastConversationId;

    /**
     * Dify 会话变量中保存的上一次 chatId，同一会话时可直接复用
     */
    private Long lastChatId;
}
