package com.tencent.supersonic.common.bi;

import lombok.Data;

import java.util.List;

@Data
public class BiAgentConfig {

    private Integer agentId;

    private String reportId;

    private BiModelConfig model;

    private BiDataSource dataSource;

    private BiPageConfig pageConfig;

    private List<String> admins;

    private List<String> viewers;

}
