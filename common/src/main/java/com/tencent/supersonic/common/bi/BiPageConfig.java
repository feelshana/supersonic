package com.tencent.supersonic.common.bi;

import java.util.List;
import lombok.Data;

@Data
public class BiPageConfig {

    private String isGroupBy;
    
    private List<BiDimensionCofig> dimensionConfigs;
    
}
