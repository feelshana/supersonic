package com.tencent.supersonic.headless.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tencent.supersonic.common.pojo.BiReportConfigDO;
import com.tencent.supersonic.headless.server.persistence.mapper.BiReportConfigMapper;
import com.tencent.supersonic.headless.server.service.BiReportConfigService;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class BiReportConfigServiceImpl extends ServiceImpl<BiReportConfigMapper, BiReportConfigDO>
        implements BiReportConfigService {
    @Override
    public List<BiReportConfigDO> getBiReportConfig(String reportId) {
        QueryWrapper<BiReportConfigDO> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(BiReportConfigDO::getReportId, reportId);
        return this.list(queryWrapper);

    }

    @Override
    public List<String> getDimRelations(String reportId) {
        List<BiReportConfigDO> reportConfigDOList = this.getBiReportConfig(reportId);
        if (CollectionUtils.isNotEmpty(reportConfigDOList)) {
            BiReportConfigDO biReportConfigDO = reportConfigDOList.get(0);
            return Arrays.asList(biReportConfigDO.getDimRelation().split(","));
        }
        return null;
    }
}
