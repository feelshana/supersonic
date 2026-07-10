package com.tencent.supersonic.chat.api.pojo.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * EasyBI 原始数据查询请求。 用于 Dify 工作流调用 Supersonic 获取 BI 报表原始数据，并由 Supersonic 完成 token 限制下的截断。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EasybiPureDataReq {

    /**
     * 报表 ID，会拼接在 URL 中：/report/data/{reportId}/getPureData4ChatBI
     */
    private String reportId;

    /**
     * EasyBI 会话信息，透传到 easyBiSession header
     */
    private String easyBiSession;

    /**
     * 请求体参数，会原样 POST 给 EasyBI 接口
     */
    private Map<String, Object> param;

    /**
     * 用户问题（可选），用于组装给 LLM 的 prompt。 如果为空，则响应中不返回 prompt 字段。
     */
    private String queryText;

    /**
     * 数据部分的最大 token 数（不含 prompt 自身）。 为空时默认 25000。
     */
    private Integer maxDataTokens;

    /**
     * 自定义 prompt 模板（可选），占位符：{{queryText}}、{{data}}
     */
    private String promptTemplate;
}
