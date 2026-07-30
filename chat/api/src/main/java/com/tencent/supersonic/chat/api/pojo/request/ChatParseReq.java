package com.tencent.supersonic.chat.api.pojo.request;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.request.QueryFilters;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatParseReq {
    private String queryText;
    private Integer chatId;
    private Integer agentId;
    private Long dataSetId;
    private User user;
    private QueryFilters queryFilters;
    private boolean saveAnswer = true;
    private boolean disableLLM = false;
    private Long queryId;
    private SemanticParseInfo selectedParse;
    /**
     * 查询模式，"SIMPLE" 表示简易模式：跳过 MAPPING 向量召回环节， 直接将完整 Schema 传给 LLM 生成 SQL，适用于问题已高度结构化的外部调用场景。
     */
    private String queryType;
    /**
     * SQL执行失败重试时的错误反馈信息（失败SQL+错误原因）。 独立于 queryText 传递，不影响 MAPPING 向量召回、多轮改写和聊天历史，
     * 最终注入 PARSING 阶段 LLM 提示词，引导重新生成正确的 SQL。
     */
    private String errorFeedback;
}
