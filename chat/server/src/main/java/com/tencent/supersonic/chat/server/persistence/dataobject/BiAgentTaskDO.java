package com.tencent.supersonic.chat.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.sql.Date;
import java.util.Objects;

@Data
@TableName("s2_bi_agent_task")
public class BiAgentTaskDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String reportId;

    private Date taskDay;

    private String status;

    private String config;

    private String errorMsg;

    private Integer agentId;

    private String agentName;

    private java.util.Date createdAt;

    private java.util.Date updatedAt;

    private java.util.Date startedAt;

    private java.util.Date finishedAt;

    public String safeErrorMsg(int maxLen) {
        if (Objects.isNull(errorMsg)) {
            return null;
        }
        if (errorMsg.length() <= maxLen) {
            return errorMsg;
        }
        return errorMsg.substring(0, maxLen);
    }
}
