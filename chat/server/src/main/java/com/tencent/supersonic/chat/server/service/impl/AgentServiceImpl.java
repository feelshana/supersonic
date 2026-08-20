package com.tencent.supersonic.chat.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.pagehelper.PageInfo;
import com.google.common.collect.Lists;
import com.tencent.supersonic.auth.api.authentication.service.UserService;
import com.tencent.supersonic.chat.api.pojo.request.ChatMemoryFilter;
import com.tencent.supersonic.chat.api.pojo.request.ChatParseReq;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.agent.AgentContextResp;
import com.tencent.supersonic.chat.server.agent.AgentDataSetInfoDTO;
import com.tencent.supersonic.chat.server.agent.TermDTO;
import com.tencent.supersonic.chat.server.agent.VisualConfig;
import com.tencent.supersonic.chat.server.persistence.dataobject.AgentDO;
import com.tencent.supersonic.chat.server.persistence.mapper.AgentDOMapper;
import com.tencent.supersonic.chat.server.pojo.ChatMemory;
import com.tencent.supersonic.chat.server.service.AgentService;
import com.tencent.supersonic.chat.server.service.ChatQueryService;
import com.tencent.supersonic.chat.server.service.MemoryService;
import com.tencent.supersonic.common.config.ChatModel;
import com.tencent.supersonic.common.config.GeneralManageConfig;
import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.DimensionConstants;
import com.tencent.supersonic.common.pojo.TermConstants;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.enums.Text2SQLType;
import com.tencent.supersonic.common.service.ChatModelService;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.api.pojo.*;
import com.tencent.supersonic.headless.api.pojo.request.PageDimensionReq;
import com.tencent.supersonic.headless.api.pojo.request.PageMetricReq;
import com.tencent.supersonic.headless.api.pojo.request.PageSchemaItemReq;
import com.tencent.supersonic.headless.api.pojo.request.QueryNLReq;
import com.tencent.supersonic.headless.api.pojo.response.*;
import com.tencent.supersonic.headless.chat.parser.llm.OnePassSCSqlGenStrategy;
import com.tencent.supersonic.headless.chat.parser.llm.SimpleStrategy;
import com.tencent.supersonic.headless.chat.parser.llm.SqlGenStrategyFactory;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.headless.server.facade.service.ChatLayerService;
import com.tencent.supersonic.headless.server.pojo.DimensionsFilter;
import com.tencent.supersonic.headless.server.service.*;
import dev.langchain4j.model.input.Prompt;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

import static com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq.SqlGenType.ONE_PASS_SELF_CONSISTENCY;

@Slf4j
@Service
public class AgentServiceImpl extends ServiceImpl<AgentDOMapper, AgentDO> implements AgentService {

    @Autowired
    private MemoryService memoryService;

    @Autowired
    @Lazy
    private ChatQueryService chatQueryService;

    @Autowired
    private ChatModelService chatModelService;

    @Autowired
    private GeneralManageConfig generalManageConfig;
    @Autowired
    private SchemaService schemaService;
    @Autowired
    private UserService userService;
    @Autowired
    private OnePassSCSqlGenStrategy onePassSCSqlGenStrategy;
    @Autowired
    @Qualifier("chatExecutor")
    private ThreadPoolExecutor executor;
    @Autowired
    private DataSetService dataSetService;
    @Autowired
    private DomainService domainService;
    @Autowired
    private ModelService modelService;
    @Autowired
    private DimensionService dimensionService;
    @Autowired
    private MetricService metricService;

    @Override
    public List<Agent> getAgents(User user, AuthType authType) {
        return getAgentDOList().stream().map(this::convert)
                .filter(agent -> filterByAuth(agent, user, authType)).collect(Collectors.toList());
    }

    private boolean filterByAuth(Agent agent, User user, AuthType authType) {
        Set<String> orgIds = userService.getUserAllOrgId(user.getName());

        if (user.isSuperAdmin() || agent.openToAll()
                || user.getName().equals(agent.getCreatedBy())) {
            return true;
        }
        authType = authType == null ? AuthType.VIEWER : authType;
        switch (authType) {
            case ADMIN:
                return checkAdminPermission(orgIds, user, agent);
            case VIEWER:
            default:
                return checkViewPermission(orgIds, user, agent);
        }
    }

