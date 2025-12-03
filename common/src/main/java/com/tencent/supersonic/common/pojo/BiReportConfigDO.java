package com.tencent.supersonic.common.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("bi_report_config")
public class BiReportConfigDO {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private String reportId;

    private String dimRelation;

    // 1:多级分类；2:同级维度
    private Integer type;
}
