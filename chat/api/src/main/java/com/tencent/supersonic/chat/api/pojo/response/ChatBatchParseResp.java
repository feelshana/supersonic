package com.tencent.supersonic.chat.api.pojo.response;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 批量查询响应。
 * <p>
 * 非合并模式：使用 results（各子任务结果文本）。 合并模式：使用 finalResults（合并后最终文本）和 structuredResult（结构化展示文本）。
 */
@Data
public class ChatBatchParseResp implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 每个子任务的执行结果文本（[SUCCESS]csv 或 [FAILED]msg），格式与 Dify 迭代输出一致 */
    private List<String> results;

    /** 合并模式：合并后的最终结果文本（各子任务用 "---" 分隔），merge=false 时为 null */
    private String finalResults;

    /** 合并模式：合并后的结构化文本（### 子任务N: 描述\n查询结果:...），merge=false 时为 null */
    private String structuredResult;
}
