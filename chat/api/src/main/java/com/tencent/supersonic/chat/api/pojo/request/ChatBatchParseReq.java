package com.tencent.supersonic.chat.api.pojo.request;

import com.tencent.supersonic.common.pojo.User;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 批量查询请求，用于 Dify 工作流中并发执行多个子任务。
 * <p>
 * 非合并模式（第一次查询）：只传 agentId/chatId/queryTexts，服务端并发执行后返回各子任务结果。
 * 合并模式（重试场景）：额外传 merge=true + originalResults + indexMap，服务端并发执行重试子任务后，
 * 按 indexMap 替换/追加到原始结果中，直接返回合并后的最终结果。
 */
@Data
public class ChatBatchParseReq implements Serializable {
    private static final long serialVersionUID = 1L;

    private Integer agentId;
    private Integer chatId;

    /** 子任务列表，每个元素是一个原子级查询描述 */
    private List<String> queryTexts;

    /** 查询模式，同 parseAndExecute 的 queryType（normal/simple/super_simple），不传走正常模式 */
    private String queryType;

    private User user;

    // ====== 合并模式字段（重试场景用，第一次查询不传） ======

    /** 是否启用合并：true=Java侧合并后返回finalResults/structuredResult，false=只返回results */
    private boolean merge;

    /** 原始查询结果（带 [SUCCESS]/[FAILED] 前缀的文本列表，来自首次迭代输出） */
    private List<String> originalResults;

    /** 原始子任务描述列表 */
    private List<String> originalQueryDetails;

    /**
     * 重试子任务对应原始序号的映射，如 ["2","new"] 表示第1个重试任务替换原序号2，第2个是新增。
     * 长度必须与 queryTexts 一致。
     */
    private List<String> indexMap;
}