    @Override
    public List<Agent> getAgents() {
        return getAgentDOList().stream().map(this::convert).collect(Collectors.toList());
    }

    @Override
    public Agent createAgent(Agent agent, User user) {
        agent.createdBy(user.getName());
        AgentDO agentDO = convert(agent);
        save(agentDO);
        agent.setId(agentDO.getId());
        executeAgentExamplesAsync(agent);
        return agent;
    }

    @Override
    public Agent updateAgent(Agent agent, User user) {
        agent.updatedBy(user.getName());
        updateById(convert(agent));
        executeAgentExamplesAsync(agent);
        return agent;
    }

    @Override
    public Agent getAgent(Integer id) {
        if (id == null) {
            return null;
        }
        return convert(getById(id));
    }

    @Override
    public List<Agent> getAgentByName(String name) {
        if (name == null) {
            return new ArrayList<>();
        }
        List<AgentDO> agentDOList = getByName(name);
        if (CollectionUtils.isEmpty(agentDOList)) {
            return new ArrayList<>();
        }
        return agentDOList.stream().map(this::convert).collect(Collectors.toList());
    }

    private List<AgentDO> getByName(String name) {
        return baseMapper.selectList(new LambdaQueryWrapper<AgentDO>().eq(AgentDO::getName, name));
    }

    @Override
    public void deleteAgent(Integer id) {
        removeById(id);
    }

    @Override
    public Agent getAgentDetail(Integer agentId, User user) {
        if (agentId == null) {
            return null;
        }
        Agent agent = convert(getById(agentId));
        Set<Long> dataSetIds = agent.getDataSetIds();
        List<DimensionResp> dimensionNames = new ArrayList<>();
        List<MetricResp> metricNames = new ArrayList<>();
        for (Long dataSetId : dataSetIds) {
            DataSetResp dataSet = dataSetService.getDataSet(dataSetId);
            Long domainId = dataSet.getDomainId();
            List<ModelResp> allModelByDomainIds =
                    modelService.getAllModelByDomainIds(Lists.newArrayList(domainId));
            List<Long> modelIds =
                    allModelByDomainIds.stream().map(ModelResp::getId).collect(Collectors.toList());
            PageDimensionReq pageDimensionReq = new PageDimensionReq();
            pageDimensionReq.setModelIds(modelIds);
            pageDimensionReq.setPageSize(99);
            pageDimensionReq.setCurrent(1);
            PageInfo<DimensionResp> dimensionPageInfo =
                    dimensionService.queryDimension(pageDimensionReq);
            dimensionNames.addAll(dimensionPageInfo.getList());
            PageMetricReq pageMetricReq = new PageMetricReq();
            pageMetricReq.setModelIds(modelIds);
            pageMetricReq.setPageSize(99);
            pageMetricReq.setCurrent(1);
            PageInfo<MetricResp> metricRespPageInfo =
                    metricService.queryMetric(pageMetricReq, user);
            metricNames.addAll(metricRespPageInfo.getList());
        }
        agent.setDimensionList(dimensionNames);
        agent.setMetricList(metricNames);
        return agent;
    }


    @Override
    public String getAgentPrompt(Integer agentId, String queryText, User user) {
        Agent agent = convert(getById(agentId));
        Set<Long> dataSetIds = agent.getDataSetIds();
        SemanticSchema semanticSchema = schemaService.getSemanticSchema(dataSetIds);
        LLMReq llmReq = new LLMReq();
        llmReq.setQueryText(queryText);
        llmReq.setChatAppConfig(agent.getChatAppConfig());
        SimpleStrategy simpleStrategy = new SimpleStrategy();
        Prompt promptText = simpleStrategy.generateStreamPrompt(llmReq, semanticSchema);
        return promptText.text().replaceAll("\\n", "");

    }

    @Autowired
    private ChatLayerService chatLayerService;

