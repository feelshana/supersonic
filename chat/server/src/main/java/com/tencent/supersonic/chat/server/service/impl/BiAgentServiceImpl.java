package com.tencent.supersonic.chat.server.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.tencent.supersonic.auth.api.authentication.request.UserReq;
import com.tencent.supersonic.auth.api.authentication.service.UserService;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.agent.AgentTool;
import com.tencent.supersonic.chat.server.agent.AgentToolType;
import com.tencent.supersonic.chat.server.agent.DatasetTool;
import com.tencent.supersonic.chat.server.agent.ToolConfig;

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
import com.tencent.supersonic.common.pojo.enums.*;
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
import com.tencent.supersonic.headless.api.pojo.request.*;
import com.tencent.supersonic.headless.api.pojo.response.*;
import com.tencent.supersonic.headless.chat.parser.llm.OnePassSCSqlGenStrategy;
import com.tencent.supersonic.headless.server.persistence.dataobject.DimensionValueDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.DomainDO;
import com.tencent.supersonic.headless.server.service.*;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.directory.api.util.Strings;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;



@Slf4j
@Service
public class BiAgentServiceImpl implements BiAgentService {

    @Value("${s2.bi.model-id:2}")
    private Integer chatModelId;
    @Value("${s2.bi.url}")
    private String biUrl;
    @Value("${s2.dictionary.enabled:false}")
    private Boolean dictionaryEnabled;
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
    @Autowired
    private UserService userService;
    @Autowired
    private TermService termService;

    @Override
    @Transactional
    public Agent createBiAgent(BiAgentConfig config) throws Exception {
        BiModelConfig modelConfig = config.getModel();
        BiPageConfig pageConfig = config.getPageConfig();
        // 参数检查
        validateConfig(modelConfig);
        // 检查BI的用户名，没有就创建
        checkBIUsers(config.getAdmins(), config.getViewers());
        User user = User.getDefaultUser();
        Map<String, List<DimValueMap>> dimAliasMap;
        Map<String, List<String>> oldDimDefaultValuesMap;
        String domainName = "BI-" + config.getReportName();
        String domainBizName = "bi-" + config.getReportId();

        // 查询重复的主题域
        List<DomainDO> domains = domainService.getDomainByBizName(domainName, domainBizName);
        log.info("查询主题域信息, domainName: {}, domainBizName: {}, 结果数量: {}", domainName, domainBizName,
                domains.size());
        // 处理重复主题域 删除多余的
        DomainDO keepDomain = normalizeDomainAndCleanRedundant(domains, domainBizName, user);

        // 查询重复名称的智能助手
        List<Agent> agents = agentService.getAgentByName(domainName);
        log.info("查询智能助手信息, agentName: {}, 结果数量: {}", domainName, agents.size());

        // 判断是否唯一,如果存在多个同样的助理，清除多余的只保留一个agent
        Agent uniqueAgent = findUniqueAgent(agents, keepDomain == null ? null : keepDomain.getId());

        // 创建或复用数据源
        log.info("开始创建数据源");
        DatabaseResp databaseResp = createDataSource(config, user);

        // 创建或更新主题域
        log.info("开始创建或更新主题域");
        DomainResp domainResp = upsertDomain(keepDomain, domainName, domainBizName,
                config.getAdmins(), config.getViewers(), user);

        // 加载已有模型中的维度别名/默认值，用于后续保留
        Map<String, Object> existingModelMeta = loadExistingModelMeta(domainResp.getId());
        dimAliasMap = (Map<String, List<DimValueMap>>) existingModelMeta.getOrDefault("dimAliasMap",
                Collections.emptyMap());
        oldDimDefaultValuesMap = (Map<String, List<String>>) existingModelMeta
                .getOrDefault("dimDefaultValuesMap", new HashMap<>());

        // 创建或更新模型
        log.info("开始创建或更新模型");
        List<ModelResp> modelResps =
                createModel(modelConfig, pageConfig, dimAliasMap, oldDimDefaultValuesMap, user,
                        databaseResp, domainResp, config.getAdmins(), config.getViewers());

        // 创建数据集
        log.info("开始创建数据集");
        DataSetResp dataSetResp =
                createDataSet(modelConfig, user, domainResp, modelResps, config.getAdmins());
        // 工具配置
        ToolConfig toolConfig = createToolConfig(dataSetResp);

        // 创建或者更新助理
        Agent agent = createOrUpdateAgent(config, uniqueAgent, toolConfig, user, domainName);
        log.info("智能助手创建完成, id: {}", agent.getId());
        return agent;
    }



    private ToolConfig createToolConfig(DataSetResp dataSetResp) {
        ToolConfig toolConfig = new ToolConfig();
        DatasetTool datasetTool = new DatasetTool();
        datasetTool.setId(RandomStringUtils.randomAlphanumeric(8));
        datasetTool.setType(AgentToolType.DATASET);
        datasetTool.setDataSetIds(Lists.newArrayList(dataSetResp.getId()));
        toolConfig.getTools().add(datasetTool);
        return toolConfig;
    }

    private DomainResp createDomain(String domainName, String domainBizName, List<String> admins,
            List<String> viewers, User user) {
        DomainReq domainReq = new DomainReq();
        domainReq.setName(domainName);
        domainReq.setBizName(domainBizName);
        domainReq.setAdmins(admins);
        domainReq.setViewers(viewers);
        DomainResp domainResp = domainService.createDomain(domainReq, user);
        return domainResp;
    }

    private DomainResp upsertDomain(DomainDO keepDomain, String domainName, String domainBizName,
            List<String> admins, List<String> viewers, User user) {
        if (keepDomain == null) {
            log.info("主题域不存在，执行新增, bizName: {}", domainBizName);
            return createDomain(domainName, domainBizName, admins, viewers, user);
        }
        DomainResp existingDomain = domainService.getDomain(keepDomain.getId());
        if (existingDomain == null) {
            return createDomain(domainName, domainBizName, admins, viewers, user);
        }

        boolean sameName = StringUtils.equals(existingDomain.getName(), domainName);
        boolean sameBizName = StringUtils.equals(existingDomain.getBizName(), domainBizName);
        boolean sameAdmins = isSameList(existingDomain.getAdmins(), admins);
        boolean sameViewers = isSameList(existingDomain.getViewers(), viewers);

        if (sameName && sameBizName && sameAdmins && sameViewers) {
            log.info("主题域无变化，跳过更新, id: {}", existingDomain.getId());
            return existingDomain;
        }

        DomainUpdateReq updateReq = new DomainUpdateReq();
        updateReq.setId(existingDomain.getId());
        updateReq.setName(domainName);
        updateReq.setBizName(domainBizName);
        updateReq.setAdmins(admins);
        updateReq.setViewers(viewers);
        updateReq.setParentId(existingDomain.getParentId());
        updateReq.setIsOpen(existingDomain.getIsOpen());
        updateReq.setAdminOrgs(existingDomain.getAdminOrgs());
        updateReq.setViewOrgs(existingDomain.getViewOrgs());
        log.info("主题域存在变化，执行更新, id: {}", existingDomain.getId());
        return domainService.updateDomain(updateReq, user);
    }

