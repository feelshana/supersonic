package com.tencent.supersonic.common.bi;

import lombok.Data;

@Data
public class BiTable {
    private String tableId;
    private String tableName;
    private String tableAliasName;
    private Integer tableType;
    private String databaseName;
}