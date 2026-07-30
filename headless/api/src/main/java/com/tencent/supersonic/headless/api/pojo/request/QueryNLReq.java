package com.tencent.supersonic.headless.api.pojo.request;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.Text2SQLExemplar;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.Text2SQLType;
import com.tencent.supersonic.headless.api.pojo.QueryDataType;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.enums.MapModeEnum;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
public class QueryNLReq extends SemanticQueryReq implements Serializable {
    private Long queryId;
    private String queryText;
    private Set<Long> dataSetIds = Sets.newHashSet();
    private User user;
    private QueryFilters queryFilters;
    private boolean saveAnswer = true;
    private Text2SQLType text2SQLType = Text2SQLType.LLM_OR_RULE;
    private MapModeEnum mapModeEnum = MapModeEnum.STRICT;
    private QueryDataType queryDataType = QueryDataType.ALL;
    private Map<String, ChatApp> chatAppConfig;
    private List<Text2SQLExemplar> dynamicExemplars = Lists.newArrayList();
    private SemanticParseInfo contextParseInfo;
    private SemanticParseInfo selectedParseInfo;
    private boolean descriptionMapped;
    private Integer agentId;
    private String requestId = "";
    private List<String> segmentDimBizNames = new ArrayList<>();
    private List<String> excludeDefaultDimNames = new ArrayList<>();
    /**
     * 查询模式，"SIMPLE" 表示简易模式，透传自 ChatParseReq， 供下游 ChatWorkflowEngine / LLMRequestService 判断是否跳过
     * MAPPING。
     */
    private String queryType;
    /**
     * SQL执行失败重试时的错误反馈信息，透传自 ChatParseReq（BeanMapper 同名字段自动复制）， 最终由
     * LLMRequestService 注入 LLMReq 供 PARSING 阶段提示词使用。
     */
    private String errorFeedback;

    @Override
    public String toCustomizedString() {
        StringBuilder stringBuilder = new StringBuilder("{");
        stringBuilder.append("\"queryText\":").append(dataSetId);
        stringBuilder.append("\"dataSetId\":").append(dataSetId);
        stringBuilder.append("\"modelIds\":").append(modelIds);
        stringBuilder.append(",\"params\":").append(params);
        stringBuilder.append(",\"cacheInfo\":").append(cacheInfo);
        stringBuilder.append(",\"mapMode\":").append(mapModeEnum);
        stringBuilder.append(",\"dataType\":").append(queryDataType);
        stringBuilder.append('}');
        return stringBuilder.toString();
    }
}
