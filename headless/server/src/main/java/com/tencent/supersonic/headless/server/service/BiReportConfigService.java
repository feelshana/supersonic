package com.tencent.supersonic.headless.server.service;

import com.tencent.supersonic.headless.server.persistence.dataobject.BiReportConfigDO;

import java.util.List;

public interface BiReportConfigService {

    public List<BiReportConfigDO> getBiReportConfig(String reportId);

    public List<String> getDimRelations(String reportId);
}