    @Override
    public String getAgentDataSetInfo(Integer agentId, String queryText, User user) {
        Agent agent = convert(getById(agentId));
        if (agent == null || agent.getDataSetIds() == null) {
            return "";
        }

        Set<Long> dataSetIds = agent.getDataSetIds();
        SemanticSchema semanticSchema = schemaService.getSemanticSchema(dataSetIds);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy年MM月dd日");
        String currentDate = LocalDate.now().format(formatter);

        Map<String, String> termsMap = new HashMap<>();
        if (semanticSchema.getTerms() != null) {
            termsMap = semanticSchema.getTerms().stream()
                    .filter(term -> term.getAlias() == null || term.getAlias().stream()
                            .noneMatch(a -> a.toLowerCase().contains("rule")))
                    .collect(Collectors.toMap(SchemaElement::getName, SchemaElement::getDescription,
                            (a, b) -> a));
        }

        // 解析"无需排除id的维度"术语配置：key=维度名, value=limit条数(1~50)
        Map<String, Integer> noExcludeIdDimMap = parseNoExcludeIdDimConfig(semanticSchema);

        // 构建维度信息，包括维度值
        StringBuilder dimensionsInfo = new StringBuilder();
        if (semanticSchema.getDimensions() != null) {
            for (SchemaElement dimension : semanticSchema.getDimensions()) {
                // 跳过名称含id的维度（如省份id、内容ID、分组Id等），但术语配置的白名单维度除外
                String dimNameLower = dimension.getName().toLowerCase();
                if (dimNameLower.contains("id")
                        && !noExcludeIdDimMap.containsKey(dimension.getName())) {
                    continue;
                }

                dimensionsInfo.append("   - ").append(dimension.getName());
                if (StringUtils.isNotEmpty(dimension.getTimeFormat())) {
                    final String DAILY_FORMAT = "yyyyMMdd";
                    final String MONTHLY_FORMAT = "yyyyMM";
                    String granularityDesc = DAILY_FORMAT.equals(dimension.getTimeFormat()) ? "日表"
                            : MONTHLY_FORMAT.equals(dimension.getTimeFormat()) ? "月表" : "";
                    dimensionsInfo.append("（日期字段，格式：").append(dimension.getTimeFormat());
                    if (!granularityDesc.isEmpty()) {
                        dimensionsInfo.append("，").append(granularityDesc);
                    }
                    dimensionsInfo.append("）");
                }
                if (isSkipDimension(dimension)) {
                    dimensionsInfo.append("\n");
                    continue;
                }
                if (Boolean.TRUE.equals(dimension.isHasDimValues())
                        || !CollectionUtils.isEmpty(dimension.getSchemaValueMaps())) {
                    PageInfo<DictValueDimResp> pageInfo =
                            onePassSCSqlGenStrategy.getDimensionValuesFromDict(dimension);
                    if (pageInfo != null && !CollectionUtils.isEmpty(pageInfo.getList())) {
                        // 根据术语配置取limit；未配置则默认50；最大上限50，最小1
                        int dimLimit = noExcludeIdDimMap.getOrDefault(dimension.getName(), 50);
                        dimLimit = Math.max(1, Math.min(dimLimit, 50));
                        List<String> dimensionValues =
                                pageInfo.getList().stream().map(DictValueDimResp::getValue)
                                        .limit(dimLimit).collect(Collectors.toList());
                        if (!dimensionValues.isEmpty()) {
                            dimensionsInfo.append("\n");
                            // 省份维度：含“全国”时只输出说明，不列维度值
                            boolean isProvinceDim = dimNameLower.contains("省份")
                                    || dimNameLower.contains("province");
                            if (isProvinceDim && dimensionValues.contains("全国")) {
                                List<String> sampleProvinces =
                                        dimensionValues.stream().filter(v -> !"全国".equals(v))
                                                .limit(3).collect(Collectors.toList());
                                dimensionsInfo.append("     说明：该维度包含'全国'");
                                if (!sampleProvinces.isEmpty()) {
                                    dimensionsInfo.append("和'")
                                            .append(String.join("'、'", sampleProvinces))
                                            .append("'等省份数据");
                                }
                                dimensionsInfo.append("，全国的数据不需要用各省来累加\n");
                            } else {
                                // 城市维度：含“全省”时只输出说明，不列维度值
                                boolean isCityDim =
                                        dimNameLower.contains("城市") || dimNameLower.contains("地市")
                                                || dimNameLower.contains("city");
                                if (isCityDim && dimensionValues.contains("全省")) {
                                    List<String> sampleCities =
                                            dimensionValues.stream().filter(v -> !"全省".equals(v))
                                                    .limit(3).collect(Collectors.toList());
                                    dimensionsInfo.append("     说明：该维度包含'全省'");
                                    if (!sampleCities.isEmpty()) {
                                        dimensionsInfo.append("和'")
                                                .append(String.join("'、'", sampleCities))
                                                .append("'等城市数据");
                                    }
                                    dimensionsInfo.append("，全省的数据不需要用各城市来累加\n");
                                } else {
                                    // 普通维度：正常输出维度值
                                    dimensionsInfo.append("     维度值: ")
                                            .append(String.join(", ", dimensionValues))
                                            .append("\n");
                                }
                            }
                        } else {
                            dimensionsInfo.append("\n");
                        }
                    } else {
                        dimensionsInfo.append("\n");
                    }
                } else {
                    dimensionsInfo.append("\n");
                }
            }
        }
        // 仅当 queryText 非空时才执行语义映射，用于拼接第6项信息
        List<SchemaElementMatch> schemaElementMatches = null;
        if (StringUtils.isNotEmpty(queryText)) {
            QueryNLReq queryNLReq = new QueryNLReq();
            queryNLReq.setQueryText(queryText);
            queryNLReq.setAgentId(agentId);
            queryNLReq.setDataSetIds(dataSetIds);
            queryNLReq.setText2SQLType(Text2SQLType.NONE);

            MapResp map;
            try {
                map = chatLayerService.map(queryNLReq);
            } catch (Exception e) {
                throw new RuntimeException("Failed to call chatLayerService.", e);
            }

            SchemaMapInfo mapInfo = map != null ? map.getMapInfo() : null;
            if (mapInfo != null && mapInfo.getDataSetElementMatches() != null
                    && !dataSetIds.isEmpty()) {
                Long firstDataSetId = dataSetIds.iterator().next();
                schemaElementMatches = mapInfo.getDataSetElementMatches().get(firstDataSetId);
            }
        }

        StringBuilder replyGuidelineBuilder = new StringBuilder();
        replyGuidelineBuilder.append("当前报表包含以下数据集信息：\n").append("1. 维度列表：\n")
                .append(dimensionsInfo.toString()).append("\n2. 指标列表：\n");

        if (semanticSchema.getMetrics() != null) {
            replyGuidelineBuilder.append(semanticSchema.getMetrics().stream()
                    .map(m -> "   - " + m.getName()).collect(Collectors.joining("\n")));
        }

        replyGuidelineBuilder.append("\n3. 术语说明：\n");
        replyGuidelineBuilder.append(
                termsMap.entrySet().stream().map(e -> "   - " + e.getKey() + ": " + e.getValue())
                        .collect(Collectors.joining("\n")));

        replyGuidelineBuilder.append("\n4. 当前日期：").append(currentDate);

        if (StringUtils.isNotEmpty(queryText)) {
            replyGuidelineBuilder.append("\n5. 当前用户问题映射到的维度及其维度值：\n[");
            if (!CollectionUtils.isEmpty(schemaElementMatches)) {
                List<String> dimensionValuePairs = schemaElementMatches.stream()
                        .filter(m -> Boolean.TRUE.equals(m.isFullMatched())
                                && SchemaElementType.VALUE.equals(m.getElement().getType()))
                        .map(m -> m.getElement().getName() + "：" + m.getWord())
                        .collect(Collectors.toList());
                replyGuidelineBuilder.append(String.join(",", dimensionValuePairs));
            }
            replyGuidelineBuilder.append("]");
        }

        return replyGuidelineBuilder.toString();
    }