    private DomainDO normalizeDomainAndCleanRedundant(List<DomainDO> domains, String domainBizName,
            User user) {
        if (CollectionUtils.isEmpty(domains)) {
            return null;
        }
        List<DomainDO> sortedDomains = domains.stream().filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(DomainDO::getId)).toList();
        DomainDO keepDomain = sortedDomains.getFirst();
        if (sortedDomains.size() == 1) {
            return keepDomain;
        }

        Set<Long> redundantDomainIds = sortedDomains.stream().skip(1).map(DomainDO::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        log.warn("检测到重复主题域，将清理冗余主题域, bizName: {}, keepDomainId: {}, redundantDomainIds: {}",
                domainBizName, keepDomain.getId(), redundantDomainIds);
        cleanupDomainResources(redundantDomainIds, user);
        return keepDomain;
    }

    private void cleanupDomainResources(Set<Long> domainIds, User user) {
        if (CollectionUtils.isEmpty(domainIds)) {
            return;
        }
        for (Long domainId : domainIds) {
            MetaFilter filterDataSet = new MetaFilter();
            filterDataSet.setDomainId(domainId);
            List<DataSetResp> dataSetResps = dataSetService.getDataSetList(filterDataSet);
            if (!CollectionUtils.isEmpty(dataSetResps)) {
                for (DataSetResp dataSetResp : dataSetResps) {
                    dataSetService.delete(dataSetResp.getId(), user);
                }
            }
            clearModel(user, domainId, new HashMap<>(), new HashMap<>());
            termService.deleteByDomainId(domainId);
            domainService.deleteDomain(domainId);
            log.info("已清理冗余主题域及关联资源, domainId: {}", domainId);
        }
    }



