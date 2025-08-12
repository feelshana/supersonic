package com.tencent.supersonic.common.bi;

import lombok.Data;

@Data
public class BiModelItem {
    private String id;
    private String databaseName;
    private String tableName;
    private String tableAliasName;
    private String columnName;
    private String columnId;
    private String columnActualName;
    private String isLook;
    private String format;
    private String name;
    private String colNum;
    private Integer columnType;
    private Integer originTableType;
    private String description;
    private String databaseTableColumnName;
    private Integer type;
    private String aggregationType;
}