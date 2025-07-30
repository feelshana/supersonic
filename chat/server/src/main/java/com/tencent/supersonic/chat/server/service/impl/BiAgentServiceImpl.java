package com.tencent.supersonic.chat.server.service.impl;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.directory.api.util.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.agent.AgentToolType;
import com.tencent.supersonic.chat.server.agent.DatasetTool;
import com.tencent.supersonic.chat.server.agent.ToolConfig;
import com.tencent.supersonic.chat.server.service.AgentService;
import com.tencent.supersonic.chat.server.service.BiAgentService;
import com.tencent.supersonic.common.bi.BiDataSource;
import com.tencent.supersonic.common.bi.BiModelConfig;
import com.tencent.supersonic.common.bi.BiModelItem;
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
import com.tencent.supersonic.headless.api.pojo.DataSetDetail;
import com.tencent.supersonic.headless.api.pojo.DataSetModelConfig;
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
import com.tencent.supersonic.headless.api.pojo.request.DictSingleTaskReq;
import com.tencent.supersonic.headless.api.pojo.request.DomainReq;
import com.tencent.supersonic.headless.api.pojo.request.MetaBatchReq;
import com.tencent.supersonic.headless.api.pojo.request.ModelReq;
import com.tencent.supersonic.headless.api.pojo.response.DataSetResp;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.api.pojo.response.DimensionResp;
import com.tencent.supersonic.headless.api.pojo.response.DomainResp;
import com.tencent.supersonic.headless.api.pojo.response.MetricResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.chat.parser.llm.OnePassSCSqlGenStrategy;
import com.tencent.supersonic.headless.server.pojo.DimensionsFilter;
import com.tencent.supersonic.headless.server.service.DataSetService;
import com.tencent.supersonic.headless.server.service.DatabaseService;
import com.tencent.supersonic.headless.server.service.DictConfService;
import com.tencent.supersonic.headless.server.service.DictTaskService;
import com.tencent.supersonic.headless.server.service.DimensionService;
import com.tencent.supersonic.headless.server.service.DomainService;
import com.tencent.supersonic.headless.server.service.MetricService;
import com.tencent.supersonic.headless.server.service.ModelService;


@Service
public class BiAgentServiceImpl implements BiAgentService {

    @Value("${s2.bi.model-id:7}")
    private Integer chatModelId;

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
    public Agent createBiAgent(BiModelConfig config) throws Exception {
        // 参数检查
        if (config.getSqlConditionParams() != null && !config.getSqlConditionParams().isEmpty()) {
            throw new IllegalArgumentException("暂不支持带参数的模型创建智能助手");
        }
        // 不支持多表模型
        if (config.getTables() != null && config.getTables().size() > 1) {
            throw new IllegalArgumentException("暂不支持含多张表的模型创建智能助手");
        }
        User user = User.getDefaultUser();
        // 删除旧的模型和主题域
        if (config.getAgentId() != null) {
            clearOldConfig(config.getAgentId(), user);
        }
        // 创建数据源
        DatabaseResp databaseResp = createDataSource(config, user);
        // 创建主题域
        DomainReq domainReq = new DomainReq();
        domainReq.setName("BI-" + config.getModelName());
        domainReq.setBizName("bi-" + config.getModelId());
        DomainResp domainResp = domainService.createDomain(domainReq, user);
        // 创建模型
        List<ModelResp> modelResps = createModel(config, user, databaseResp, domainResp);
        // 创建数据集
        DataSetResp dataSetResp = createDataSet(config, user, domainResp, modelResps);
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
        agent.setToolConfig(JSONObject.toJSONString(toolConfig));
        agent.setName("BI-" + config.getModelName());
        // 模型配置
        Map<String, ChatApp> chatAppConfig =
                Maps.newHashMap(ChatAppManager.getAllApps(AppModule.CHAT));
        chatAppConfig.values().forEach(app -> app.setChatModelId(this.chatModelId));
        if (!"1".equals(config.getIsGroupBy())) {
            ChatApp chatApp = chatAppConfig.get(OnePassSCSqlGenStrategy.APP_KEY);
            chatApp.setPrompt(chatApp.getPrompt() + "\n#规则:\n1.不要对结果进行聚合。");
        }
        agent.enableSearch();
        agent.enableFeedback();
        agent.setChatAppConfig(chatAppConfig);
        agent = agentService.createAgent(agent, user);
        return agent;
    }

