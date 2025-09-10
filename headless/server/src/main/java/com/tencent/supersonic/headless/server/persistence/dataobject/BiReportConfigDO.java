package com.tencent.supersonic.headless.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("bi_report_config")
public class BiReportConfigDO {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private String reportId;

    private String dimRelation;
}
