package com.tencent.supersonic.headless.server.service.impl;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.common.config.EmbeddingConfig;
import com.tencent.supersonic.common.pojo.Constants;
import com.tencent.supersonic.common.pojo.DateConf;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.EventType;
import com.tencent.supersonic.common.pojo.enums.StatusEnum;
import com.tencent.supersonic.common.pojo.enums.TaskStatusEnum;
import com.tencent.supersonic.common.pojo.enums.TypeEnums;
import com.tencent.supersonic.common.service.EmbeddingService;
import com.tencent.supersonic.common.util.BeanMapper;
import com.tencent.supersonic.common.util.DateUtils;
import com.tencent.supersonic.headless.api.pojo.DimValueMap;
import com.tencent.supersonic.headless.api.pojo.Dimension;

import com.tencent.supersonic.headless.api.pojo.request.DictItemFilter;
import com.tencent.supersonic.headless.api.pojo.request.DictSingleTaskReq;
import com.tencent.supersonic.headless.api.pojo.request.DictValueReq;
import com.tencent.supersonic.headless.api.pojo.request.QuerySqlReq;
import com.tencent.supersonic.headless.api.pojo.request.ValueTaskQueryReq;
import com.tencent.supersonic.headless.api.pojo.response.DictItemResp;
import com.tencent.supersonic.headless.api.pojo.response.DictTaskResp;
import com.tencent.supersonic.headless.api.pojo.response.DictValueDimResp;
import com.tencent.supersonic.headless.api.pojo.response.DictValueResp;
import com.tencent.supersonic.headless.api.pojo.response.DimensionResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.chat.knowledge.DictWord;
import com.tencent.supersonic.headless.chat.knowledge.file.FileHandler;
import com.tencent.supersonic.headless.server.facade.service.SemanticLayerService;
import com.tencent.supersonic.headless.server.persistence.dataobject.DictTaskDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.DimensionValueDO;
import com.tencent.supersonic.headless.server.persistence.repository.DictRepository;
import com.tencent.supersonic.headless.server.service.DictTaskService;
import com.tencent.supersonic.headless.server.service.DimensionService;
import com.tencent.supersonic.headless.server.service.ModelService;
import com.tencent.supersonic.headless.server.utils.DictUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DictTaskServiceImpl implements DictTaskService {

    @Value("${dict.flush.enable:true}")
    private Boolean dictFlushEnable;

    @Value("${dict.flush.daily.enable:true}")
    private Boolean dictFlushDailyEnable;

    @Value("${dict.file.type:txt}")
    private String dictFileType;

    @Value("${s2.dictionary.enabled:true}")
    private Boolean dictionaryEnabled;


    private String dimValue = "DimValue_%d_%d";

    private static final int MAX_DICT_VALUE_SCAN = 10000;


    private final DictRepository dictRepository;
    private final DictUtils dictConverter;
    private final DictUtils dictUtils;
    private final FileHandler fileHandler;
    private final DictWordService dictWordService;
    private final DimensionService dimensionService;
    private final EmbeddingService embeddingService;
    private final EmbeddingConfig embeddingConfig;
    private final SemanticLayerService queryService;
    private final ModelService modelService;

    public DictTaskServiceImpl(DictRepository dictRepository, DictUtils dictConverter,
            DictUtils dictUtils, FileHandler fileHandler, DictWordService dictWordService,
            DimensionService dimensionService, EmbeddingService embeddingService,
            EmbeddingConfig embeddingConfig, SemanticLayerService queryService,
            ModelService modelService) {
        this.dictRepository = dictRepository;
        this.dictConverter = dictConverter;
        this.dictUtils = dictUtils;
        this.fileHandler = fileHandler;
        this.dictWordService = dictWordService;
        this.dimensionService = dimensionService;
        this.embeddingService = embeddingService;
        this.embeddingConfig = embeddingConfig;
        this.queryService = queryService;
        this.modelService = modelService;
    }

    @Override
    public Long addDictTask(DictSingleTaskReq taskReq, User user) {
        DictItemResp dictItemResp = fetchDictItemResp(taskReq);
        if (Objects.isNull(dictItemResp)) {
            return 0L;
        }


        Long dictTaskId = handleDictTaskByItemResp(dictItemResp, user);

        // 统一执行一次词典加载
        if (Boolean.TRUE.equals(dictionaryEnabled)) {
            try {
                dictWordService.loadDictWord();
                log.info("[dailyDictTask] Dictionary loaded successfully after batch processing.");
            } catch (Exception e) {
                log.error("[dailyDictTask] Failed to load dictionary after batch processing.", e);
            }
        }
        return dictTaskId;
    }

    private Long handleDictTaskByItemResp(DictItemResp dictItemResp, User user) {
        DictTaskDO dictTaskDO =
                dictConverter.generateDictTaskDO(dictItemResp, user, TaskStatusEnum.PENDING);
        log.info("[addDictTask] dictTaskDO:{}", dictTaskDO);
        dictRepository.addDictTask(dictTaskDO);
        Long idInDb = dictTaskDO.getId();
        dictItemResp.setId(idInDb);
        runDictTask(dictItemResp, user);
        return idInDb;
    }

    private DictItemResp fetchDictItemResp(DictSingleTaskReq taskReq) {
        DictItemFilter dictItemFilter = DictItemFilter.builder().itemId(taskReq.getItemId())
                .type(taskReq.getType()).build();
        List<DictItemResp> dictItemRespList = dictRepository.queryDictConf(dictItemFilter);
        if (!CollectionUtils.isEmpty(dictItemRespList)) {
            dictItemRespList.getFirst().setLocked(1);
            return dictItemRespList.getFirst();
        }
        if (!TypeEnums.DIMENSION.equals(taskReq.getType())) {
            return null;
        }
        DimensionResp dimensionResp = dimensionService.getDimension(taskReq.getItemId());
        if (Objects.isNull(dimensionResp)) {
            return null;
        }
        DictItemResp fallback = new DictItemResp();
        fallback.setModelId(dimensionResp.getModelId());
        fallback.setBizName(dimensionResp.getBizName());
        fallback.setType(TypeEnums.DIMENSION);
        fallback.setItemId(dimensionResp.getId());
        fallback.setStatus(StatusEnum.ONLINE);
        fallback.setLocked(1);
        return fallback;
    }

    private void runDictTask(DictItemResp dictItemResp, User user) {
        if (Objects.isNull(dictItemResp)) {
            return;
        }

        DictTaskDO dictTaskDO = dictRepository.queryDictTaskById(dictItemResp.getId());
        dictTaskDO.setStatus(TaskStatusEnum.RUNNING.getStatus());
        dictRepository.editDictTask(dictTaskDO);

        // 1.Generate item dictionary data
        List<String> data = dictUtils.fetchItemValue(dictItemResp);

        // 2.Change dictionary file

        if (Boolean.TRUE.equals(dictFlushEnable) && Boolean.TRUE.equals(dictionaryEnabled)) {
            String fileName = dictItemResp.fetchDictFileName() + Constants.DOT + dictFileType;
            fileHandler.writeFile(data, fileName, false);
        }

        // 3.Change in-memory dictionary data in real time
        String status = TaskStatusEnum.SUCCESS.getStatus();
        // try {
        // dictWordService.loadDictWord();
        // } catch (Exception e) {
        // log.error("reloadCustomDictionary error", e);
        // status = TaskStatusEnum.ERROR.getStatus();
        // dictTaskDO.setDescription(e.toString());
        // }
        dictTaskDO.setStatus(status);
        dictTaskDO.setElapsedMs(DateUtils.calculateDiffMs(dictTaskDO.getCreatedAt()));
        dictRepository.editDictTask(dictTaskDO);

        // 4. 全量刷新维度值向量（先清后写）并更新预览 dimValueMaps
        deleteAllDimensionValueEmbedding(dictItemResp);
        List<DimensionValueDO> dimensionValueDOS = buildDimensionValueDOS(data, dictItemResp);
        if (!CollectionUtils.isEmpty(dimensionValueDOS)) {
            dimensionService.sendDimensionValueEventBatch(dimensionValueDOS, EventType.ADD);
        }
        List<DimValueMap> previewDimValueMaps =
                buildPreviewDimValueMaps(dimensionValueDOS, dictItemResp.getItemId());
        dimensionService.updateDimValueMapsOnlyBatch(dictItemResp.getItemId(), previewDimValueMaps,
                user);
    }


    private DimensionValueDO convert2DimValueDO(String lineStr) {
        DimensionValueDO dimensionValueDO = new DimensionValueDO();
        if (StringUtils.isNotEmpty(lineStr)) {
            String[] itemArray = lineStr.split("\\s+");
            if (Objects.nonNull(itemArray) && itemArray.length >= 3) {
                dimensionValueDO.setDimValue(itemArray[0].replace("#", " "));
                dimensionValueDO.setNature(itemArray[itemArray.length - 2]);
                dimensionValueDO.setFrequency(Long.parseLong(itemArray[itemArray.length - 1]));
            }
        }
        return dimensionValueDO;
    }

    private List<DimensionValueDO> buildDimensionValueDOS(List<String> data,
            DictItemResp dictItemResp) {
        if (CollectionUtils.isEmpty(data) || Objects.isNull(dictItemResp)
                || Objects.isNull(dictItemResp.getItemId())
                || Objects.isNull(dictItemResp.getModelId())) {
            return new ArrayList<>();
        }
        Map<String, DimensionValueDO> valueMap = new LinkedHashMap<>();
        for (String line : data) {
            DimensionValueDO parsed = convert2DimValueDO(line);
            if (Objects.isNull(parsed) || StringUtils.isBlank(parsed.getDimValue())) {
                continue;
            }
            String dimValue = parsed.getDimValue().trim();
            if (StringUtils.isBlank(dimValue)) {
                continue;
            }
            parsed.setDimValue(dimValue);
            parsed.setDimBizName(dictItemResp.getBizName());
            parsed.setModelId(dictItemResp.getModelId());
            parsed.setDimId(dictItemResp.getItemId());
            parsed.setFrequency(Objects.isNull(parsed.getFrequency()) ? 1L : parsed.getFrequency());
            valueMap.merge(dimValue, parsed, (oldV, newV) -> {
                oldV.setFrequency(Math.max(oldV.getFrequency(), newV.getFrequency()));
                if (StringUtils.isBlank(oldV.getNature())) {
                    oldV.setNature(newV.getNature());
                }
                return oldV;
            });
        }
        return new ArrayList<>(valueMap.values());
    }

    private List<DimValueMap> buildPreviewDimValueMaps(List<DimensionValueDO> dimensionValueDOS,
            Long dimId) {
        if (CollectionUtils.isEmpty(dimensionValueDOS) || Objects.isNull(dimId)) {
            return new ArrayList<>();
        }
        DimensionResp dimensionResp = dimensionService.getDimension(dimId);
        List<DimValueMap> oldMaps = Objects.isNull(dimensionResp)
                || CollectionUtils.isEmpty(dimensionResp.getDimValueMaps()) ? new ArrayList<>()
                        : dimensionResp.getDimValueMaps();
        Map<String, DimValueMap> oldMapByValue = oldMaps.stream().filter(Objects::nonNull)
                .filter(map -> StringUtils
                        .isNotBlank(StringUtils.defaultIfBlank(map.getValue(), map.getTechName())))
                .collect(Collectors.toMap(
                        map -> StringUtils.defaultIfBlank(map.getValue(), map.getTechName()),
                        map -> map, (a, b) -> a));

        return dimensionValueDOS.stream().sorted(Comparator
                .comparing((DimensionValueDO v) -> Optional.ofNullable(v.getFrequency()).orElse(0L))
                .reversed().thenComparing(DimensionValueDO::getDimValue))
                .filter(v -> StringUtils.isNotBlank(v.getDimValue()))
                .filter(v -> StringUtils.length(v.getDimValue()) <= 20).limit(50).map(v -> {
                    String value = v.getDimValue();
                    DimValueMap dimValueMap = new DimValueMap();
                    dimValueMap.setValue(value);
                    dimValueMap.setTechName(value);
                    DimValueMap old = oldMapByValue.get(value);
                    if (Objects.nonNull(old)) {
                        if (!CollectionUtils.isEmpty(old.getAlias())) {
                            dimValueMap.setAlias(old.getAlias());
                        }
                        if (StringUtils.isNotBlank(old.getBizName())
                                && !StringUtils.equals(old.getBizName(), value)) {
                            dimValueMap.setBizName(old.getBizName());
                        }
                    }
                    return dimValueMap;
                }).collect(Collectors.toList());
    }

    @Override
    public Long deleteDictTask(DictSingleTaskReq taskReq, User user) {

        DictItemResp dictItemResp = fetchDictItemResp(taskReq);
        if (Objects.isNull(dictItemResp)) {
            return 0L;
        }
        deleteAllDimensionValueEmbedding(dictItemResp);
        clearDimensionValueMaps(dictItemResp, user);

        if (Boolean.TRUE.equals(dictionaryEnabled)) {
            String fileName = dictItemResp.fetchDictFileName() + Constants.DOT + dictFileType;
            fileHandler.deleteDictFile(fileName);
            try {
                dictWordService.loadDictWord();
            } catch (Exception e) {
                log.error("reloadCustomDictionary error", e);
            }
        }
        // Add a clear dictionary file record
        DictTaskDO dictTaskDO =
                dictConverter.generateDictTaskDO(dictItemResp, user, TaskStatusEnum.INITIAL);
        log.info("[addDictTask] dictTaskDO:{}", dictTaskDO);
        dictRepository.addDictTask(dictTaskDO);
        return 0L;
    }

    private void deleteAllDimensionValueEmbedding(DictItemResp dictItemResp) {
        if (Objects.isNull(dictItemResp) || !TypeEnums.DIMENSION.equals(dictItemResp.getType())
                || Objects.isNull(dictItemResp.getItemId())) {
            return;
        }
        try {
            Map<String, Object> filterCondition = new HashMap<>();
            filterCondition.put("type", TypeEnums.VALUE.name());
            filterCondition.put("dimId", dictItemResp.getItemId());
            embeddingService.deleteByCondition(embeddingConfig.getMetaCollectionName(),
                    filterCondition);
        } catch (Exception e) {
            log.warn("deleteAllDimensionValueEmbedding error,dimId:{}", dictItemResp.getItemId(),
                    e);
        }
    }

    private void clearDimensionValueMaps(DictItemResp dictItemResp, User user) {
        if (Objects.isNull(dictItemResp) || !TypeEnums.DIMENSION.equals(dictItemResp.getType())
                || Objects.isNull(dictItemResp.getItemId()) || Objects.isNull(user)) {
            return;
        }
        try {
            dimensionService.updateDimValueAliasBatch(dictItemResp.getItemId(), new ArrayList<>(),
                    user);
        } catch (Exception e) {
            log.warn("clearDimensionValueMaps error,dimId:{}", dictItemResp.getItemId(), e);
        }
    }


    @Override
    public Long deleteDictTaskForBI(DictSingleTaskReq taskReq, User user) {
        DictItemResp dictItemResp = fetchDictItemResp(taskReq);
        if (Objects.isNull(dictItemResp)) {
            return 0L;
        }

        deleteAllDimensionValueEmbedding(dictItemResp);
        clearDimensionValueMaps(dictItemResp, user);
        if (Boolean.TRUE.equals(dictionaryEnabled)) {
            String fileName = dictItemResp.fetchDictFileName() + Constants.DOT + dictFileType;
            fileHandler.deleteDictFile(fileName);
        }


        // Add a clear dictionary file record
        DictTaskDO dictTaskDO =
                dictConverter.generateDictTaskDO(dictItemResp, user, TaskStatusEnum.INITIAL);
        log.info("[addDictTask] dictTaskDO:{}", dictTaskDO);
        dictRepository.addDictTask(dictTaskDO);
        return 0L;
    }

    @Override
    public Long addSuccessTaskForBI(DictSingleTaskReq taskReq, User user) {
        DictItemResp dictItemResp = fetchDictItemResp(taskReq);
        if (Objects.isNull(dictItemResp)) {
            return 0L;
        }
        DictTaskResp latestTask = dictRepository.queryLatestDictTask(taskReq);
        if (Objects.nonNull(latestTask) && Objects.nonNull(latestTask.getId())) {
            DictTaskDO existTask = dictRepository.queryDictTaskById(latestTask.getId());
            if (Objects.nonNull(existTask)) {
                existTask.setStatus(TaskStatusEnum.SUCCESS.getStatus());
                existTask.setElapsedMs(DateUtils.calculateDiffMs(existTask.getCreatedAt()));
                dictRepository.editDictTask(existTask);
                return existTask.getId();
            }
        }
        DictTaskDO dictTaskDO =
                dictConverter.generateDictTaskDO(dictItemResp, user, TaskStatusEnum.SUCCESS);
        log.info("[addDictTask] dictTaskDO:{}", dictTaskDO);
        dictTaskDO.setElapsedMs(DateUtils.calculateDiffMs(dictTaskDO.getCreatedAt()));
        dictRepository.addDictTask(dictTaskDO);
        return dictTaskDO.getId();
    }


    @Override
    public void reloadDictWord() {

        if (Boolean.FALSE.equals(dictionaryEnabled)) {
            return;
        }
        try {
            dictWordService.loadDictWord();
        } catch (Exception e) {
            log.error("reloadCustomDictionary error", e);
        }
    }

    public void deleteEmbedding(DictItemResp dictItemResp, String fileName) {
        List<DimensionValueDO> dimensionValueDOS;
        // TODO，直接从文件中读取所有维度值不妥，后续待优化
        List<String> data = fileHandler.readFile(fileName);
        if (!CollectionUtils.isEmpty(data)) {
            dimensionValueDOS = data.stream().map(this::convert2DimValueDO).filter(Objects::nonNull)
                    .peek(dimensionValueDO -> {
                        dimensionValueDO.setModelId(dictItemResp.getModelId());
                        dimensionValueDO.setDimId(dictItemResp.getItemId());
                        dimensionValueDO.setDimBizName(dictItemResp.getBizName());
                    }).collect(Collectors.toList());
            dimensionService.sendDimensionValueEventBatch(dimensionValueDOS, EventType.DELETE);
        }
    }

    @Override
    @Scheduled(cron = "${knowledge.dimension.value.cron:0 0 4 * * ?}")
    public Boolean dailyDictTask() {
        log.info("[dailyDictTask] start");
        if (!dictFlushDailyEnable || Boolean.FALSE.equals(dictionaryEnabled)) {
            log.info("dailyDictTask skipped (dictFlushDailyEnable={}, dictionaryEnabled={})",
                    dictFlushDailyEnable, dictionaryEnabled);
            return true;
        }
        DictItemFilter filter =
                DictItemFilter.builder().status(StatusEnum.ONLINE).locked(0).build();
        List<DictItemResp> dictItemRespList = dictRepository.queryDictConf(filter);
        dictItemRespList.forEach(item -> handleDictTaskByItemResp(item, null));

        // 统一执行一次词典加载
        if (Boolean.TRUE.equals(dictionaryEnabled)) {
            try {
                dictWordService.loadDictWord();
                log.info("[dailyDictTask] Dictionary loaded successfully after batch processing.");
            } catch (Exception e) {
                log.error("[dailyDictTask] Failed to load dictionary after batch processing.", e);
            }
        }

        log.info("[dailyDictTask] finish");
        return true;
    }

    @Override
    public DictTaskResp queryLatestDictTask(DictSingleTaskReq taskReq, User user) {
        return dictRepository.queryLatestDictTask(taskReq);
    }

    @Override
    public PageInfo<DictTaskResp> queryDictTask(ValueTaskQueryReq taskQueryReq, User user) {
        PageInfo<DictTaskDO> dictTaskDOPageInfo =
                PageHelper.startPage(taskQueryReq.getCurrent(), taskQueryReq.getPageSize())
                        .doSelectPageInfo(() -> dictRepository.queryAllDictTask(taskQueryReq));
        PageInfo<DictTaskResp> dictTaskRespPageInfo = new PageInfo<>();
        BeanMapper.mapper(dictTaskDOPageInfo, dictTaskRespPageInfo);
        dictTaskRespPageInfo.setList(dictConverter.taskDO2Resp(dictTaskDOPageInfo.getList()));
        return dictTaskRespPageInfo;
    }

    // @Override
    // public PageInfo<DictValueDimResp> queryDictValue(DictValueReq dictValueReq, User user) {
    // // todo 优化读取内存结构
    // // return getDictValuePageFromMemory(dictValueReq);
    // return getDictValuePageFromFile(dictValueReq);
    // }

    @Override
    public PageInfo<DictValueDimResp> queryDictValue(DictValueReq dictValueReq, User user) {
        if (!TypeEnums.DIMENSION.equals(dictValueReq.getType())) {
            return emptyDictValuePage(dictValueReq);
        }
        if (!isDictVisibleEnabled(dictValueReq)) {
            return emptyDictValuePage(dictValueReq);
        }
        if (!isLatestTaskSuccess(dictValueReq)) {
            return emptyDictValuePage(dictValueReq);
        }

        DimensionResp dimResp = dimensionService.getDimension(dictValueReq.getItemId());
        if (Objects.nonNull(dimResp) && !CollectionUtils.isEmpty(dimResp.getDimValueMaps())
                && dimResp.getDimValueMaps().size() < 50) {
            return getDictValuePageFromMaps(dictValueReq, dimResp);
        }
        return getDictValuePageFromDb(dictValueReq, user, dimResp);
    }


    private boolean isDictVisibleEnabled(DictValueReq dictValueReq) {
        DictItemFilter filter = DictItemFilter.builder().itemId(dictValueReq.getItemId())
                .type(dictValueReq.getType()).build();
        List<DictItemResp> dictItemRespList = dictRepository.queryDictConf(filter);
        if (CollectionUtils.isEmpty(dictItemRespList)) {
            return false;
        }
        DictItemResp dictItemResp = dictItemRespList.getFirst();
        return StatusEnum.ONLINE.equals(dictItemResp.getStatus());
    }

    private boolean isLatestTaskSuccess(DictValueReq dictValueReq) {
        DictSingleTaskReq taskReq = DictSingleTaskReq.builder().itemId(dictValueReq.getItemId())
                .type(dictValueReq.getType()).build();
        DictTaskResp latestTask = dictRepository.queryLatestDictTask(taskReq);
        if (Objects.isNull(latestTask) || StringUtils.isBlank(latestTask.getTaskStatus())) {
            return false;
        }
        return TaskStatusEnum.SUCCESS.getStatus().equals(latestTask.getTaskStatus());
    }

    private PageInfo<DictValueDimResp> emptyDictValuePage(DictValueReq dictValueReq) {
        PageInfo<DictValueDimResp> empty = new PageInfo<>();
        empty.setList(new ArrayList<>());
        empty.setTotal(0);
        empty.setPageNum(dictValueReq.getCurrent());
        empty.setPageSize(dictValueReq.getPageSize());
        return empty;
    }


    private PageInfo<DictValueDimResp> getDictValuePageFromMaps(DictValueReq dictValueReq) {
        DimensionResp dimResp = dimensionService.getDimension(dictValueReq.getItemId());
        return getDictValuePageFromMaps(dictValueReq, dimResp);
    }

    private PageInfo<DictValueDimResp> getDictValuePageFromMaps(DictValueReq dictValueReq,
            DimensionResp dimResp) {
        PageInfo<DictValueDimResp> pageInfo = new PageInfo<>();
        if (Objects.isNull(dimResp) || CollectionUtils.isEmpty(dimResp.getDimValueMaps())) {
            pageInfo.setList(new ArrayList<>());
            pageInfo.setTotal(0);
            pageInfo.setPageNum(dictValueReq.getCurrent());
            pageInfo.setPageSize(dictValueReq.getPageSize());
            return pageInfo;
        }


        List<DictValueDimResp> values = dimResp.getDimValueMaps().stream().filter(Objects::nonNull)
                .map(this::convert2DictValueInternal).filter(Objects::nonNull)
                .filter(resp -> StringUtils.isBlank(dictValueReq.getKeyValue()) || StringUtils
                        .containsIgnoreCase(resp.getValue(), dictValueReq.getKeyValue()))
                .collect(Collectors.toList());

        Integer pageSize = dictValueReq.getPageSize();
        Integer current = dictValueReq.getCurrent();
        int startIndex = Math.max((current - 1) * pageSize, 0);
        int endIndex = Math.min(startIndex + pageSize, values.size());
        List<DictValueDimResp> paged = startIndex >= values.size() ? new ArrayList<>()
                : values.subList(startIndex, endIndex);

        pageInfo.setList(paged);
        pageInfo.setTotal(values.size());
        pageInfo.setPageNum(current);
        pageInfo.setPageSize(pageSize);
        return pageInfo;
    }

    private PageInfo<DictValueDimResp> getDictValuePageFromDb(DictValueReq dictValueReq,
            User user, DimensionResp preloadedDimResp) {

        PageInfo<DictValueDimResp> empty = new PageInfo<>();
        empty.setList(new ArrayList<>());
        empty.setTotal(0);
        empty.setPageNum(dictValueReq.getCurrent());
        empty.setPageSize(dictValueReq.getPageSize());

        try {
            DimensionResp dimResp = Objects.nonNull(preloadedDimResp) ? preloadedDimResp
                    : dimensionService.getDimension(dictValueReq.getItemId());

            if (Objects.isNull(dimResp) || Objects.isNull(dimResp.getModelId())) {
                return empty;
            }

            ModelResp modelResp = modelService.getModel(dimResp.getModelId());
            if (Objects.isNull(modelResp) || Objects.isNull(modelResp.getModelDetail())) {
                return empty;
            }

            String dimBizName = dimResp.getBizName();
            if (StringUtils.isBlank(dimBizName)) {
                return empty;
            }

            String tableStr = StringUtils.isNotBlank(modelResp.getModelDetail().getTableQuery())
                    ? modelResp.getModelDetail().getTableQuery()
                    : "(" + modelResp.getModelDetail().getSqlQuery() + ") AS t";
            String escapedKey =
                    StringUtils.defaultString(dictValueReq.getKeyValue()).replace("'", "''");
            String whereClause = String.format(" where %s is not null", dimBizName);
            if (StringUtils.isNotBlank(escapedKey)) {
                whereClause += String.format(" and %s like '%%%s%%'", dimBizName, escapedKey);
            }

            DateSamplingConfig dateSamplingConfig =
                    resolveDateSamplingConfig(dictValueReq.getItemId(), modelResp);
            String sampledWhereClause = whereClause;
            if (Objects.nonNull(dateSamplingConfig)
                    && StringUtils.isNotBlank(dateSamplingConfig.getDateFilterSql())) {
                sampledWhereClause += " and " + dateSamplingConfig.getDateFilterSql();
            }

            String sampledSql = String.format("select %s as value from %s %s limit %d", dimBizName,
                    tableStr, sampledWhereClause, MAX_DICT_VALUE_SCAN);
            String countSql =
                    String.format("select count(1) total from (select distinct value from (%s) sampled) t",
                            sampledSql);
            QuerySqlReq countReq = QuerySqlReq.builder().sql(countSql).build();
            countReq.addModelId(dimResp.getModelId());
            SemanticQueryResp countResp = queryService.queryByReq(countReq, user);
            long total = extractTotal(countResp);
            long cappedTotal = Math.min(total, MAX_DICT_VALUE_SCAN);
            if (cappedTotal <= 0) {
                return empty;
            }

            if (cappedTotal <= 50) {
                int dimValueMapsCount =
                        countFilteredDimValueMaps(dictValueReq.getItemId(), dictValueReq.getKeyValue());
                if (dimValueMapsCount > cappedTotal) {
                    return getDictValuePageFromMaps(dictValueReq, dimResp);

                }
            }

            Integer current = dictValueReq.getCurrent();
            Integer pageSize = dictValueReq.getPageSize();
            int offset = Math.max((current - 1) * pageSize, 0);
            if (offset >= cappedTotal) {
                empty.setTotal(cappedTotal);
                return empty;
            }
            int currentPageSize = (int) Math.min(pageSize, cappedTotal - offset);
            String dataSql = String.format(
                    "select distinct value from (%s) sampled order by value limit %d offset %d",
                    sampledSql, currentPageSize, offset);
            QuerySqlReq dataReq = QuerySqlReq.builder().sql(dataSql).build();
            dataReq.addModelId(dimResp.getModelId());
            SemanticQueryResp dataResp = queryService.queryByReq(dataReq, user);


            List<DictValueDimResp> list = new ArrayList<>();
            if (Objects.nonNull(dataResp) && !CollectionUtils.isEmpty(dataResp.getResultList())) {
                for (Map<String, Object> row : dataResp.getResultList()) {
                    if (CollectionUtils.isEmpty(row)) {
                        continue;
                    }
                    Object valueObj = row.get("value");
                    if (Objects.isNull(valueObj)) {
                        valueObj = row.values().stream().findFirst().orElse(null);
                    }
                    if (Objects.isNull(valueObj) || StringUtils.isBlank(valueObj.toString())) {
                        continue;
                    }
                    DictValueDimResp resp = new DictValueDimResp();
                    resp.setValue(valueObj.toString());
                    list.add(resp);
                }
            }

            fillDimMapInfo(list, dictValueReq.getItemId());
            empty.setList(list);
            empty.setTotal(cappedTotal);
            return empty;

        } catch (Exception e) {
            log.warn("query dict value from db fallback error, req:{}", dictValueReq, e);
            return empty;
        }
    }

    private int countFilteredDimValueMaps(Long dimId, String keyValue) {
        DimensionResp dimResp = dimensionService.getDimension(dimId);
        if (Objects.isNull(dimResp) || CollectionUtils.isEmpty(dimResp.getDimValueMaps())) {
            return 0;
        }
        return (int) dimResp.getDimValueMaps().stream().filter(Objects::nonNull).map(
                map -> StringUtils.defaultIfBlank(map.getValue(), map.getTechName()))
                .filter(StringUtils::isNotBlank)
                .filter(value -> StringUtils.isBlank(keyValue)
                        || StringUtils.containsIgnoreCase(value, keyValue))
                .count();
    }

    private DateSamplingConfig resolveDateSamplingConfig(Long dimId, ModelResp modelResp) {
        if (Objects.isNull(dimId) || Objects.isNull(modelResp)) {
            return null;
        }
        String dateField = null;
        DictItemFilter dictItemFilter =
                DictItemFilter.builder().itemId(dimId).type(TypeEnums.DIMENSION).build();
        List<DictItemResp> dictItems = dictRepository.queryDictConf(dictItemFilter);
        if (!CollectionUtils.isEmpty(dictItems) && Objects.nonNull(dictItems.getFirst().getConfig())
                && Objects.nonNull(dictItems.getFirst().getConfig().getDateConf())) {
            DateConf dateConf = dictItems.getFirst().getConfig().getDateConf();
            if (StringUtils.isNotBlank(dateConf.getDateField())) {
                dateField = dateConf.getDateField();
            }
        }

        List<Dimension> timeDimensions = modelResp.getTimeDimensionForBI();
        Dimension timeDimension = CollectionUtils.isEmpty(timeDimensions) ? null : timeDimensions.getFirst();
        String dateFormat = Objects.nonNull(timeDimension)
                ? StringUtils.defaultIfBlank(timeDimension.getDateFormat(), "yyyy-MM-dd")
                : "yyyy-MM-dd";
        String timeGranularity = Objects.nonNull(timeDimension)
                && Objects.nonNull(timeDimension.getTypeParams())
                        ? timeDimension.getTypeParams().getTimeGranularity()
                        : "";

        if (StringUtils.isBlank(dateField) && Objects.nonNull(timeDimension)) {
            dateField = StringUtils.defaultIfBlank(timeDimension.getBizName(), timeDimension.getExpr());
        }

        if (StringUtils.isBlank(dateField) || !isSafeFieldName(dateField)) {
            return null;
        }

        String dateFilterSql = buildDateFilterSql(dateField, dateFormat, timeGranularity);
        if (StringUtils.isBlank(dateFilterSql)) {
            return null;
        }
        return new DateSamplingConfig(dateField, dateFilterSql);
    }

    private String buildDateFilterSql(String dateField, String dateFormat, String timeGranularity) {
        boolean monthGranularity = isMonthGranularity(dateFormat, timeGranularity);
        LocalDate startDate;
        LocalDate endDate;
        LocalDate today = LocalDate.now();
        if (monthGranularity || today.getDayOfMonth() == 1) {
            YearMonth prevMonth = YearMonth.now().minusMonths(1);
            startDate = prevMonth.atDay(1);
            endDate = prevMonth.atEndOfMonth();
        } else {
            startDate = today.minusDays(3);
            endDate = today;
        }

        String start = formatDate(startDate, dateFormat);
        String end = formatDate(endDate, dateFormat);
        return String.format("%s >= '%s' and %s <= '%s'", dateField, start, dateField, end);
    }


    private boolean isMonthGranularity(String dateFormat, String timeGranularity) {
        if (StringUtils.equalsIgnoreCase(timeGranularity, "month")) {
            return true;
        }
        if (StringUtils.isBlank(dateFormat)) {
            return false;
        }
        String format = dateFormat.toLowerCase();
        return format.contains("m") && !format.contains("d");
    }

    private String formatDate(LocalDate date, String dateFormat) {
        String format = StringUtils.defaultIfBlank(dateFormat, "yyyy-MM-dd");
        try {
            return date.format(DateTimeFormatter.ofPattern(format));
        } catch (Exception e) {
            return date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
    }

    private boolean isSafeFieldName(String fieldName) {
        return StringUtils.isNotBlank(fieldName) && fieldName.matches("[a-zA-Z0-9_.$]+$");
    }

    private long extractTotal(SemanticQueryResp semanticQueryResp) {
        if (Objects.isNull(semanticQueryResp)
                || CollectionUtils.isEmpty(semanticQueryResp.getResultList())) {
            return 0L;
        }
        Map<String, Object> first = semanticQueryResp.getResultList().get(0);
        if (CollectionUtils.isEmpty(first)) {
            return 0L;
        }
        Object totalObj = first.get("total");
        if (Objects.isNull(totalObj)) {
            totalObj = first.values().stream().findFirst().orElse(0L);
        }
        if (Objects.isNull(totalObj)) {
            return 0L;
        }
        return Long.parseLong(totalObj.toString());
    }

    private PageInfo<DictValueDimResp> getDictValuePageFromFile(DictValueReq dictValueReq) {

        String fileName = String.format("dic_value_%d_%s_%s", dictValueReq.getModelId(),
                dictValueReq.getType().name(), dictValueReq.getItemId()) + Constants.DOT
                + dictFileType;
        PageInfo<DictValueResp> dictValueRespList =
                fileHandler.queryDictValue(fileName, dictValueReq);
        PageInfo<DictValueDimResp> result = convert2DictValueDimRespPage(dictValueRespList);
        fillDimMapInfo(result.getList(), dictValueReq.getItemId());
        return result;
    }

    private void fillDimMapInfo(List<DictValueDimResp> list, Long dimId) {
        DimensionResp dimResp = dimensionService.getDimension(dimId);
        if (CollectionUtils.isEmpty(dimResp.getDimValueMaps())) {
            return;
        }
        Map<String, DimValueMap> valueAndMap = dimResp.getDimValueMaps().stream()
                .collect(Collectors.toMap(dim -> dim.getValue(), v -> v, (v1, v2) -> v2));
        if (CollectionUtils.isEmpty(valueAndMap)) {
            return;
        }
        list.stream().forEach(dictValueDimResp -> {
            String dimValue = dictValueDimResp.getValue();
            if (valueAndMap.containsKey(dimValue) && Objects.nonNull(valueAndMap.get(dimValue))) {
                dictValueDimResp.setAlias(valueAndMap.get(dimValue).getAlias());
            }
        });
    }

    private PageInfo<DictValueDimResp> convert2DictValueDimRespPage(
            PageInfo<DictValueResp> dictValueRespPage) {
        PageInfo<DictValueDimResp> result = new PageInfo<>();
        BeanMapper.mapper(dictValueRespPage, result);
        if (CollectionUtils.isEmpty(dictValueRespPage.getList())) {
            return result;
        }

        List<DictValueDimResp> list = getDictValueDimRespList(dictValueRespPage.getList());
        result.setList(list);
        return result;
    }

    private List<DictValueDimResp> getDictValueDimRespList(List<DictValueResp> dictValueRespList) {
        List<DictValueDimResp> list =
                dictValueRespList.stream().map(dictValue -> convert2DictValueInternal(dictValue))
                        .collect(Collectors.toList());
        return list;
    }

    private List<DictValueDimResp> getDictValueDimRespList(List<DictWord> dictWords, Long dimId) {
        DimensionResp dimResp = dimensionService.getDimension(dimId);
        List<DictValueDimResp> list =
                dictWords.stream().map(dictWord -> convert2DictValueInternal(dictWord, dimResp))
                        .collect(Collectors.toList());
        return list;
    }

    private DictValueDimResp convert2DictValueInternal(DictWord dictWord, DimensionResp dimResp) {
        DictValueDimResp dictValueDimResp = new DictValueDimResp();
        BeanMapper.mapper(dictWord, dictValueDimResp);
        if (Objects.nonNull(dimResp.getDimValueMaps())) {
            Map<String, DimValueMap> techAndAliasMap = dimResp.getDimValueMaps().stream().collect(
                    Collectors.toMap(dimValue -> dimValue.getTechName(), v -> v, (v1, v2) -> v2));
            if (techAndAliasMap.containsKey(dictWord.getWord())) {
                DimValueMap dimValueMap = techAndAliasMap.get(dictWord.getWord());
                BeanMapper.mapper(dimValueMap, dictValueDimResp);
            }
        }
        return dictValueDimResp;
    }

    private DictValueDimResp convert2DictValueInternal(DictValueResp dictValue) {
        DictValueDimResp dictValueDimResp = new DictValueDimResp();
        BeanMapper.mapper(dictValue, dictValueDimResp);
        return dictValueDimResp;
    }

    private DictValueDimResp convert2DictValueInternal(DimValueMap dimValueMap) {
        if (Objects.isNull(dimValueMap)) {
            return null;
        }
        String value =
                StringUtils.defaultIfBlank(dimValueMap.getValue(), dimValueMap.getTechName());
        if (StringUtils.isBlank(value)) {
            return null;
        }
        DictValueDimResp dictValueDimResp = new DictValueDimResp();
        dictValueDimResp.setValue(value);
        dictValueDimResp.setBizName(dimValueMap.getBizName());
        if (!CollectionUtils.isEmpty(dimValueMap.getAlias())) {
            dictValueDimResp.setAlias(dimValueMap.getAlias());
        }
        return dictValueDimResp;
    }

    private PageInfo<DictValueDimResp> getDictValuePageFromMemory(DictValueReq dictValueReq) {
        PageInfo<DictValueDimResp> dictValueRespPageInfo = new PageInfo<>();
        Set<Long> dimSet = new HashSet<>();
        dimSet.add(dictValueReq.getItemId());
        List<DictWord> dimDictWords = dictWordService.getDimDictWords(dimSet);
        if (CollectionUtils.isEmpty(dimDictWords)) {
            return dictValueRespPageInfo;
        }
        if (StringUtils.isNotEmpty(dictValueReq.getKeyValue())) {
            dimDictWords = dimDictWords.stream()
                    .filter(dimValue -> dimValue.getWord().contains(dictValueReq.getKeyValue()))
                    .collect(Collectors.toList());
        }

        Integer pageSize = dictValueReq.getPageSize();
        Integer current = dictValueReq.getCurrent();
        dictValueRespPageInfo.setTotal(dimDictWords.size());
        dictValueRespPageInfo.setPageSize(pageSize);
        dictValueRespPageInfo.setPageNum(dictValueReq.getCurrent());

        // 分页
        int startIndex = (current - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, dimDictWords.size());
        List<DictWord> data = dimDictWords.subList(startIndex, endIndex);
        dictValueRespPageInfo.setList(getDictValueDimRespList(data, dictValueReq.getItemId()));
        return dictValueRespPageInfo;
    }

    private static class DateSamplingConfig {
        private final String dateField;
        private final String dateFilterSql;

        private DateSamplingConfig(String dateField, String dateFilterSql) {
            this.dateField = dateField;
            this.dateFilterSql = dateFilterSql;
        }

        public String getDateField() {
            return dateField;
        }

        public String getDateFilterSql() {
            return dateFilterSql;
        }
    }

    @Override
    public String queryDictFilePath(DictValueReq dictValueReq, User user) {
        String fileName = String.format("dic_value_%d_%s_%s", dictValueReq.getModelId(),
                dictValueReq.getType().name(), dictValueReq.getItemId()) + Constants.DOT
                + dictFileType;
        return fileHandler.queryDictFilePath(fileName);
    }

    @Override
    public void importDictData(DictItemResp dictItemResp, List<String> data, User user) {
        // Change dictionary file
        if (Boolean.TRUE.equals(dictFlushEnable) && Boolean.TRUE.equals(dictionaryEnabled)) {
            String fileName = dictItemResp.fetchDictFileName() + Constants.DOT + dictFileType;
            fileHandler.writeFile(data, fileName, false);
        }


        if (!data.isEmpty() && user != null) {
            // 维度值存向量库
            List<DimensionValueDO> dimensionValueDOS;
            dimensionValueDOS = data.stream().map(this::convert2DimValueDO)
                    .filter(line -> Objects.nonNull(line)).toList();
            dimensionValueDOS.forEach(dimensionValueDO -> {
                dimensionValueDO.setDimBizName(dictItemResp.getBizName());
                dimensionValueDO.setModelId(dictItemResp.getModelId());
                dimensionValueDO.setDimId(dictItemResp.getItemId());
            });
            dimensionService.sendDimensionValueEventBatch(dimensionValueDOS, EventType.ADD);
        }
    }
}