    @Override
    public String getAgentDataSetInfoForValidation(Integer agentId, String queryText, User user) {
        Agent agent = convert(getById(agentId));
        if (agent == null || agent.getDataSetIds() == null) {
            return "";
        }

        Set<Long> dataSetIds = agent.getDataSetIds();
        SemanticSchema semanticSchema = schemaService.getSemanticSchema(dataSetIds);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy年MM月dd日");
        String currentDate = LocalDate.now().format(formatter);

        Map<String, String> termsMap = new HashMap<>();
        if (semanticSchema.getTerms() != null) {
            termsMap = semanticSchema.getTerms().stream()
                    .filter(term -> term.getAlias() == null || term.getAlias().stream()
                            .noneMatch(a -> a.toLowerCase().contains("rule")))
                    .collect(Collectors.toMap(SchemaElement::getName, SchemaElement::getDescription,
                            (a, b) -> a));
        }
        // 构建维度信息，包括维度值（附加字段名 bizName）
        StringBuilder dimensionsInfo = new StringBuilder();
        if (semanticSchema.getDimensions() != null) {
            for (SchemaElement dimension : semanticSchema.getDimensions()) {
                String dimNameLower = dimension.getName().toLowerCase();
                if (dimNameLower.contains("id")) {
                    continue;
                }

                dimensionsInfo.append("   - ").append(dimension.getName());
                // 附加实际数据库列名（bizName），供SQL查询使用
                if (StringUtils.isNotBlank(dimension.getBizName())) {
                    dimensionsInfo.append("（字段名：").append(dimension.getBizName()).append("）");
                }
                if (StringUtils.isNotEmpty(dimension.getTimeFormat())) {
                    final String DAILY_FORMAT = "yyyyMMdd";
                    final String MONTHLY_FORMAT = "yyyyMM";
                    String granularityDesc = DAILY_FORMAT.equals(dimension.getTimeFormat()) ? "日表"
                            : MONTHLY_FORMAT.equals(dimension.getTimeFormat()) ? "月表" : "";
                    dimensionsInfo.append("（日期字段，格式：").append(dimension.getTimeFormat());
                    if (!granularityDesc.isEmpty()) {
                        dimensionsInfo.append("，").append(granularityDesc);
                    }
                    dimensionsInfo.append("）");
                }
                if (isSkipDimension(dimension)) {
                    dimensionsInfo.append("\n");
                    continue;
                }
                if (Boolean.TRUE.equals(dimension.isHasDimValues())
                        || !CollectionUtils.isEmpty(dimension.getSchemaValueMaps())) {
                    PageInfo<DictValueDimResp> pageInfo =
                            onePassSCSqlGenStrategy.getDimensionValuesFromDict(dimension);
                    if (pageInfo != null && !CollectionUtils.isEmpty(pageInfo.getList())) {
                        List<String> dimensionValues =
                                pageInfo.getList().stream().map(DictValueDimResp::getValue)
                                        .limit(50).collect(Collectors.toList());
                        if (!dimensionValues.isEmpty()) {
                            dimensionsInfo.append("\n");
                            boolean isProvinceDim = dimNameLower.contains("省份")
                                    || dimNameLower.contains("province");
                            if (isProvinceDim && dimensionValues.contains("全国")) {
                                List<String> sampleProvinces =
                                        dimensionValues.stream().filter(v -> !"全国".equals(v))
                                                .limit(3).collect(Collectors.toList());
                                dimensionsInfo.append("     说明：该维度包含'全国'");
                                if (!sampleProvinces.isEmpty()) {
                                    dimensionsInfo.append("和'")
                                            .append(String.join("'、'", sampleProvinces))
                                            .append("'等省份数据");
                                }
                                dimensionsInfo.append("，全国的数据不需要用各省来累加\n");
                            } else {
                                boolean isCityDim =
                                        dimNameLower.contains("城市") || dimNameLower.contains("地市")
                                                || dimNameLower.contains("city");
                                if (isCityDim && dimensionValues.contains("全省")) {
                                    List<String> sampleCities =
                                            dimensionValues.stream().filter(v -> !"全省".equals(v))
                                                    .limit(3).collect(Collectors.toList());
                                    dimensionsInfo.append("     说明：该维度包含'全省'");
                                    if (!sampleCities.isEmpty()) {
                                        dimensionsInfo.append("和'")
                                                .append(String.join("'、'", sampleCities))
                                                .append("'等城市数据");
                                    }
                                    dimensionsInfo.append("，全省的数据不需要用各城市来累加\n");
                                } else {
                                    dimensionsInfo.append("     维度值: ")
                                            .append(String.join(", ", dimensionValues))
                                            .append("\n");
                                }
                            }
                        } else {
                            dimensionsInfo.append("\n");
                        }
                    } else {
                        dimensionsInfo.append("\n");
                    }
                } else {
                    dimensionsInfo.append("\n");
                }
            }
        }
        // 仅当 queryText 非空时才执行语义映射
        List<SchemaElementMatch> schemaElementMatches = null;
        if (StringUtils.isNotEmpty(queryText)) {
            QueryNLReq queryNLReq = new QueryNLReq();
            queryNLReq.setQueryText(queryText);
            queryNLReq.setAgentId(agentId);
            queryNLReq.setDataSetIds(dataSetIds);
            queryNLReq.setText2SQLType(Text2SQLType.NONE);

            MapResp map;
            try {
                map = chatLayerService.map(queryNLReq);
            } catch (Exception e) {
                throw new RuntimeException("Failed to call chatLayerService.", e);
            }

            SchemaMapInfo mapInfo = map != null ? map.getMapInfo() : null;
            if (mapInfo != null && mapInfo.getDataSetElementMatches() != null
                    && !dataSetIds.isEmpty()) {
                Long firstDataSetId = dataSetIds.iterator().next();
                schemaElementMatches = mapInfo.getDataSetElementMatches().get(firstDataSetId);
            }
        }

        StringBuilder replyGuidelineBuilder = new StringBuilder();
        replyGuidelineBuilder.append("当前报表包含以下数据集信息：\n").append("1. 维度列表：\n")
                .append(dimensionsInfo.toString()).append("\n2. 指标列表：\n");

        if (semanticSchema.getMetrics() != null) {
            replyGuidelineBuilder.append(semanticSchema.getMetrics().stream()
                    .map(m -> "   - " + m.getName()).collect(Collectors.joining("\n")));
        }

        replyGuidelineBuilder.append("\n3. 术语说明：\n");
        replyGuidelineBuilder.append(
                termsMap.entrySet().stream().map(e -> "   - " + e.getKey() + ": " + e.getValue())
                        .collect(Collectors.joining("\n")));

        replyGuidelineBuilder.append("\n4. 当前日期：").append(currentDate);

        if (StringUtils.isNotEmpty(queryText)) {
            replyGuidelineBuilder.append("\n5. 当前用户问题映射到的维度及其维度值：\n[");
            if (!CollectionUtils.isEmpty(schemaElementMatches)) {
                List<String> dimensionValuePairs = schemaElementMatches.stream()
                        .filter(m -> Boolean.TRUE.equals(m.isFullMatched())
                                && SchemaElementType.VALUE.equals(m.getElement().getType()))
                        .map(m -> m.getElement().getName() + "：" + m.getWord())
                        .collect(Collectors.toList());
                replyGuidelineBuilder.append(String.join(",", dimensionValuePairs));
            }
            replyGuidelineBuilder.append("]");
        }

        return replyGuidelineBuilder.toString();
    }

