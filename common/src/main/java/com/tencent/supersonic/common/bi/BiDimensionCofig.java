package com.tencent.supersonic.common.bi;

import java.util.List;
import lombok.Data;

@Data
public class BiDimensionCofig {
    
    private String name;

    private List<String> values;
    
    private String defaultValue;
    
}