    private void clearOldConfig(Integer agentId, User user) {
        Agent agent = agentService.getAgent(agentId);
        if (agent == null) {
            return;
        }
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
                        List<Long> dimensionIds = dimensions.stream().map(DimensionResp::getId)
                                .collect(Collectors.toList());
                        MetaBatchReq batchReq = new MetaBatchReq();
                        batchReq.setIds(dimensionIds);
                        batchReq.setStatus(StatusEnum.DELETED.getCode());
                        dimensionService.batchUpdateStatus(batchReq, user);
                    }
                    List<MetricResp> metrics = metricService.getMetrics(filter);
                    if (!CollectionUtils.isEmpty(metrics)) {
                        List<Long> metricIds = metrics.stream().map(MetricResp::getId)
                                .collect(Collectors.toList());
                        MetaBatchReq batchReq = new MetaBatchReq();
                        batchReq.setIds(metricIds);
                        batchReq.setStatus(StatusEnum.DELETED.getCode());
                        metricService.batchUpdateStatus(batchReq, user);
                    }
                    modelService.deleteModel(model.getId(), user);
                }
                domainService.deleteDomain(domainId);
            }
        }
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

    private List<ModelResp> createModel(BiModelConfig config, User user, DatabaseResp databaseResp,
            DomainResp domainResp) throws Exception {
        List<ModelResp> modelResps = Lists.newArrayList();
        List<BiModelItem> biDimensions = config.getDimensions();
        List<BiModelItem> biMeasures = config.getMeasures();
        // 拖拽建模
        if (config.getCreateModelType() == 1) {
            Set<String> dictDimensions = Sets.newHashSet();
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
                    dimension.setIsCreateDimension(1);
                    dimensions.add(dimension);
                    if ("YES".equalsIgnoreCase(modelDimension.getIsDict())) {
                        dictDimensions.add(modelDimension.getName());
                    }
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
                    measure.setIsCreateMetric(1);
                    measures.add(measure);
                }
            }
            if (!customs.isEmpty()) {
                for (BiModelItem custom : customs) {
                    // 非可见的维度或指标跳过
                    if (!"YES".equals(custom.getIsLook())) {
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
                        dimension.setIsCreateDimension(1);
                        List<Dimension> dimensions = modelDetail.getDimensions();
                        if (dimensions == null) {
                            dimensions = Lists.newArrayList();
                            modelDetail.setDimensions(dimensions);
                        }
                        dimensions.add(dimension);
                        if ("YES".equalsIgnoreCase(custom.getIsDict())) {
                            dictDimensions.add(custom.getName());
                        }
                    } else if (custom.getType() == 1) {
                        Measure measure = new Measure();
                        measure.setName(custom.getName());
                        measure.setBizName(custom.getColumnName());
                        measure.setAgg(AggOperatorEnum.NONE.getOperator());
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
            if (!dictDimensions.isEmpty()) {
                createDimensionDict(user, dictDimensions);
            }
        } else if (config.getCreateModelType() == 2) {
            Set<String> dictDimensions = Sets.newHashSet();
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
                    dimensions.add(dimension);
                    if ("YES".equalsIgnoreCase(modelDimension.getIsDict())) {
                        dictDimensions.add(modelDimension.getName());
                    }
                }
            }
            if (modelMeasures != null) {
                List<Measure> measures = Lists.newArrayList();
                modelDetail.setMeasures(measures);;
                for (BiModelItem modelMeasure : modelMeasures) {
                    Measure measure = new Measure();
                    measure.setName(modelMeasure.getName());
                    measure.setBizName(modelMeasure.getName());
                    measure.setAgg(AggOperatorEnum.SUM.getOperator());
                    measure.setIsCreateMetric(1);
                    measures.add(measure);
                }
            }
            ModelResp modelResp = modelService.createModel(modelReq, user);
            modelResps.add(modelResp);
            // 处理维度字典导入
            if (!dictDimensions.isEmpty()) {
                createDimensionDict(user, dictDimensions);
            }
        } else {
            throw new IllegalArgumentException("不支持的建模类型 : " + config.getCreateModelType());
        }
        return modelResps;
    }

    private void createDimensionDict(User user, Set<String> dictDimensions) {
        DimensionsFilter filter = new DimensionsFilter();
        filter.setDimensionNames(Lists.newArrayList(dictDimensions));
        List<DimensionResp> queryDimensions = dimensionService.queryDimensions(filter);
        for (DimensionResp dimensionResp : queryDimensions) {
            DictItemReq dictItemReq = new DictItemReq();
            dictItemReq.setType(TypeEnums.DIMENSION);
            dictItemReq.setItemId(dimensionResp.getId());
            dictItemReq.setStatus(StatusEnum.ONLINE);
            dictConfService.addDictConf(dictItemReq, user);
            DictSingleTaskReq taskReq = DictSingleTaskReq.builder()
                    .type(TypeEnums.DIMENSION).itemId(dimensionResp.getId()).build();
            dictTaskService.addDictTask(taskReq, user);
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
    
    private DatabaseResp createDataSource(BiModelConfig config, User user) {
        BiDataSource dataSource = config.getDataSource();
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
