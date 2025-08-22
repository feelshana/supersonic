package com.tencent.supersonic.chat.server.service.impl;

import static com.tencent.supersonic.common.pojo.Constants.POUND;
import static com.tencent.supersonic.common.pojo.Constants.SPACE;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.directory.api.util.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.agent.AgentToolType;
import com.tencent.supersonic.chat.server.agent.DatasetTool;
import com.tencent.supersonic.chat.server.agent.ToolConfig;
import com.tencent.supersonic.chat.server.parser.NL2SQLParser;
import com.tencent.supersonic.chat.server.service.AgentService;
import com.tencent.supersonic.chat.server.service.BiAgentService;
import com.tencent.supersonic.common.bi.BiAgentConfig;
import com.tencent.supersonic.common.bi.BiDataSource;
import com.tencent.supersonic.common.bi.BiDimensionCofig;
import com.tencent.supersonic.common.bi.BiModelConfig;
import com.tencent.supersonic.common.bi.BiModelItem;
import com.tencent.supersonic.common.bi.BiPageConfig;
import com.tencent.supersonic.common.bi.BiTable;
import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AggOperatorEnum;
import com.tencent.supersonic.common.pojo.enums.AppModule;
import com.tencent.supersonic.common.pojo.enums.EngineType;
import com.tencent.supersonic.common.pojo.enums.StatusEnum;
import com.tencent.supersonic.common.pojo.enums.TypeEnums;
import com.tencent.supersonic.common.util.AESEncryptionUtil;
import com.tencent.supersonic.common.util.ChatAppManager;
import com.tencent.supersonic.common.util.HttpUtils;
import com.tencent.supersonic.headless.api.pojo.DataSetDetail;
import com.tencent.supersonic.headless.api.pojo.DataSetModelConfig;
import com.tencent.supersonic.headless.api.pojo.DimValueMap;
import com.tencent.supersonic.headless.api.pojo.Dimension;
import com.tencent.supersonic.headless.api.pojo.Measure;
import com.tencent.supersonic.headless.api.pojo.MetaFilter;
import com.tencent.supersonic.headless.api.pojo.ModelDetail;
import com.tencent.supersonic.headless.api.pojo.enums.DataType;
import com.tencent.supersonic.headless.api.pojo.enums.DimensionType;
import com.tencent.supersonic.headless.api.pojo.enums.ModelDefineType;
import com.tencent.supersonic.headless.api.pojo.request.DataSetReq;
import com.tencent.supersonic.headless.api.pojo.request.DatabaseReq;
import com.tencent.supersonic.headless.api.pojo.request.DictItemReq;
import com.tencent.supersonic.headless.api.pojo.request.DomainReq;
import com.tencent.supersonic.headless.api.pojo.request.ModelReq;
import com.tencent.supersonic.headless.api.pojo.response.DataSetResp;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.api.pojo.response.DictItemResp;
import com.tencent.supersonic.headless.api.pojo.response.DimensionResp;
import com.tencent.supersonic.headless.api.pojo.response.DomainResp;
import com.tencent.supersonic.headless.api.pojo.response.MetricResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.chat.parser.llm.OnePassSCSqlGenStrategy;
import com.tencent.supersonic.headless.server.service.DataSetService;
import com.tencent.supersonic.headless.server.service.DatabaseService;
import com.tencent.supersonic.headless.server.service.DictConfService;
import com.tencent.supersonic.headless.server.service.DictTaskService;
import com.tencent.supersonic.headless.server.service.DimensionService;
import com.tencent.supersonic.headless.server.service.DomainService;
import com.tencent.supersonic.headless.server.service.MetricService;
import com.tencent.supersonic.headless.server.service.ModelService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class BiAgentServiceImpl implements BiAgentService {

    @Value("${s2.bi.model-id:7}")
    private Integer chatModelId;
    @Value("${s2.bi.url}")
    private String biUrl;

    @Autowired
    private DatabaseService databaseService;
    @Autowired
    private DomainService domainService;
    @Autowired
    private ModelService modelService;
    @Autowired
    private DataSetService dataSetService;
    @Autowired
    private DimensionService dimensionService;
    @Autowired
    private MetricService metricService;
    @Autowired
    private AgentService agentService;
    @Autowired
    private DictConfService dictConfService;
    @Autowired
    private DictTaskService dictTaskService;

    @Override
    public Agent createBiAgent(BiAgentConfig config) throws Exception {
        BiModelConfig modelConfig = config.getModel();
        BiPageConfig pageConfig = config.getPageConfig();
        // 参数检查
        if (modelConfig.getSqlConditionParams() != null && !modelConfig.getSqlConditionParams().isEmpty()) {
            throw new IllegalArgumentException("暂不支持带参数的模型创建智能助手");
        }
        // 不支持多表模型
        if (modelConfig.getTables() != null && modelConfig.getTables().size() > 1) {
            throw new IllegalArgumentException("暂不支持含多张表的模型创建智能助手");
        }
        User user = User.getDefaultUser();
        Map<String, List<DimValueMap>> dimAliasMap = null;
        // 删除旧的模型和主题域
        if (config.getAgentId() != null) {
            dimAliasMap = clearOldConfig(config.getAgentId(), user);
        } else {
            dimAliasMap = Collections.emptyMap();
        }
        // 创建数据源
        DatabaseResp databaseResp = createDataSource(config.getDataSource(), user);
        // 创建主题域
        DomainReq domainReq = new DomainReq();
        domainReq.setName("BI-" + modelConfig.getModelName());
        domainReq.setBizName("bi-" + modelConfig.getModelId());
        DomainResp domainResp = domainService.createDomain(domainReq, user);
        // 创建模型
        List<ModelResp> modelResps = createModel(modelConfig, pageConfig, dimAliasMap, user, databaseResp, domainResp);
        // 创建数据集
        DataSetResp dataSetResp = createDataSet(modelConfig, user, domainResp, modelResps);
        // 工具配置
        ToolConfig toolConfig = new ToolConfig();
        DatasetTool datasetTool = new DatasetTool();
        datasetTool.setId(RandomStringUtils.randomAlphanumeric(8));
        datasetTool.setType(AgentToolType.DATASET);
        datasetTool.setDataSetIds(Lists.newArrayList(dataSetResp.getId()));
        toolConfig.getTools().add(datasetTool);
        // 更新助理
        if (config.getAgentId() != null) {
            Agent agent = agentService.getAgent(config.getAgentId());
            agent.setToolConfig(JSONObject.toJSONString(toolConfig));
            agent = agentService.updateAgent(agent, user);
            return agent;
        }
        // 创建智能助理
        Agent agent = new Agent();
        agent.setIsBi(1);
        agent.setAdmins(config.getAdmins());
        agent.setViewers(config.getViewers());
        agent.setToolConfig(JSONObject.toJSONString(toolConfig));
        agent.setName("BI-" + modelConfig.getModelName());
        // 模型配置
        Map<String, ChatApp> chatAppConfig =
                Maps.newHashMap(ChatAppManager.getAllApps(AppModule.CHAT));
        chatAppConfig.values().forEach(app -> app.setChatModelId(this.chatModelId));
        chatAppConfig.get(NL2SQLParser.APP_KEY_MULTI_TURN).setEnable(true);
        ChatApp chatApp = chatAppConfig.get(OnePassSCSqlGenStrategy.APP_KEY);
        String prompt = chatApp.getPrompt();
        prompt = prompt + "\n#其它规则：";
        if (!"1".equals(pageConfig.getIsGroupBy())) {
            prompt = prompt + "\n-这是一个统计结果表，查询禁止使用聚合，只需要SELECT，并展示所有维度，其他例外的情况：计算均值、环比、维度分组统计等则可以聚合";
        }
        prompt = prompt + "\n-维度值处理：";
        if (!CollectionUtils.isEmpty(pageConfig.getDimensionConfigs())) {
            for (BiDimensionCofig item : pageConfig.getDimensionConfigs()) {
                if (item.getDefaultValue() != null) {
                    prompt = prompt + "\n√ 未提及的维度 → " + item.getName() + "赋值'" + item.getDefaultValue() + "'";
                }
            }
        }
        prompt = prompt + "\n√ 提及维度的具体值 → 精准赋值该维度\n比如查询 产品\"咪咕音乐\"的活跃用户->提及维度具体值，产品='咪咕音乐'，未提及的渠道/场景='全部'，省份='全国'";
        chatApp.setPrompt(prompt);
        agent.enableSearch();
        agent.enableFeedback();
        agent.setChatAppConfig(chatAppConfig);
        agent = agentService.createAgent(agent, user);
        return agent;
    }
    
    @Override
    public void biAgentCallback(Agent agent, BiAgentConfig config) {
        try {
            String url = biUrl + "/report/trainingCallback";
            String body = "reportId=%s&agentId=%s&agentName=%s".formatted(config.getReportId(), agent.getId(), agent.getName());
            String result = HttpUtils.post(url, body);
            log.info("回调BI成功：{}", result);
        } catch (Exception e) {
            log.error("回调BI出错", e);
        }
    }

    private Map<String, List<DimValueMap>> clearOldConfig(Integer agentId, User user) {
        Agent agent = agentService.getAgent(agentId);
        if (agent == null) {
            return Collections.emptyMap();
        }
        // 保存维度值别名
        Map<String, List<DimValueMap>> dimAliasMap = new HashMap<>();
        List<DatasetTool> tools = agent.getParserTools(AgentToolType.DATASET);
        for (DatasetTool tool : tools) {
            List<Long> dataSetIds = tool.getDataSetIds();
            for (Long dataSetId : dataSetIds) {
                DataSetResp dataSet = dataSetService.getDataSet(dataSetId);
                if (dataSet == null) {
                    continue;
                }
                dataSetService.delete(dataSetId, user);
                Long domainId = dataSet.getDomainId();
                MetaFilter filter = new MetaFilter();
                filter.setDomainId(domainId);
                List<ModelResp> models = modelService.getModelList(filter);
                for (ModelResp model : models) {
                    MetaFilter modelFilter = new MetaFilter(Lists.newArrayList(model.getId()));
                    List<DimensionResp> dimensions = dimensionService.getDimensions(modelFilter);
                    if (!CollectionUtils.isEmpty(dimensions)) {
                        dimensions.forEach(item -> {
                            List<DimValueMap> dimValueMaps = item.getDimValueMaps();
                            if (!CollectionUtils.isEmpty(dimValueMaps)) {
                                dimAliasMap.put(item.getName(), dimValueMaps);
                            }
                        });
                        List<Long> dimensionIds = dimensions.stream().map(DimensionResp::getId)
                                .collect(Collectors.toList());
                        dimensionService.deleteDimensionBatch(dimensionIds, user);
                    }
                    List<MetricResp> metrics = metricService.getMetrics(filter);
                    if (!CollectionUtils.isEmpty(metrics)) {
                        List<Long> metricIds = metrics.stream().map(MetricResp::getId)
                                .collect(Collectors.toList());
                        metricService.deleteMetricBatch(metricIds, user);
                    }
                    modelService.deleteModel(model.getId(), user);
                }
                domainService.deleteDomain(domainId);
            }
        }
        return dimAliasMap;
    }

    private DataSetResp createDataSet(BiModelConfig config, User user, DomainResp domainResp,
            List<ModelResp> modelResps) {
        DataSetReq dataSetReq = new DataSetReq();
        dataSetReq.setDomainId(domainResp.getId());
        dataSetReq.setName(config.getModelName());
        dataSetReq.setBizName(config.getModelId());
        DataSetDetail dataSetDetail = new DataSetDetail();
        dataSetReq.setDataSetDetail(dataSetDetail);
        List<DataSetModelConfig> dataSetModelConfigs = Lists.newArrayList();
        dataSetDetail.setDataSetModelConfigs(dataSetModelConfigs);
        for (ModelResp modelResp : modelResps) {
            DataSetModelConfig modelConfig = new DataSetModelConfig();
            dataSetModelConfigs.add(modelConfig);
            modelConfig.setId(modelResp.getId());
            MetaFilter filter = new MetaFilter(Lists.newArrayList(modelResp.getId()));
            List<DimensionResp> dimensions = dimensionService.getDimensions(filter);
            if (dimensions != null && !dimensions.isEmpty()) {
                modelConfig.setDimensions(
                        dimensions.stream().map(DimensionResp::getId).collect(Collectors.toList()));
            }
            List<MetricResp> metrics = metricService.getMetrics(filter);
            if (metrics != null && !metrics.isEmpty()) {
                modelConfig.setMetrics(
                        metrics.stream().map(MetricResp::getId).collect(Collectors.toList()));
            }
        }
        DataSetResp dataSetResp = dataSetService.save(dataSetReq, user);
        return dataSetResp;
    }

    private List<ModelResp> createModel(BiModelConfig config, BiPageConfig pageConfig, Map<String, List<DimValueMap>> dimAliasMap,
            User user, DatabaseResp databaseResp, DomainResp domainResp) throws Exception {
        List<ModelResp> modelResps = Lists.newArrayList();
        List<BiModelItem> biDimensions = config.getDimensions();
        List<BiModelItem> biMeasures = config.getMeasures();
        // 拖拽建模
        if (config.getCreateModelType() == 1) {
            List<BiModelItem> customs = processCustom(config.getCustoms());
            BiTable table = config.getTables().get(0);
            String tableName = table.getDatabaseName() + "." + table.getTableName();
            ModelReq modelReq = new ModelReq();
            modelReq.setDatabaseId(databaseResp.getId());
            modelReq.setDomainId(domainResp.getId());
            modelReq.setName(config.getModelName());
            modelReq.setBizName(table.getTableName());
            ModelDetail modelDetail = new ModelDetail();
            modelDetail.setQueryType(ModelDefineType.TABLE_QUERY.getName());
            modelDetail.setTableQuery(tableName);
            modelReq.setModelDetail(modelDetail);
            if (biDimensions != null) {
                List<Dimension> dimensions = Lists.newArrayList();
                modelDetail.setDimensions(dimensions);
                for (BiModelItem modelDimension : biDimensions) {
                    // 非可见的维度或指标跳过
                    if (!"YES".equalsIgnoreCase(modelDimension.getIsLook())) {
                        continue;
                    }
                    Dimension dimension = new Dimension();
                    dimension.setName(modelDimension.getName());
                    Integer columnType = modelDimension.getColumnType();
                    if (columnType != null && columnType == 2) {
                        dimension.setType(DimensionType.time);
                        dimension.setDateFormat(modelDimension.getFormat());
                    } else {
                        dimension.setType(DimensionType.categorical);
                    }
                    dimension.setBizName(modelDimension.getColumnName());
                    dimension.setDescription(modelDimension.getDescription());
                    dimension.setIsCreateDimension(1);
                    dimensions.add(dimension);
                }
            }
            if (biMeasures != null) {
                List<Measure> measures = Lists.newArrayList();
                modelDetail.setMeasures(measures);;
                for (BiModelItem modelMeasure : biMeasures) {
                    // 非可见的维度或指标跳过
                    if (!"YES".equals(modelMeasure.getIsLook())) {
                        continue;
                    }
                    Measure measure = new Measure();
                    measure.setName(modelMeasure.getName());
                    measure.setBizName(modelMeasure.getColumnName());
                    measure.setAgg(AggOperatorEnum.NONE.getOperator());
                    if (modelMeasure.getAggregationType() != null) {
                        AggOperatorEnum aggOperator = AggOperatorEnum.of(modelMeasure.getAggregationType());
                        if (!AggOperatorEnum.UNKNOWN.equals(aggOperator)) {
                            measure.setAgg(aggOperator.getOperator());
                        }
                    }
                    measure.setIsCreateMetric(1);
                    measures.add(measure);
                }
            }
            if (!customs.isEmpty()) {
                for (BiModelItem custom : customs) {
                    // 非可见的维度或指标跳过
                    if (!"YES".equals(custom.getIsLook()) || custom.getType() == null) {
                        continue;
                    }
                    if (custom.getType() == 2) {
                        Dimension dimension = new Dimension();
                        dimension.setName(custom.getName());
                        Integer columnType = custom.getColumnType();
                        if (columnType != null && columnType == 2) {
                            dimension.setType(DimensionType.time);
                            dimension.setDateFormat(custom.getFormat());
                        } else {
                            dimension.setType(DimensionType.categorical);
                        }
                        dimension.setBizName(custom.getColumnName());
                        dimension.setDescription(custom.getDescription());
                        dimension.setIsCreateDimension(1);
                        List<Dimension> dimensions = modelDetail.getDimensions();
                        if (dimensions == null) {
                            dimensions = Lists.newArrayList();
                            modelDetail.setDimensions(dimensions);
                        }
                        dimensions.add(dimension);
                    } else if (custom.getType() == 1) {
                        Measure measure = new Measure();
                        measure.setName(custom.getName());
                        measure.setBizName(custom.getColumnName());
                        measure.setAgg(AggOperatorEnum.NONE.getOperator());
                        if (custom.getAggregationType() != null) {
                            AggOperatorEnum aggOperator = AggOperatorEnum.of(custom.getAggregationType());
                            if (!AggOperatorEnum.UNKNOWN.equals(aggOperator)) {
                                measure.setAgg(aggOperator.getOperator());
                            }
                        }
                        measure.setIsCreateMetric(1);
                        List<Measure> measures = modelDetail.getMeasures();
                        if (measures == null) {
                            measures = Lists.newArrayList();
                            modelDetail.setMeasures(measures);
                        }
                        measures.add(measure);
                    }
                }
            }
            ModelResp modelResp = modelService.createModel(modelReq, user);
            modelResps.add(modelResp);
            // 处理维度字典导入
            if (!CollectionUtils.isEmpty(pageConfig.getDimensionConfigs())) {
                importDimension(user, pageConfig.getDimensionConfigs(), modelResp.getId(), dimAliasMap);
            }
        } else if (config.getCreateModelType() == 2) {
            List<BiModelItem> modelDimensions = config.getDimensions();
            List<BiModelItem> modelMeasures = config.getMeasures();
            ModelReq modelReq = new ModelReq();
            modelReq.setDatabaseId(databaseResp.getId());
            modelReq.setDomainId(domainResp.getId());
            modelReq.setName(config.getModelName());
            modelReq.setBizName(config.getModelId());
            ModelDetail modelDetail = new ModelDetail();
            modelDetail.setQueryType(ModelDefineType.SQL_QUERY.getName());
            modelDetail.setSqlQuery(config.getQuerySql());
            modelReq.setModelDetail(modelDetail);
            if (modelDimensions != null) {
                List<Dimension> dimensions = Lists.newArrayList();
                modelDetail.setDimensions(dimensions);
                for (BiModelItem modelDimension : modelDimensions) {
                    Dimension dimension = new Dimension();
                    dimension.setName(modelDimension.getName());
                    Integer columnType = modelDimension.getColumnType();
                    if (columnType != null && columnType == 2) {
                        dimension.setType(DimensionType.time);
                        dimension.setDateFormat(modelDimension.getFormat());
                    } else {
                        dimension.setType(DimensionType.categorical);
                    }
                    dimension.setBizName(modelDimension.getName());
                    dimension.setIsCreateDimension(1);
                    dimension.setDescription(modelDimension.getDescription());
                    dimensions.add(dimension);
                }
            }
            if (modelMeasures != null) {
                List<Measure> measures = Lists.newArrayList();
                modelDetail.setMeasures(measures);;
                for (BiModelItem modelMeasure : modelMeasures) {
                    Measure measure = new Measure();
                    measure.setName(modelMeasure.getName());
                    measure.setBizName(modelMeasure.getName());
                    measure.setAgg(AggOperatorEnum.NONE.getOperator());
                    if (modelMeasure.getAggregationType() != null) {
                        AggOperatorEnum aggOperator = AggOperatorEnum.of(modelMeasure.getAggregationType());
                        if (!AggOperatorEnum.UNKNOWN.equals(aggOperator)) {
                            measure.setAgg(aggOperator.getOperator());
                        }
                    }
                    measure.setIsCreateMetric(1);
                    measures.add(measure);
                }
            }
            ModelResp modelResp = modelService.createModel(modelReq, user);
            modelResps.add(modelResp);
            // 处理维度字典导入
            if (!CollectionUtils.isEmpty(pageConfig.getDimensionConfigs())) {
                importDimension(user, pageConfig.getDimensionConfigs(), modelResp.getId(), dimAliasMap);
            }
        } else {
            throw new IllegalArgumentException("不支持的建模类型 : " + config.getCreateModelType());
        }
        return modelResps;
    }

    private void importDimension(User user, List<BiDimensionCofig> dimensionConfigs, Long modelId,
            Map<String, List<DimValueMap>> dimAliasMap) {
        MetaFilter filter = new MetaFilter();
        filter.setModelIds(Collections.singletonList(modelId));
        for (BiDimensionCofig dimensionConfig : dimensionConfigs) {
            List<String> values = dimensionConfig.getValues();
            if (!CollectionUtils.isEmpty(values)) {
                filter.setName(dimensionConfig.getName());
                List<DimensionResp> resps = dimensionService.getDimensions(filter);
                if (resps == null || resps.size() != 1) {
                    continue;
                }
                DimensionResp resp = resps.get(0);
                DictItemReq dictItemReq = new DictItemReq();
                dictItemReq.setType(TypeEnums.DIMENSION);
                dictItemReq.setItemId(resp.getId());
                // 导入的维度值锁定不允许刷新
                dictItemReq.setStatus(StatusEnum.ONLINE);
                dictItemReq.setLocked(1);
                DictItemResp dictItemResp = dictConfService.addDictConf(dictItemReq, user);
                String nature = dictItemResp.getNature();
                List<String> lines = values.stream().map(value -> {
                    if (!StringUtils.isEmpty(value)) {
                        value = value.replace(SPACE, POUND);
                    }
                    return String.format("%s %s %s", value, nature, 1L);
                }).toList();
                dictTaskService.importDictData(dictItemResp, lines, user);
                List<DimValueMap> alias = dimAliasMap.get(dimensionConfig.getName());
                if (alias != null) {
                    dimensionService.updateDimValueAliasBatch(resp.getId(), alias, user);
                }
            }
        }
    }

    private List<BiModelItem> processCustom(List<BiModelItem> customs) {
        if (CollectionUtils.isEmpty(customs)) {
            return Collections.emptyList();
        }
        for (BiModelItem custom : customs) {
            String columnName = custom.getColumnName();
            Pattern pattern = Pattern.compile("\\$\\{([^}]+)}");
            Matcher matcher = pattern.matcher(columnName);
            while (matcher.find()) {
                String placeholder = matcher.group(1);
                String[] parts = placeholder.split("\\.");
                if (parts.length != 4) {
                    throw new IllegalArgumentException("无效占位符格式：" + placeholder);
                }
                String fullPlaceholder = "${" + placeholder + "}";
                columnName = columnName.replace(fullPlaceholder, parts[3]);
            }
            columnName = columnName.replace('\"', '\'');
            custom.setColumnName(columnName);
        }
        return customs;
    }
    
    private DatabaseResp createDataSource(BiDataSource dataSource, User user) {
        DatabaseReq databaseReq = new DatabaseReq();
        databaseReq.setName("BI-" + dataSource.getName());
        switch (dataSource.getType()) {
            case "mysql" -> {
                databaseReq.setType(EngineType.MYSQL.getName());
                databaseReq.setVersion("5.7");
            }
            case "doris" -> databaseReq.setType(EngineType.DORIS.getName());
            case "clickhouse" -> databaseReq.setType(EngineType.CLICKHOUSE.getName());
            default -> throw new IllegalArgumentException("不支持的数据库类型 : " + dataSource.getType());
        }
        // 检查是否已有该数据源
        List<DatabaseResp> databases =
                databaseService.getDatabaseByType(DataType.urlOf(dataSource.getConnectionUrl()));
        if (databases != null && !databases.isEmpty()) {
            for (DatabaseResp databaseResp : databases) {
                if (Strings.equals(dataSource.getConnectionUrl(), databaseResp.getUrl())
                        && Strings.equals(dataSource.getUserName(), databaseResp.getUsername())) {
                    return databaseResp;
                }
            }
        }
        databaseReq.setUrl(dataSource.getConnectionUrl());
        databaseReq.setUsername(dataSource.getUserName());
        databaseReq.setPassword(AESEncryptionUtil.aesEncryptECB(dataSource.getPassword()));
        databaseReq.setSchema(dataSource.getDefaultDatabase());
        DatabaseResp databaseResp = databaseService.createOrUpdateDatabase(databaseReq, user);
        return databaseResp;
    }

}