    private Map<String, Object> loadExistingModelMeta(Long domainId) {
        Map<String, List<DimValueMap>> dimAliasMap = new HashMap<>();
        Map<String, List<String>> dimDefaultValuesMap = new HashMap<>();

        MetaFilter filter = new MetaFilter();
        filter.setDomainId(domainId);
        List<ModelResp> models = modelService.getModelList(filter);
        for (ModelResp model : models) {
            MetaFilter modelFilter = new MetaFilter(Lists.newArrayList(model.getId()));
            List<DimensionResp> dimensions = dimensionService.getDimensions(modelFilter);
            for (DimensionResp dimension : dimensions) {
                if (!CollectionUtils.isEmpty(dimension.getDefaultValues())) {
                    dimDefaultValuesMap.put(dimension.getName(), dimension.getDefaultValues());
                }
                if (!CollectionUtils.isEmpty(dimension.getDimValueMaps())) {
                    dimAliasMap.put(dimension.getName(), dimension.getDimValueMaps());
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("dimAliasMap", dimAliasMap);
        result.put("dimDefaultValuesMap", dimDefaultValuesMap);
        return result;
    }

    /**
     * 验证配置参数
     */
    private void validateConfig(BiModelConfig modelConfig) {
        if (modelConfig.getSqlConditionParams() != null
                && !modelConfig.getSqlConditionParams().isEmpty()) {
            throw new IllegalArgumentException("暂不支持带参数的模型创建智能助手");
        }
        // 不支持多表模型
        if (modelConfig.getTables() != null && modelConfig.getTables().size() > 1) {
            throw new IllegalArgumentException("暂不支持含多张表的模型创建智能助手");
        }
    }

    private Agent createOrUpdateAgent(BiAgentConfig config, Agent uniqueAgent,
            ToolConfig toolConfig, User user, String domainName) {
        // 更新智能助理
        if (config.getAgentId() != null) {
            log.info("更新已有智能助手, id: {}", config.getAgentId());
            Agent agent = agentService.getAgent(config.getAgentId());
            return updateExistingAgent(agent, toolConfig, config, user);
        }
        if (uniqueAgent != null) {
            log.info("更新主题域对应的智能助手, id: {}", uniqueAgent.getId());
            return updateExistingAgent(uniqueAgent, toolConfig, config, user);
        }

        // 创建智能助理
        String newRulesContent = buildNewRulesContent(config.getPageConfig(), config.getModel());
        log.info("创建新智能助手");
        return createNewAgent(config, toolConfig, user, domainName, newRulesContent);
    }

    /**
     * 构建新的规则内容
     */
    private String buildNewRulesContent(BiPageConfig pageConfig, BiModelConfig model) {
        StringBuilder newRules = new StringBuilder();
        // index为编号，根据index进行排序
        int index = 1;
        newRules.append("Sql生成的限制条件：");
        newRules.append("\n").append(index++)
                .append(".若问题涉及用户数的指标（如活跃用户数、新增用户数），禁止使用 SUM()，应直接返回原始粒度或多维明细数据。");
        newRules.append("\n").append("--")
                .append("对于日表，当问某月数据时，查询日期范围内的多条日数据(例如：“12月的活跃用户数”应返回多条记录，每条对应一天)。");
        newRules.append("\n").append("--")
                .append("对于月表，当问某年数据时，查询日期范围内的多条月数据(例如：“2025年的活跃用户数”应返回多条记录，每条对应一个月)。");
        // if (!"1".equals(pageConfig.getIsGroupBy())) {
        // newRules.append("-这是一个统计结果表，查询禁止使用聚合，只需要SELECT.");
        // }



        // if (!CollectionUtils.isEmpty(pageConfig.getDimensionConfigs())) {
        // for (int i = 0; i < pageConfig.getDimensionConfigs().size(); i++) {
        // BiDimensionCofig item = pageConfig.getDimensionConfigs().get(i);
        // if (item.getDefaultValues() != null && !item.getDefaultValues().isEmpty()) {
        // String itemValues =
        // item.getDefaultValues().size() == 1 ? item.getDefaultValues().getFirst()
        // : "[" + String.join(",", item.getDefaultValues()) + "]";
        // newRules.append("\n").append(i + 2).append(".当查询的问题不涉及").append(item.getName())
        // .append("时，应加上条件").append(item.getName()).append("='")
        // .append(itemValues).append("'，当查询的问题需要具体").append(item.getName())
        // .append("这类情况时，应该加上条件").append(item.getName()).append("!='")
        // .append(itemValues).append("'");
        // }
        // }
        // newRules.append("\n").append(pageConfig.getDimensionConfigs().size() + 2)
        // .append(". 提及维度的具体值 → 精准赋值该维度");
        // }
        if (model.getDimensions() != null && !model.getDimensions().isEmpty()) {
            List<String> dimensionNames = new ArrayList<>();
            model.getDimensions().stream().filter(BiModelItem::isSelected)
                    .forEach(item -> dimensionNames.add(item.getName()));
            // 生成select的字段必须包含以下维度的提示词
            if (!dimensionNames.isEmpty()) {
                newRules.append("\n").append(index++).append(".select的字段必须包含以下维度:");
                newRules.append("\n").append(String.join(",", dimensionNames));
                // newRules.append("\n当问题明确需要图形展示时，应优先选择适合图形展示的字段组合，而非固定维度字段的表格展示。");
            }
            // 判断如果model.getDimensions()和model.getCustoms()集合中的维度对象中存在包含省份和城市的为维度，则进行省份和城市的提示词生成
            ArrayList<BiModelItem> list = new ArrayList<>();
            list.addAll(model.getDimensions());
            list.addAll(model.getCustoms());

            // 检查是否存在包含"省份"的维度 和 包含"市"的维度
            boolean hasProvinceDimension =
                    list.stream().anyMatch(item -> item.getName().contains("省份"));
            boolean hasCityDimension = list.stream().anyMatch(item -> item.getName().contains("市"));

            if (hasProvinceDimension && hasCityDimension) {
                newRules.append("\n").append(index++).append(
                        ".省份维度包含默认维度值全国，城市维度包含全省，其他维度包含全部，当用户问题需要查询各省，各市，各某维度，某维度topN情况等，需要排除默认值全国或全省或全部。");
            }
        }
        newRules.append("\n").append(index++).append(".select的指标字段：根据语义理解后进行筛选,除开维度值的查询，都应该包含指标");
        newRules.append("\n").append(index++).append(".涉及两组数据计算同环比，差值等时，必须通过left join实现");

        return newRules.toString();
    }

    /**
     * 更新现有助理
     */
    private Agent updateExistingAgent(Agent agent, ToolConfig toolConfig, BiAgentConfig config,
            User user) {
        String newToolConfig = JSONObject.toJSONString(toolConfig);
        boolean sameToolConfig = isSameToolConfigIgnoreId(agent.getToolConfig(), newToolConfig);
        boolean sameAdmins = isSameList(agent.getAdmins(), config.getAdmins());
        boolean sameViewers = isSameList(agent.getViewers(), config.getViewers());


        if (sameToolConfig && sameAdmins && sameViewers) {
            log.info("智能助手无变化，跳过更新, id: {}", agent.getId());
            return agent;
        }

        agent.setToolConfig(newToolConfig);
        agent.setAdmins(config.getAdmins());
        agent.setViewers(config.getViewers());

        // Map<String, ChatApp> chatAppConfig = agent.getChatAppConfig();
        // ChatApp chatApp = chatAppConfig.get(OnePassSCSqlGenStrategy.APP_KEY);
        //
        // if (chatApp != null) {
        //// updatePromptWithNewRules(chatApp, newRulesContent);
        // }

        return agentService.updateAgent(agent, user);
    }



    /**
     * 创建新助理
     */
    private Agent createNewAgent(BiAgentConfig config, ToolConfig toolConfig, User user,
            String domainName, String newRulesContent) {
        Agent agent = new Agent();
        agent.setIsBi(1);
        agent.setAdmins(config.getAdmins());
        agent.setViewers(config.getViewers());
        agent.setToolConfig(JSONObject.toJSONString(toolConfig));
        agent.setName(domainName);
        agent.setReportId(config.getReportId());

        // 模型配置
        Map<String, ChatApp> allApps = ChatAppManager.getAllApps(AppModule.CHAT);
        Map<String, ChatApp> chatAppConfig = Maps.newHashMap();
        for (String key : allApps.keySet()) {
            ChatApp chatApp = allApps.get(key);
            ChatApp chatAppNew = new ChatApp();
            BeanUtils.copyProperties(chatApp, chatAppNew);
            chatAppConfig.put(key, chatAppNew);
        }
        chatAppConfig.values().forEach(app -> app.setChatModelId(this.chatModelId));

        // 处理提示词
        ChatApp chatApp = chatAppConfig.get(OnePassSCSqlGenStrategy.APP_KEY);
        if (chatApp != null && chatApp.getPrompt() != null) {
            String newPrompt = chatApp.getPrompt() + "\n" + newRulesContent;
            chatApp.setPrompt(newPrompt);
        }

        agent.enableSearch();
        agent.enableFeedback();
        agent.setChatAppConfig(chatAppConfig);
        return agentService.createAgent(agent, user);
    }

    private void checkBIUsers(List<String> admins, List<String> viewers) {
        ensureUsersExist(admins);
        ensureUsersExist(viewers);
    }

    private void ensureUsersExist(List<String> userNames) {
        if (CollectionUtils.isEmpty(userNames)) {
            return;
        }
        for (String userName : userNames) {
            User user = userService.getUserByName(userName);
            if (user == null) {
                UserReq userReq = new UserReq();
                userReq.setName(userName);
                userReq.setPassword("123456");
                userReq.setNewPassword("123456");
                userService.register(userReq);
            }
        }
    }

    // 判断是否唯一,如果存在多个同样的助理，清除多余的只保留一个agent
    private Agent findUniqueAgent(List<Agent> agents, Long domainId) {
        if (domainId == null || CollectionUtils.isEmpty(agents)) {
            return null;
        }
        List<Agent> matchedAgents = new ArrayList<>();
        for (Agent agent : agents) {
            List<DatasetTool> tools = agent.getParserTools(AgentToolType.DATASET);
            if (CollectionUtils.isEmpty(tools)) {
                agentService.deleteAgent(agent.getId());
                continue;
            }

            boolean hasValidDataSet = false;
            boolean hasMatchDomain = false;
            boolean hasMismatchDomain = false;
            for (DatasetTool tool : tools) {
                List<Long> dataSetIds = tool.getDataSetIds();
                if (CollectionUtils.isEmpty(dataSetIds)) {
                    continue;
                }
                for (Long dataSetId : dataSetIds) {
                    DataSetResp dataSet = dataSetService.getDataSet(dataSetId);
                    if (dataSet == null) {
                        continue;
                    }
                    hasValidDataSet = true;
                    if (domainId.equals(dataSet.getDomainId())) {
                        hasMatchDomain = true;
                    } else {
                        hasMismatchDomain = true;
                    }
                }
            }

            if (!hasValidDataSet || !hasMatchDomain || hasMismatchDomain) {
                agentService.deleteAgent(agent.getId());
                continue;
            }
            matchedAgents.add(agent);
        }

        if (CollectionUtils.isEmpty(matchedAgents)) {
            return null;
        }

        List<Agent> uniqueAgents =
                matchedAgents.stream().sorted(Comparator.comparingInt(Agent::getId)).toList();
        if (uniqueAgents.size() > 1) {
            log.info("存在{}个同名智能助手，将删除冗余助理", uniqueAgents.size());
            for (int i = 1; i < uniqueAgents.size(); i++) {
                agentService.deleteAgent(uniqueAgents.get(i).getId());
            }
        }
        return uniqueAgents.getFirst();
    }


    @Override
    public boolean biAgentCallback(Agent agent, BiAgentConfig config) {
        try {
            String url = biUrl + "/report/trainingCallback";
            String body = "reportId=%s&agentId=%s&agentName=%s".formatted(config.getReportId(),
                    agent.getId(), agent.getName());
            String result = HttpUtils.post(url, body);
            log.info("回调BI成功：{}", result);
            return true;
        } catch (Exception e) {
            log.error("回调BI出错", e);
            return false;
        }
    }



    private void clearModel(User user, Long domainId, Map<String, List<String>> dimDefaultValuesMap,
            Map<String, List<DimValueMap>> dimAliasMap) {
        MetaFilter filter = new MetaFilter();
        filter.setDomainId(domainId);
        List<ModelResp> models = modelService.getModelList(filter);
        for (ModelResp model : models) {
            MetaFilter modelFilter = new MetaFilter(Lists.newArrayList(model.getId()));
            List<DimensionResp> dimensions = dimensionService.getDimensions(modelFilter);
            if (!CollectionUtils.isEmpty(dimensions)) {
                dimensions.forEach(item -> {
                    // 保存维度默认值
                    if (item.getDefaultValues() != null && !item.getDefaultValues().isEmpty()) {
                        dimDefaultValuesMap.put(item.getName(), item.getDefaultValues());
                    }

                    // 保存维度值映射
                    List<DimValueMap> dimValueMaps = item.getDimValueMaps();
                    if (!CollectionUtils.isEmpty(dimValueMaps)) {
                        dimAliasMap.put(item.getName(), dimValueMaps);
                    }

                    // 删除旧维度对应的词典文件（词典禁用时跳过，避免文件IO/重载开销）
                    if (Boolean.TRUE.equals(dictionaryEnabled)) {
                        DictSingleTaskReq deleteTaskReq = DictSingleTaskReq.builder()
                                .type(TypeEnums.DIMENSION).itemId(item.getId()).build();
                        try {
                            dictTaskService.deleteDictTaskForBI(deleteTaskReq, user);
                        } catch (Exception e) {
                            log.warn("删除维度词典文件失败, dimensionId: {}", item.getId(), e);
                        }
                    }
                });
                List<Long> dimensionIds =
                        dimensions.stream().map(DimensionResp::getId).collect(Collectors.toList());
                dimensionService.deleteDimensionBatch(dimensionIds, user);
            }
            List<MetricResp> metrics = metricService.getMetrics(modelFilter);
            if (!CollectionUtils.isEmpty(metrics)) {
                List<Long> metricIds =
                        metrics.stream().map(MetricResp::getId).collect(Collectors.toList());
                metricService.deleteMetricBatch(metricIds, user);
            }
            modelService.deleteModel(model.getId(), user);
        }
    }

    private DataSetResp createDataSet(BiModelConfig config, User user, DomainResp domainResp,
            List<ModelResp> modelResps, List<String> admins) {
        DataSetReq dataSetReq = new DataSetReq();
        dataSetReq.setDomainId(domainResp.getId());
        dataSetReq.setName(config.getModelName());
        dataSetReq.setBizName(config.getModelId());
        dataSetReq.setAdmins(admins);
        DataSetDetail dataSetDetail = new DataSetDetail();
        dataSetReq.setDataSetDetail(dataSetDetail);
        List<DataSetModelConfig> dataSetModelConfigs = Lists.newArrayList();
        dataSetDetail.setDataSetModelConfigs(dataSetModelConfigs);
        for (ModelResp modelResp : modelResps) {
            dataSetModelConfigs.add(buildDataSetModelConfig(modelResp));
        }


        DataSetResp existingDataSet = findDataSetByBizName(domainResp.getId(), config.getModelId());
        if (existingDataSet == null) {
            log.info("数据集不存在，执行新增, domainId: {}, bizName: {}", domainResp.getId(),
                    config.getModelId());
            return dataSetService.save(dataSetReq, user);
        }

        dataSetReq.setId(existingDataSet.getId());
        if (isSameDataSet(existingDataSet, dataSetReq)) {
            log.info("数据集无变化，跳过更新, id: {}", existingDataSet.getId());
            return existingDataSet;
        }

        log.info("数据集存在变化，执行更新, id: {}", existingDataSet.getId());
        return dataSetService.update(dataSetReq, user);
    }

    private DataSetModelConfig buildDataSetModelConfig(ModelResp modelResp) {
        DataSetModelConfig modelConfig = new DataSetModelConfig();
        modelConfig.setId(modelResp.getId());

        MetaFilter filter = new MetaFilter(Lists.newArrayList(modelResp.getId()));
        List<DimensionResp> dimensions = dimensionService.getDimensions(filter);
        if (!CollectionUtils.isEmpty(dimensions)) {
            modelConfig.setDimensions(
                    dimensions.stream().map(DimensionResp::getId).collect(Collectors.toList()));
        }

        List<MetricResp> metrics = metricService.getMetrics(filter);
        if (!CollectionUtils.isEmpty(metrics)) {
            modelConfig.setMetrics(
                    metrics.stream().map(MetricResp::getId).collect(Collectors.toList()));
        }
        return modelConfig;
    }

    private ModelResp createOrUpdateModel(ModelReq modelReq, User user, Long domainId)
            throws Exception {

        ModelResp existingModel = findModelByBizName(domainId, modelReq.getBizName());
        if (existingModel == null) {
            log.info("模型不存在，执行新增, domainId: {}, bizName: {}", domainId, modelReq.getBizName());
            return modelService.createModel(modelReq, user);
        }

        modelReq.setId(existingModel.getId());
        log.info("模型存在，执行模型/维度/指标同步, id: {}", existingModel.getId());
        return modelService.syncModelForBi(modelReq, user);
    }


    private ModelResp findModelByBizName(Long domainId, String bizName) {
        MetaFilter filter = new MetaFilter();
        filter.setDomainId(domainId);
        List<ModelResp> modelResps = modelService.getModelList(filter);
        return modelResps.stream()
                .filter(modelResp -> StringUtils.equals(modelResp.getBizName(), bizName))
                .findFirst().orElse(null);
    }

    private DataSetResp findDataSetByBizName(Long domainId, String bizName) {
        MetaFilter filter = new MetaFilter();
        filter.setDomainId(domainId);
        List<DataSetResp> dataSetResps = dataSetService.getDataSetList(filter);
        return dataSetResps.stream()
                .filter(dataSetResp -> StringUtils.equals(dataSetResp.getBizName(), bizName))
                .findFirst().orElse(null);
    }




    private boolean isSameDataSet(DataSetResp existingDataSet, DataSetReq targetReq) {
        if (existingDataSet == null || targetReq == null) {
            return false;
        }
        if (!Objects.equals(existingDataSet.getDomainId(), targetReq.getDomainId())) {
            return false;
        }
        if (!StringUtils.equals(existingDataSet.getName(), targetReq.getName())) {
            return false;
        }
        if (!StringUtils.equals(existingDataSet.getBizName(), targetReq.getBizName())) {
            return false;
        }
        if (!isSameList(existingDataSet.getAdmins(), targetReq.getAdmins())) {
            return false;
        }
        String existingDetail = JSONObject.toJSONString(existingDataSet.getDataSetDetail());
        String targetDetail = JSONObject.toJSONString(targetReq.getDataSetDetail());
        return StringUtils.equals(existingDetail, targetDetail);
    }

    private boolean isSameToolConfigIgnoreId(String oldToolConfig, String newToolConfig) {
        ToolConfig oldConfig = parseToolConfig(oldToolConfig);
        ToolConfig newConfig = parseToolConfig(newToolConfig);
        normalizeToolIds(oldConfig);
        normalizeToolIds(newConfig);
        return StringUtils.equals(JSONObject.toJSONString(oldConfig), JSONObject.toJSONString(newConfig));
    }

    private ToolConfig parseToolConfig(String toolConfig) {
        if (StringUtils.isBlank(toolConfig)) {
            return new ToolConfig();
        }
        ToolConfig parsed = JSONObject.parseObject(toolConfig, ToolConfig.class);
        return parsed == null ? new ToolConfig() : parsed;
    }

    private void normalizeToolIds(ToolConfig config) {
        List<AgentTool> tools = Optional.ofNullable(config).map(ToolConfig::getTools)
                .orElse(Collections.emptyList());
        tools.forEach(tool -> {
            if (tool != null) {
                tool.setId(null);
            }
        });
    }

    private boolean isSameList(List<String> left, List<String> right) {
        List<String> leftSorted = left == null ? Collections.emptyList()
                : left.stream().filter(StringUtils::isNotBlank).sorted().toList();
        List<String> rightSorted = right == null ? Collections.emptyList()
                : right.stream().filter(StringUtils::isNotBlank).sorted().toList();
        return Objects.equals(leftSorted, rightSorted);
    }


    private List<ModelResp> createModel(BiModelConfig config, BiPageConfig pageConfig,
            Map<String, List<DimValueMap>> dimAliasMap,
            Map<String, List<String>> oldDimDefaultValuesMap, User user, DatabaseResp databaseResp,
            DomainResp domainResp, List<String> admins, List<String> viewers) throws Exception {
        List<ModelResp> modelResps = Lists.newArrayList();
        List<BiModelItem> biDimensions = config.getDimensions();
        List<BiModelItem> biMeasures = config.getMeasures();
        List<BiDimensionCofig> dimensionConfigs = Optional.ofNullable(pageConfig)
                .map(BiPageConfig::getDimensionConfigs).orElse(Collections.emptyList());
        Map<String, List<String>> defaultValuesMap =
                buildDefaultValuesMap(dimensionConfigs, oldDimDefaultValuesMap);
        List<String> dimensionNamesList = extractDimensionNamesWithValues(dimensionConfigs);

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
            modelReq.setAdmins(admins);
            modelReq.setViewers(viewers);
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
                    String name = getReplacedAll(modelDimension.getName());
                    dimension.setName(name);
                    if (dimensionNamesList.contains(modelDimension.getName())) {
                        dimension.setHasDimValues(true);
                    }
                    if (StringUtils.isNotBlank(modelDimension.getFormat())) {
                        dimension.setType(DimensionType.time);
                        dimension.setHasDimValues(false);
                        dimension.setDateFormat(modelDimension.getFormat());
                    } else {
                        dimension.setType(DimensionType.categorical);
                    }

                    dimension.setBizName(modelDimension.getColumnName());
                    dimension.setDefaultValues(
                            defaultValuesMap.getOrDefault(modelDimension.getName(), null));
                    dimension.setDescription(name);
                    dimension.setIsCreateDimension(1);
                    dimensions.add(dimension);
                }
            }
            if (biMeasures != null) {
                List<Measure> measures = Lists.newArrayList();
                modelDetail.setMeasures(measures);
                for (BiModelItem modelMeasure : biMeasures) {
                    // 非可见的维度或指标跳过
                    if (!"YES".equals(modelMeasure.getIsLook())) {
                        continue;
                    }
                    Measure measure = new Measure();
                    String name = getReplacedAll(modelMeasure.getName());
                    measure.setName(name);
                    measure.setBizName(modelMeasure.getColumnName());
                    measure.setAgg(AggOperatorEnum.NONE.getOperator());
                    if (modelMeasure.getAggregationType() != null) {
                        AggOperatorEnum aggOperator =
                                AggOperatorEnum.of(modelMeasure.getAggregationType());
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
                        String name = getReplacedAll(custom.getName());
                        dimension.setName(name);
                        if (dimensionNamesList.contains(custom.getName())) {
                            dimension.setHasDimValues(true);
                        }
                        Integer columnType = custom.getColumnType();
                        if (columnType != null && columnType == 2) {
                            dimension.setType(DimensionType.time);
                            dimension.setHasDimValues(false);
                            dimension.setDateFormat(custom.getFormat());
                        } else {
                            dimension.setType(DimensionType.categorical);
                        }
                        dimension.setBizName(custom.getName());
                        // 对columnName进行替换
                        String replacedColumnName = getReplacedColumnName(custom.getColumnName());
                        dimension.setExpr(replacedColumnName);
                        dimension.setDefaultValues(
                                defaultValuesMap.getOrDefault(custom.getName(), null));
                        dimension.setDescription(name);
                        dimension.setIsCreateDimension(1);
                        List<Dimension> dimensions = modelDetail.getDimensions();
                        if (dimensions == null) {
                            dimensions = Lists.newArrayList();
                            modelDetail.setDimensions(dimensions);
                        }
                        dimensions.add(dimension);
                    } else if (custom.getType() == 1) {
                        Measure measure = new Measure();
                        String name = getReplacedAll(custom.getName());
                        measure.setName(name);
                        String replacedColumnName = getReplacedColumnName(custom.getColumnName());
                        measure.setExpr(replacedColumnName);
                        measure.setBizName(name);
                        measure.setAgg(AggOperatorEnum.NONE.getOperator());
                        if (custom.getAggregationType() != null) {
                            AggOperatorEnum aggOperator =
                                    AggOperatorEnum.of(custom.getAggregationType());
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
            ModelResp modelResp = createOrUpdateModel(modelReq, user, domainResp.getId());
            modelResps.add(modelResp);
            importDimensionIfNeeded(user, dimensionConfigs, modelResp.getId(), dimAliasMap);

        } else if (config.getCreateModelType() == 2) {
            // 使用JsqlPareser解析sql，替换掉别名的引号，避免supersonic解析时报错
            String querySql = processQuerySql(config.getQuerySql());
            List<BiModelItem> modelDimensions = config.getDimensions();
            List<BiModelItem> modelMeasures = config.getMeasures();
            ModelReq modelReq = new ModelReq();
            modelReq.setDatabaseId(databaseResp.getId());
            modelReq.setDomainId(domainResp.getId());
            modelReq.setName(config.getModelName());
            modelReq.setBizName(config.getModelId());
            modelReq.setAdmins(admins);
            modelReq.setViewers(viewers);
            ModelDetail modelDetail = new ModelDetail();
            modelDetail.setQueryType(ModelDefineType.SQL_QUERY.getName());
            modelDetail.setSqlQuery(querySql);
            modelReq.setModelDetail(modelDetail);
            if (modelDimensions != null) {
                List<Dimension> dimensions = Lists.newArrayList();
                modelDetail.setDimensions(dimensions);
                for (BiModelItem modelDimension : modelDimensions) {
                    Dimension dimension = new Dimension();
                    String name = getReplacedAll(modelDimension.getName());
                    dimension.setName(name);
                    if (dimensionNamesList.contains(modelDimension.getName())) {
                        dimension.setHasDimValues(true);
                    }
                    Integer columnType = modelDimension.getColumnType();
                    if (columnType != null && columnType == 2) {
                        dimension.setType(DimensionType.time);
                        dimension.setHasDimValues(false);
                        dimension.setDateFormat(modelDimension.getFormat());
                    } else {
                        dimension.setType(DimensionType.categorical);
                    }
                    dimension.setBizName(name);
                    dimension.setDefaultValues(
                            defaultValuesMap.getOrDefault(modelDimension.getName(), null));
                    dimension.setIsCreateDimension(1);
                    dimension.setDescription(name);
                    dimensions.add(dimension);
                }
            }
            if (modelMeasures != null) {
                List<Measure> measures = Lists.newArrayList();
                modelDetail.setMeasures(measures);;
                for (BiModelItem modelMeasure : modelMeasures) {
                    Measure measure = new Measure();
                    String name = getReplacedAll(modelMeasure.getName());
                    measure.setName(name);
                    measure.setBizName(name);
                    measure.setAgg(AggOperatorEnum.NONE.getOperator());
                    if (modelMeasure.getAggregationType() != null) {
                        AggOperatorEnum aggOperator =
                                AggOperatorEnum.of(modelMeasure.getAggregationType());
                        if (!AggOperatorEnum.UNKNOWN.equals(aggOperator)) {
                            measure.setAgg(aggOperator.getOperator());
                        }
                    }
                    measure.setIsCreateMetric(1);
                    measures.add(measure);
                }
            }
            ModelResp modelResp = createOrUpdateModel(modelReq, user, domainResp.getId());
            modelResps.add(modelResp);
            importDimensionIfNeeded(user, dimensionConfigs, modelResp.getId(), dimAliasMap);
        } else {

            throw new IllegalArgumentException("不支持的建模类型 : " + config.getCreateModelType());
        }
        return modelResps;
    }

    private Map<String, List<String>> buildDefaultValuesMap(List<BiDimensionCofig> dimensionConfigs,
            Map<String, List<String>> oldDimDefaultValuesMap) {
        Map<String, List<String>> defaultValuesMap = dimensionConfigs.stream().filter(
                biDimensionCofig -> !CollectionUtils.isEmpty(biDimensionCofig.getDefaultValues()))
                .collect(Collectors.toMap(BiDimensionCofig::getName,
                        BiDimensionCofig::getDefaultValues));
        if (oldDimDefaultValuesMap != null) {
            for (Map.Entry<String, List<String>> entry : oldDimDefaultValuesMap.entrySet()) {
                defaultValuesMap.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        return defaultValuesMap;
    }

    private List<String> extractDimensionNamesWithValues(List<BiDimensionCofig> dimensionConfigs) {
        return dimensionConfigs.stream()
                .filter(biDimensionCofig -> !CollectionUtils.isEmpty(biDimensionCofig.getValues()))
                .map(BiDimensionCofig::getName).toList();
    }

    private void importDimensionIfNeeded(User user, List<BiDimensionCofig> dimensionConfigs,
            Long modelId, Map<String, List<DimValueMap>> dimAliasMap) {
        if (CollectionUtils.isEmpty(dimensionConfigs)) {
            return;
        }
        importDimension(user, dimensionConfigs, modelId, dimAliasMap);
    }

    private String getReplacedColumnName(String columnName) {

        // 检查columnName是否包含"left"，不包含则直接返回原字符串
        if (columnName == null || !columnName.toLowerCase().contains("left")) {
            return columnName;
        }

        // 处理LEFT函数到SUBSTRING的转换
        // 匹配模式：left(string, length) → substring(string, 1, length)
        Pattern pattern = Pattern.compile("(`?left`?)\\s*\\(\\s*([^,]+)\\s*,\\s*(\\d+)\\s*\\)",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(columnName);

        StringBuffer result = new StringBuffer();
        boolean found = false;
        while (matcher.find()) {
            found = true;
            // 转换为SUBSTRING语法：SUBSTRING(string, 1, length)
            String replacement = matcher.group(1).replace("left", "substring") + "("
                    + matcher.group(2) + ", 1, " + matcher.group(3) + ")";
            matcher.appendReplacement(result, replacement);
        }

        if (found) {
            matcher.appendTail(result);
            return result.toString();
        } else {
            // 如果包含"left"但没有匹配到函数调用模式，返回原字符串
            return columnName;
        }
    }


    @NotNull
    private static String getReplacedAll(String name) {
        return name.replaceAll("\\（([^)]*)\\）", "$1").replaceAll("\\(([^)]*)\\)", "$1")
                .replace("：", "").replace(":", "").replaceAll("\\s+", "");
    }

    private String processQuerySql(String querySql) {
        try {
            Select statement = (Select) CCJSqlParserUtil.parse(querySql);
            if (statement instanceof PlainSelect select) {
                List<SelectItem<?>> items = select.getSelectItems();
                items.forEach(item -> {
                    Alias alias = item.getAlias();
                    // log.info("BI传递的sql alias name: {}", alias.getName());
                    if (alias != null && alias.getName() != null) {
                        String name =
                                alias.getName().replace("\"", "`").replaceAll("\\（([^)]*)\\）", "$1")
                                        .replaceAll("\\(([^)]*)\\)", "$1");
                        alias.setName(name);
                        // log.info("替换后的sql alias name: {}", name);
                    }
                });
                return select.toString();
            }
        } catch (Exception e) {
            log.error("解析sql出错: {}", querySql, e);
        }
        return querySql;
    }

    private void importDimension(User user, List<BiDimensionCofig> dimensionConfigs, Long modelId,
            Map<String, List<DimValueMap>> dimAliasMap) {
        MetaFilter filter = new MetaFilter();
        filter.setModelIds(Collections.singletonList(modelId));
        for (BiDimensionCofig dimensionConfig : dimensionConfigs) {
            List<String> values = dimensionConfig.getValues();
            if (CollectionUtils.isEmpty(values)) {
                continue;
            }
            filter.setName(dimensionConfig.getName());
            List<DimensionResp> resps = dimensionService.getDimensions(filter);
            if (resps == null || resps.size() != 1) {
                continue;
            }
            DimensionResp resp = resps.getFirst();
            // 停用原有加入词典的逻辑
            DictItemReq dictItemReq = new DictItemReq();
            dictItemReq.setType(TypeEnums.DIMENSION);
            dictItemReq.setItemId(resp.getId());
            // 导入的维度值锁定不允许刷新
            dictItemReq.setStatus(StatusEnum.ONLINE);
            dictItemReq.setLocked(1);
            ensureDictConf(dictItemReq, user, resp.getId());

            // String nature = dictItemResp.getNature();
            // List<String> lines = values.stream().map(value -> {
            // if (!StringUtils.isEmpty(value)) {
            // value = value.replace(SPACE, POUND);
            // }
            // return value;
            // }).filter(value -> !value.equals("全国"))
            // .map(value -> String.format("%s %s %s", value, nature, 1L)).toList();
            // dictTaskService.importDictData(dictItemResp, lines, user);
            // List<DimValueMap> alias = dimAliasMap.get(dimensionConfig.getName());
            // if (alias != null) {
            // dimensionService.updateDimValueAliasBatch(resp.getId(), alias, user);
            // }
            List<String> normalizedValues = values.stream().filter(StringUtils::isNotBlank)
                    .map(String::trim).filter(value -> !"全国".equals(value)).distinct().toList();

            // 仅保存前50个维度值到dim_value_maps，供提示词和背景信息使用
            List<DimValueMap> oldAlias = dimAliasMap == null ? Collections.emptyList()
                    : dimAliasMap.getOrDefault(dimensionConfig.getName(), Collections.emptyList());
            List<DimValueMap> previewDimValueMaps =
                    buildPreviewDimValueMaps(normalizedValues, oldAlias);

            if (isDimensionValuesUnchanged(resp, normalizedValues, previewDimValueMaps)) {
                DictSingleTaskReq successTaskReq = DictSingleTaskReq.builder()
                        .type(TypeEnums.DIMENSION).itemId(resp.getId()).build();
                dictTaskService.addSuccessTaskForBI(successTaskReq, user);
                log.info("维度值无变化，跳过更新, dimId: {}, dimName: {}", resp.getId(), resp.getName());
                continue;
            }


            // 清理该维度旧向量和旧dimValueMaps，避免历史值残留
            DictSingleTaskReq deleteTaskReq = DictSingleTaskReq.builder().type(TypeEnums.DIMENSION)
                    .itemId(resp.getId()).build();
            try {
                dictTaskService.deleteDictTaskForBI(deleteTaskReq, user);
            } catch (Exception e) {
                log.warn("清理维度值失败, dimId: {}", resp.getId(), e);
            }

            if (CollectionUtils.isEmpty(normalizedValues)) {
                continue;
            }

            // 全量维度值写入向量库
            List<DimensionValueDO> dimensionValueDOS = normalizedValues.stream().map(value -> {
                DimensionValueDO dimensionValueDO = new DimensionValueDO();
                dimensionValueDO.setModelId(modelId);
                dimensionValueDO.setDimId(resp.getId());
                dimensionValueDO.setDimName(resp.getName());
                dimensionValueDO.setDimBizName(resp.getBizName());
                dimensionValueDO.setDimValue(value);
                dimensionValueDO.setFrequency(1L);
                return dimensionValueDO;
            }).toList();
            dimensionService.sendDimensionValueEventBatch(dimensionValueDOS, EventType.ADD);
            // 维度值别名处理判断是否有别名，直接入库

            if (!CollectionUtils.isEmpty(dimAliasMap)) {
                // 记录维度值别名集合
                List<DimensionValueDO> dimensionValueAliasList = new ArrayList<>();
                dimAliasMap.getOrDefault(resp.getName(), Collections.emptyList())
                        .forEach(dimValues -> {
                            // 获取别名
                            List<String> alias = dimValues.getAlias();
                            if (!CollectionUtils.isEmpty(alias)) {
                                alias.forEach(tAlias -> {
                                    DimensionValueDO dimensionValueDO = new DimensionValueDO();
                                    // 设置维度值的别名进去
                                    dimensionValueDO.setAlias(tAlias);
                                    dimensionValueDO.setModelId(modelId);
                                    // 这是维度别名
                                    dimensionValueDO.setDimId(resp.getId());
                                    dimensionValueDO.setDimName(resp.getName());
                                    dimensionValueDO.setDimBizName(resp.getBizName());
                                    // 这是维度值
                                    dimensionValueDO.setDimValue(dimValues.getTechName());
                                    dimensionValueAliasList.add(dimensionValueDO);
                                });
                            }
                        });
                dimensionService.sendDimensionValueAliasEventBatch(dimensionValueAliasList,
                        EventType.ADD);
            }

            if (!CollectionUtils.isEmpty(previewDimValueMaps)) {
                // 原本调用updateDimValueAliasBatch方法改为updateDimValueMapsOnlyBatch，因为词典已取消，无需缓存别名到内存。
                dimensionService.updateDimValueMapsOnlyBatch(resp.getId(), previewDimValueMaps,
                        user);
            }
            DictSingleTaskReq successTaskReq = DictSingleTaskReq.builder().type(TypeEnums.DIMENSION)
                    .itemId(resp.getId()).build();
            dictTaskService.addSuccessTaskForBI(successTaskReq, user);

        }


    }

    private void ensureDictConf(DictItemReq dictItemReq, User user, Long dimId) {
        try {
            dictConfService.addDictConf(dictItemReq, user);
        } catch (RuntimeException e) {
            if (StringUtils.containsIgnoreCase(e.getMessage(), "dictConf is existed")) {
                log.info("dictConf已存在，改为更新, dimId:{}", dimId);
                dictConfService.editDictConf(dictItemReq, user);
                return;
            }
            throw e;
        }
    }

    private boolean isDimensionValuesUnchanged(DimensionResp dimensionResp,
            List<String> normalizedValues, List<DimValueMap> previewDimValueMaps) {

        List<DimValueMap> existingMaps =
                dimensionResp.getDimValueMaps() == null ? Collections.emptyList()
                        : dimensionResp.getDimValueMaps();
        Set<String> existingValues = existingMaps.stream().filter(Objects::nonNull)
                .map(DimValueMap::getValue).filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> previewValues = previewDimValueMaps == null ? Collections.emptySet()
                : previewDimValueMaps.stream().filter(Objects::nonNull).map(DimValueMap::getValue)
                        .filter(StringUtils::isNotBlank)
                        .collect(Collectors.toCollection(LinkedHashSet::new));

        // 历史只保存预览值（最多50个），当本次值量超过50时无法精准判断是否完全无变化，默认更新。
        if (normalizedValues.size() > 50) {
            return false;
        }
        return Objects.equals(existingValues, previewValues);
    }

    private List<DimValueMap> buildPreviewDimValueMaps(List<String> normalizedValues,
            List<DimValueMap> oldAlias) {
        Map<String, DimValueMap> aliasByValue = oldAlias == null ? Collections.emptyMap()
                : oldAlias.stream().filter(Objects::nonNull)
                        .filter(item -> StringUtils.isNotBlank(item.getValue())).collect(
                                Collectors.toMap(DimValueMap::getValue, item -> item, (a, b) -> a));

        List<DimValueMap> preview = new ArrayList<>();
        normalizedValues.stream().filter(value -> StringUtils.length(value) <= 20).limit(50)
                .forEach(value -> {
                    DimValueMap dimValueMap = new DimValueMap();
                    dimValueMap.setValue(value);
                    dimValueMap.setTechName(value);
                    if (!CollectionUtils.isEmpty(aliasByValue) && aliasByValue.containsKey(value)) {
                        DimValueMap oldMap = aliasByValue.get(value);
                        if (!CollectionUtils.isEmpty(oldMap.getAlias())) {
                            dimValueMap.setAlias(oldMap.getAlias());
                        }
                        if (StringUtils.isNotBlank(oldMap.getBizName())
                                && !StringUtils.equals(oldMap.getBizName(), value)) {
                            dimValueMap.setBizName(oldMap.getBizName());
                        }
                    }
                    preview.add(dimValueMap);
                });
        return preview;
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

    private DatabaseResp createDataSource(BiAgentConfig config, User user) {
        BiDataSource dataSource = config.getDataSource();
        DatabaseReq databaseReq = new DatabaseReq();
        databaseReq.setName("BI-" + dataSource.getName());
        switch (dataSource.getType().toLowerCase()) {
            case "mysql" -> {
                databaseReq.setType(EngineType.MYSQL.getName());
                databaseReq.setVersion("5.7");
            }
            case "doris" -> databaseReq.setType(EngineType.DORIS.getName());
            case "clickhouse" -> databaseReq.setType(EngineType.CLICKHOUSE.getName());
            default -> throw new IllegalArgumentException("不支持的数据库类型 : " + dataSource.getType());
        }
        // 检查是否已有该数据源
        List<DatabaseResp> databases = databaseService.getDatabaseByTypeForBI(dataSource.getType());
        if (databases != null && !databases.isEmpty()) {
            for (DatabaseResp databaseResp : databases) {
                if (StringUtils.equalsIgnoreCase(dataSource.getConnectionUrl(),
                        databaseResp.getUrl())
                        && Strings.equals(dataSource.getUserName(), databaseResp.getUsername())) {
                    return databaseResp;
                }
            }
        }
        databaseReq.setUrl(dataSource.getConnectionUrl());
        databaseReq.setUsername(dataSource.getUserName());
        databaseReq.setPassword(AESEncryptionUtil.aesEncryptECB(dataSource.getPassword()));
        databaseReq.setSchema(dataSource.getDefaultDatabase());
        databaseReq.setAdmins(config.getAdmins());
        databaseReq.setViewers(config.getViewers());
        DatabaseResp databaseResp = databaseService.createOrUpdateDatabase(databaseReq, user);
        return databaseResp;
    }

}
