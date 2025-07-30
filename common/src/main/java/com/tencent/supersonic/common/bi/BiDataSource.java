package com.tencent.supersonic.common.bi;

import lombok.Data;

@Data
public class BiDataSource {
    private String id;
    private Integer seq;
    private Integer origin;
    private String orgId;
    private String name;
    private String description;
    private Integer isDefault;
    private String type;
    private String ip;
    private String port;
    private String defaultDatabase;
    private String userName;
    private String password;
    private Integer connectionType;
    private String connectionUrl;
}