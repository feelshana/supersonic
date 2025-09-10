package com.tencent.supersonic.headless.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.headless.server.persistence.dataobject.AppDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.BiReportConfigDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BiReportConfigMapper extends BaseMapper<BiReportConfigDO> {
}
