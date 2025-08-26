package com.tencent.supersonic.common.bi;

import lombok.Data;

import java.util.List;

@Data
public class BiPageConfig {

    private String isGroupBy;

    private List<BiDimensionCofig> dimensionConfigs;

}