    @Override
    public List<AgentDataSetInfoDTO> getRedSeaDataSetInfo(List<Integer> agentIds, String queryText,
            String queryType, User user) {
        log.info("[getRedSeaDataSetInfo] agentIds:{}, queryText:{}, queryType:{}", agentIds,
                queryText, queryType);
        boolean needDetail = "detail".equalsIgnoreCase(queryType);
        return agentIds.stream().map(agentId -> {
            Agent agent = convert(getById(agentId));
            AgentDataSetInfoDTO dto = new AgentDataSetInfoDTO();
            dto.setAgentId(agentId);
            dto.setAgentName(agent.getName());
            if (needDetail) {
                String info = getAgentDataSetInfo(agentId, queryText, user);
                dto.setDataSetInfo(info);
            } else {
                dto.setDescription(agent.getDescription());
            }
            return dto;
        }).collect(Collectors.toList());
    }

    private boolean isSkipDimension(SchemaElement dimension) {
        if (dimension == null) {
            return true;
        }
        // 跳过日期类维度（省份/城市维度不跳过，由上层单独识别并追加说明）
        String dimensionName = dimension.getName().toLowerCase();
        return dimensionName.contains("日期") || dimensionName.contains("时间")
                || dimensionName.contains("date") || dimensionName.contains("time");
    }

