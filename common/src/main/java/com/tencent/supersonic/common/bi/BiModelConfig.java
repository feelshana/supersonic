package com.tencent.supersonic.common.bi;

import lombok.Data;
import java.util.List;

@Data
public class BiModelConfig {
    private String dbType;
    private String dataSourceId;
    private String dataSourceName;
    private Integer state;
    private Integer createModelType;
    private String modelId;
    private String modelName;
    private String querySql;
    
    private List<BiModelItem> customs;
    private List<BiModelItem> dimensions;
    private List<BiModelItem> measures;
    private List<BiModelParam> sqlConditionParams;
    private List<BiTable> tables;
    
}