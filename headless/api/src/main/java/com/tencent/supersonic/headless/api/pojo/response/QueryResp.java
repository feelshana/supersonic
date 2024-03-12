package com.tencent.supersonic.headless.api.pojo.response;

import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import lombok.Data;
import java.util.Date;
import java.util.List;


@Data
public class QueryResp {

    private Long questionId;
    private Date createTime;
    private Long chatId;
    private Integer score;
    private String feedback;
    private String queryText;
    private QueryResult queryResult;
    private List<SemanticParseInfo> parseInfos;

}