    /**
     * 解析"无需排除id的维度"术语配置。 description 格式为 JSON Map: {"维度名": limit条数, ...}
     * 配置了的维度即使name含"id"也不会被排除；limit范围1~50，超出按50截断。
     */
    private Map<String, Integer> parseNoExcludeIdDimConfig(SemanticSchema semanticSchema) {
        if (semanticSchema == null || CollectionUtils.isEmpty(semanticSchema.getTerms())) {
            return Collections.emptyMap();
        }
        SchemaElement configTerm = semanticSchema.getTerms().stream()
                .filter(t -> TermConstants.NO_EXCLUDE_ID_DIMENSIONS.equals(t.getName())).findFirst()
                .orElse(null);
        if (configTerm == null || StringUtils.isBlank(configTerm.getDescription())) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Integer> result =
                    JsonUtil.toMap(configTerm.getDescription(), String.class, Integer.class);
            return result != null ? result : Collections.emptyMap();
        } catch (Exception e) {
            log.warn("parseNoExcludeIdDimConfig failed to parse term description: {}",
                    configTerm.getDescription(), e);
            return Collections.emptyMap();
        }
    }

    /**
     * the example in the agent will be executed by default, if the result is correct, it will be
     * put into memory as a reference for LLM
     *
     * @param agent
     */
    private void executeAgentExamplesAsync(Agent agent) {
        executor.execute(() -> doExecuteAgentExamples(agent));
    }

    private synchronized void doExecuteAgentExamples(Agent agent) {
        if (!agent.containsDatasetTool() || !agent.enableMemoryReview()
                || CollectionUtils.isEmpty(agent.getExamples())) {
            return;
        }

        List<String> examples = agent.getExamples();
        ChatMemoryFilter chatMemoryFilter =
                ChatMemoryFilter.builder().agentId(agent.getId()).questions(examples).build();
        List<String> memoriesExisted = memoryService.getMemories(chatMemoryFilter).stream()
                .map(ChatMemory::getQuestion).collect(Collectors.toList());
        for (String example : examples) {
            if (memoriesExisted.contains(example)) {
                continue;
            }
            try {
                chatQueryService
                        .parseAndExecute(ChatParseReq.builder().chatId(-1).agentId(agent.getId())
                                .queryText(example).user(User.getDefaultUser()).build());
            } catch (Exception e) {
                log.warn("agent:{} example execute failed:{}", agent.getName(), example);
            }
        }
    }

    private List<AgentDO> getAgentDOList() {
        return list();
    }

    private Agent convert(AgentDO agentDO) {
        if (agentDO == null) {
            return null;
        }
        Agent agent = new Agent();
        BeanUtils.copyProperties(agentDO, agent);
        agent.setToolConfig(agentDO.getToolConfig());
        List<String> examples = JsonUtil.toList(agentDO.getExamples(), String.class);
        LinkedList<String> examplesLinked = Lists.newLinkedList(examples);
        // NL2SQLParserConfig nl2SqlParserConfig = ContextUtils.getBean(NL2SQLParserConfig.class);
        // List<Integer> simpleModelAgentIds = nl2SqlParserConfig.getSimpleModelAgentIds();
        // // 检查当前请求的 agentId 是否在 simpleModelAgentIds 列表中
        // if (!examplesLinked.contains("我能够查询的数据范围")
        // && !simpleModelAgentIds.contains(agentDO.getId())) {
        // examplesLinked.addFirst("我能够查询的数据范围");
        // }
        agent.setExamples(examplesLinked);
        agent.setChatAppConfig(
                JsonUtil.toMap(agentDO.getChatModelConfig(), String.class, ChatApp.class));
        agent.setVisualConfig(JsonUtil.toObject(agentDO.getVisualConfig(), VisualConfig.class));
        agent.getChatAppConfig().values().forEach(c -> {
            if (c.isEnable()) {// 优化，减少访问数据库的次数
                ChatModel chatModel = chatModelService.getChatModel(c.getChatModelId());
                if (Objects.nonNull(chatModel)) {
                    c.setChatModelConfig(chatModel.getConfig());
                }
            }
        });
        agent.setAdmins(JsonUtil.toList(agentDO.getAdmin(), String.class));
        agent.setViewers(JsonUtil.toList(agentDO.getViewer(), String.class));
        agent.setAdminOrgs(JsonUtil.toList(agentDO.getAdminOrg(), String.class));
        agent.setViewOrgs(JsonUtil.toList(agentDO.getViewOrg(), String.class));
        agent.setIsOpen(agentDO.getIsOpen());
        return agent;
    }

    private AgentDO convert(Agent agent) {
        AgentDO agentDO = new AgentDO();
        BeanUtils.copyProperties(agent, agentDO);
        agentDO.setToolConfig(agent.getToolConfig());
        agentDO.setExamples(JsonUtil.toString(agent.getExamples()));
        agentDO.setChatModelConfig(JsonUtil.toString(agent.getChatAppConfig()));
        agentDO.setVisualConfig(JsonUtil.toString(agent.getVisualConfig()));
        agentDO.setAdmin(JsonUtil.toString(agent.getAdmins()));
        agentDO.setViewer(JsonUtil.toString(agent.getViewers()));
        agentDO.setAdminOrg(JsonUtil.toString(agent.getAdminOrgs()));
        agentDO.setViewOrg(JsonUtil.toString(agent.getViewOrgs()));
        agentDO.setIsOpen(agent.getIsOpen());
        if (agentDO.getStatus() == null) {
            agentDO.setStatus(1);
        }
        return agentDO;
    }

    private boolean checkAdminPermission(Set<String> orgIds, User user, Agent agent) {
        List<String> admins = agent.getAdmins();
        List<String> adminOrgs = agent.getAdminOrgs();
        if (user.isSuperAdmin()) {
            return true;
        }
        if (admins.contains(user.getName()) || agent.getCreatedBy().equals(user.getName())) {
            return true;
        }
        if (CollectionUtils.isEmpty(adminOrgs)) {
            return false;
        }
        for (String orgId : orgIds) {
            if (adminOrgs.contains(orgId)) {
                return true;
            }
        }
        return false;
    }

    private boolean checkViewPermission(Set<String> orgIds, User user, Agent agent) {
        if (checkAdminPermission(orgIds, user, agent)) {
            return true;
        }
        List<String> viewers = agent.getViewers();
        List<String> viewOrgs = agent.getViewOrgs();
        if (agent.openToAll()) {
            return true;
        }
        if (viewers.contains(user.getName())) {
            return true;
        }
        if (CollectionUtils.isEmpty(viewOrgs)) {
            return false;
        }
        for (String orgId : orgIds) {
            if (viewOrgs.contains(orgId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<TermDTO> getAgentTerms(Integer agentId, String termName, String alias, User user) {
        Agent agent = convert(getById(agentId));
        if (agent == null || agent.getDataSetIds() == null) {
            return new ArrayList<>();
        }

        Set<Long> dataSetIds = agent.getDataSetIds();
        SemanticSchema semanticSchema = schemaService.getSemanticSchema(dataSetIds);

        List<SchemaElement> terms = semanticSchema.getTerms();
        if (CollectionUtils.isEmpty(terms)) {
            return new ArrayList<>();
        }

        // 如果传入了术语名称，则按名称精确过滤
        List<SchemaElement> filteredTerms;
        if (StringUtils.isNotEmpty(termName)) {
            filteredTerms = terms.stream().filter(term -> termName.equals(term.getName()))
                    .collect(Collectors.toList());
        } else {
            filteredTerms = terms;
        }

        // 如果传入了别名关键词，则筛选别名中包含该值的术语
        if (StringUtils.isNotEmpty(alias)) {
            String aliasLower = alias.toLowerCase();
            filteredTerms = filteredTerms.stream()
                    .filter(term -> term.getAlias() != null && term.getAlias().stream()
                            .anyMatch(a -> a.toLowerCase().contains(aliasLower)))
                    .collect(Collectors.toList());
        }

        // 构建术语信息列表
        return filteredTerms.stream().map(term -> {
            TermDTO dto = new TermDTO();
            dto.setName(term.getName());
            dto.setDescription(term.getDescription());
            dto.setAlias(term.getAlias());
            dto.setDataSetId(term.getDataSetId());
            dto.setDataSetName(term.getDataSetName());
            if (term.getExtInfo() != null && !term.getExtInfo().isEmpty()) {
                dto.setExtInfo(term.getExtInfo());
            }
            return dto;
        }).collect(Collectors.toList());
    }

    @Override
    public AgentContextResp getAgentContext(Integer agentId, String queryText, String termName,
            String alias, User user) {
        String dataSetInfo = getAgentDataSetInfo(agentId, queryText, user);
        List<TermDTO> terms = getAgentTerms(agentId, termName, alias, user);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy年MM月dd日");
        String currentDate = LocalDate.now().format(formatter);

        return AgentContextResp.builder().dataSetInfo(dataSetInfo).terms(terms)
                .currentDate(currentDate).build();
    }

}
