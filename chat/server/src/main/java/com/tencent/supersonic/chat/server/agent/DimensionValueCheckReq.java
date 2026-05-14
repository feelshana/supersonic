package com.tencent.supersonic.chat.server.agent;

import lombok.Data;

@Data
public class DimensionValueCheckReq {

    private Integer agentId;

    private String queryText;

    private String dataSetInfo;

    private String startDate;

    private String endDate;
}
