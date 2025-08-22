package com.tencent.supersonic.headless.server.service;

import java.util.List;
import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.request.DictSingleTaskReq;
import com.tencent.supersonic.headless.api.pojo.request.DictValueReq;
import com.tencent.supersonic.headless.api.pojo.request.ValueTaskQueryReq;
import com.tencent.supersonic.headless.api.pojo.response.DictItemResp;
import com.tencent.supersonic.headless.api.pojo.response.DictTaskResp;
import com.tencent.supersonic.headless.api.pojo.response.DictValueDimResp;

/** Manage dictionary tasks */
public interface DictTaskService {
    Long addDictTask(DictSingleTaskReq taskReq, User user);

    Long deleteDictTask(DictSingleTaskReq taskReq, User user);

    Boolean dailyDictTask();

    DictTaskResp queryLatestDictTask(DictSingleTaskReq taskReq, User user);

    PageInfo<DictTaskResp> queryDictTask(ValueTaskQueryReq taskQueryReq, User user);

    PageInfo<DictValueDimResp> queryDictValue(DictValueReq dictValueReq, User user);

    String queryDictFilePath(DictValueReq dictValueReq, User user);

    void importDictData(DictItemResp dictItemResp, List<String> data, User user);
}
