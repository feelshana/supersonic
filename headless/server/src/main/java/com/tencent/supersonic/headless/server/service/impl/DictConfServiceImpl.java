package com.tencent.supersonic.headless.server.service.impl;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.StatusEnum;
import com.tencent.supersonic.common.pojo.enums.TypeEnums;
import com.tencent.supersonic.headless.api.pojo.request.DictItemFilter;
import com.tencent.supersonic.headless.api.pojo.request.DictItemReq;
import com.tencent.supersonic.headless.api.pojo.response.DictItemResp;
import com.tencent.supersonic.headless.api.pojo.response.DimensionResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.DictConfDO;
import com.tencent.supersonic.headless.server.persistence.repository.DictRepository;
import com.tencent.supersonic.headless.server.service.DictConfService;
import com.tencent.supersonic.headless.server.service.DimensionService;
import com.tencent.supersonic.headless.server.utils.DictUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class DictConfServiceImpl implements DictConfService {

    private final DictRepository dictRepository;
    private final DictUtils dictConverter;
    private final DimensionService dimensionService;

    public DictConfServiceImpl(DictRepository dictRepository, DictUtils dictConverter,
                               DimensionService dimensionService) {
        this.dictRepository = dictRepository;
        this.dictConverter = dictConverter;
        this.dimensionService = dimensionService;
    }

    @Override
    public DictItemResp addDictConf(DictItemReq itemValueReq, User user) {
        DictConfDO dictConfDO = dictConverter.generateDictConfDO(itemValueReq, user);
        Boolean exist = checkConfExist(itemValueReq, user);
        if (exist) {
            throw new RuntimeException("dictConf is existed");
        }
        Long id = dictRepository.addDictConf(dictConfDO);
        log.debug("dictConfDO:{}", dictConfDO);

        DictItemFilter filter =
                DictItemFilter.builder().id(id).status(itemValueReq.getStatus()).build();
        Optional<DictItemResp> dictItemResp = queryDictConf(filter, user).stream().findFirst();
        if (dictItemResp.isPresent()) {
            return dictItemResp.get();
        }
        return null;
    }

    private Boolean checkConfExist(DictItemReq itemValueReq, User user) {
        DictItemFilter filter = DictItemFilter.builder().build();
        BeanUtils.copyProperties(itemValueReq, filter);
        filter.setStatus(null);
        Optional<DictItemResp> dictItemResp = queryDictConf(filter, user).stream().findFirst();
        if (dictItemResp.isPresent()) {
            return true;
        }
        return false;
    }

    @Override
    public DictItemResp editDictConf(DictItemReq itemValueReq, User user) {
        DictConfDO dictConfDO = dictConverter.generateDictConfDO(itemValueReq, user);
        dictRepository.editDictConf(dictConfDO);
        DictItemFilter filter = DictItemFilter.builder().build();
        BeanUtils.copyProperties(itemValueReq, filter);
        Optional<DictItemResp> dictItemResp = queryDictConf(filter, user).stream().findFirst();
        if (dictItemResp.isPresent()) {
            return dictItemResp.get();
        }
        return null;
    }

    @Override
    public List<DictItemResp> queryDictConf(DictItemFilter dictItemFilter, User user) {
        List<DictItemResp> dictItems = dictRepository.queryDictConf(dictItemFilter);
        if (!dictItems.isEmpty()) {
            dictItems.getFirst().setLocked(1);
            return dictItems;
        }
        DictItemResp fallback = buildDimensionFallback(dictItemFilter);
        if (fallback == null) {
            return dictItems;
        }
        return java.util.Collections.singletonList(fallback);

    }

    private DictItemResp buildDimensionFallback(DictItemFilter dictItemFilter) {
        if (dictItemFilter == null || !TypeEnums.DIMENSION.equals(dictItemFilter.getType())
                || dictItemFilter.getItemId() == null) {
            return null;
        }
        if (dictItemFilter.getStatus() != null && !StatusEnum.ONLINE.equals(dictItemFilter.getStatus())) {
            return null;
        }
        if (dictItemFilter.getLocked() != null && !Integer.valueOf(1).equals(dictItemFilter.getLocked())) {
            return null;
        }

        DimensionResp dimensionResp = dimensionService.getDimension(dictItemFilter.getItemId());
        if (dimensionResp == null || dimensionResp.getModelId() == null
                || dimensionResp.getBizName() == null || dimensionResp.getId() == null
                || dimensionResp.getDimValueMaps() == null || dimensionResp.getDimValueMaps().isEmpty()) {
            return null;
        }

        DictItemResp dictItemResp = new DictItemResp();
        dictItemResp.setId(dimensionResp.getId());
        dictItemResp.setModelId(dimensionResp.getModelId());
        dictItemResp.setBizName(dimensionResp.getBizName());
        dictItemResp.setType(TypeEnums.DIMENSION);
        dictItemResp.setItemId(dimensionResp.getId());
        dictItemResp.setStatus(StatusEnum.ONLINE);
        dictItemResp.setLocked(1);
        return dictItemResp;
    }
}
