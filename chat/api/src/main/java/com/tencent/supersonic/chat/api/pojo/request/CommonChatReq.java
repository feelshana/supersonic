package com.tencent.supersonic.chat.api.pojo.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CommonChatReq {

    /**
     * type 1: 报表申请 2: 取数申请,3:订阅报表
     */
    @NotNull(message = "type 不能为空")
    private Integer type;

    /**
     * description 报表申请描述，取数的传入sql即可
     */
    @NotBlank(message = "description 不能为空")
    private String description;


    /**
     * 最后一次拒绝原因
     */
    private String lastReason;

    /**
     * 当为报表申请时候传入
     */
    private String where;
}
