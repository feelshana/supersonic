package com.tencent.supersonic.chat.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.pagehelper.PageInfo;
import com.google.common.collect.Lists;
import com.tencent.supersonic.auth.api.authentication.service.UserService;
import com.tencent.supersonic.chat.api.pojo.request.ChatMemoryFilter;
import com.tencent.supersonic.chat.api.pojo.request.ChatParseReq;
import com.tencent.supersonic.chat.server.agent.Agent;
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
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.service.ChatModelService;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SemanticSchema;
import com.tencent.supersonic.headless.api.pojo.request.PageDimensionReq;
import com.tencent.supersonic.headless.api.pojo.request.PageMetricReq;
import com.tencent.supersonic.headless.api.pojo.request.PageSchemaItemReq;
import com.tencent.supersonic.headless.api.pojo.response.*;
import com.tencent.supersonic.headless.chat.parser.llm.OnePassSCSqlGenStrategy;
import com.tencent.supersonic.headless.chat.parser.llm.SimpleStrategy;
import com.tencent.supersonic.headless.chat.parser.llm.SqlGenStrategyFactory;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
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

    @Override
    public String getAgentDataSetInfo(Integer agentId, String queryText, User user) {
        Agent agent = convert(getById(agentId));
        Set<Long> dataSetIds = agent.getDataSetIds();
        SemanticSchema semanticSchema = schemaService.getSemanticSchema(dataSetIds);

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy年MM月dd日");
        String currentDate = dateFormat.format(new Date());

        Map<String, String> termsMap = semanticSchema.getTerms().stream()
                .collect(Collectors.toMap(SchemaElement::getName, SchemaElement::getDescription));

        // 构建维度信息，包括维度值
        StringBuilder dimensionsInfo = new StringBuilder();
        for (SchemaElement dimension : semanticSchema.getDimensions()) {
            dimensionsInfo.append("   - ").append(dimension.getName());
            // 筛选日期维度做单独说明
            if (StringUtils.isNotEmpty(dimension.getTimeFormat())) {
                dimensionsInfo.append(" FORMAT '").append(dimension.getTimeFormat()).append("'");
            }
            dimensionsInfo.append("\n");
            // 获取维度值
            if (dimension.isHasDimValues()) {
                PageInfo<DictValueDimResp> dimensionValuesFromDict =
                        onePassSCSqlGenStrategy.getDimensionValuesFromDict(dimension);
                List<DictValueDimResp> list = dimensionValuesFromDict.getList();
                List<String> dimensionValues =
                        list.stream().map(DictValueDimResp::getValue).toList();

                // 限制最多显示50个维度值
                if (!CollectionUtils.isEmpty(dimensionValues)) {
                    int limit = Math.min(dimensionValues.size(), 50);
                    dimensionsInfo.append("     维度值: ").append(dimensionValues.subList(0, limit)
                            .stream().collect(Collectors.joining(", "))).append("\n");
                }
            }


        }

        String replyGuideline = "当前报表包含以下数据集信息：\n" + "1. 维度列表：\n" + dimensionsInfo.toString()
                + "\n2. 指标列表：\n"
                + semanticSchema.getMetrics().stream().map(m -> "   - " + m.getName())
                        .collect(Collectors.joining("\n"))
                + "\n3. 术语说明：\n"
                + termsMap.entrySet().stream().map(e -> "   - " + e.getKey() + ": " + e.getValue())
                        .collect(Collectors.joining("\n"))
                + "\n4. 当前日期：" + currentDate
                // 增加日期格式说明，yyyyMMdd格式为日表，yyyyMM格式为月表
                + "\n5. 当前数据集日期格式："
                + semanticSchema.getDimensions().stream()
                        .filter(d -> StringUtils.isNotEmpty(d.getTimeFormat()))
                        .map(d -> d.getName() + " FORMAT '" + d.getTimeFormat() + "'")
                        .collect(Collectors.joining("\n"));

        // 还需要拼接上数据集的id和模型id
        replyGuideline += "\n数据集ID："
                + dataSetIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        replyGuideline += "\n模型ID：" + semanticSchema.getDimensions().getFirst().getModel();
        return replyGuideline;
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

}
