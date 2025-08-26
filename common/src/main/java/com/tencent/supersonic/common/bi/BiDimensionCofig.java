package com.tencent.supersonic.common.bi;

import lombok.Data;

import java.util.List;

@Data
public class BiDimensionCofig {

    private String name;

    private List<String> values;

    private List<String> defaultValues;

}
