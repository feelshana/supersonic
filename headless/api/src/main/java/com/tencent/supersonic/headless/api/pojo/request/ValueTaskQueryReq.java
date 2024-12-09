package com.tencent.supersonic.headless.api.pojo.request;

import javax.validation.constraints.NotNull;

import com.tencent.supersonic.common.pojo.PageBaseReq;
import lombok.Data;

import java.util.List;

/**
 * @author: kanedai
 * @date: 2024/11/24
 */
@Data
public class ValueTaskQueryReq extends PageBaseReq {

    @NotNull
    private Long itemId;

    private List<String> taskStatusList;

    private String key;
}