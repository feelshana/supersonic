package com.tencent.supersonic.chat.server.service.impl;

import com.google.common.collect.Lists;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.agent.DimensionValueCheckReq;
import com.tencent.supersonic.chat.server.agent.DimensionValueCheckResp;
import com.tencent.supersonic.chat.server.agent.DimensionValueCheckResp.DimValueCheckItem;
import com.tencent.supersonic.chat.server.service.AgentService;
import com.tencent.supersonic.chat.server.service.DimensionValueValidationService;
import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.headless.api.pojo.response.*;
import com.tencent.supersonic.headless.server.service.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.provider.ModelProvider;
import dev.langchain4j.service.AiServices;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DimensionValueValidationServiceImpl implements DimensionValueValidationService {

    private static final String LLM_JUDGMENT_PROMPT = """
            ## 角色
            你是一个SQL查询分析专家。你的任务是结合数据集信息，分析用户问题对应的SQL查询条件是否已经明确。

            ## 数据集信息
            {{dataSetInfo}}
            数据集信息中包含了：
            - 维度列表（每个维度下含该维度的维度值列表，**字段名**=实际数据库列名）
            - 指标列表
            - 术语说明（如"沪上"映射为"上海"）
            - 当前日期
            - 用户问题已映射到的维度及维度值（仅作为参考，不代表已确认）

            **重要：维度信息中的"字段名"是该维度对应的实际数据库列名**。
            例如：`- 省份（字段名：province_name）` 表示省份维度对应的数据库列名为 `province_name`。
            当需要输出 unclearDimensions 时，bizName 字段应填写该维度的"字段名"值。

            ## 用户问题
            {{queryText}}

            ## 重要规则

            **关于映射结果的警告**：
            数据集中第5项"用户问题已映射到的维度及其维度值"是通过向量和相似度匹配出来的结果，**不代表该值在数据集中真实存在**。
            判断一个维度值是否"已确认"的**唯一标准**是：该值（或经过术语说明转换后的值）是否**精确出现在该维度的维度值列表中**。

            示例：
            - 映射结果显示"省份：沪上"，术语说明指出"沪上"="上海"，且省份维度的维度值列表中包含"上海" → **已确认**
            - 映射结果显示"体育类型：BA"，但体育类型维度的维度值列表中没有"BA"、"篮球通会员"等任何相关值 → **不确定，需查库确认**

            ## 分析步骤

            ### 第一步：理解用户问题意图
            判断用户问题的类型（questionType）：
            - **query_data**（查数据/查指标）：如"体育产品的销售额是多少"→ 需要知道SELECT什么指标、WHERE什么维度值
            - **ask_dimension**（问维度）：如"有哪些产品维度"→ 不涉及具体值，属于元数据查询
            - **ask_metric_definition**（问指标口径/用法）：如"销售额指标怎么算的"→ 不涉及具体值
            - **combination**（组合查询）：如"上个月广东省的体育产品收入和访问量对比"→ 需要多个维度值+指标+日期范围
            - **other**（其他）：不属于以上类型

            ### 第二步：提取已确认的查询要素（confirmedInfo）
            从用户问题中提取以下已明确的信息：

            **metrics（指标）**：用户明确提到的指标名称，从指标列表中匹配
            - 如"销售额是多少"→ ["销售额"]
            - 如"收入和访问量"→ ["收入", "访问量"]
            - 如"各产品销量"→ ["销量"]
            - 没有明确提指标→ []

            **dimensions（维度）**：用户明确指定了筛选值的维度
            - 如"体育产品的销售额"→ ["产品"]（因为指定了"体育"这个值）
            - 如"广东省的订单"→ ["省份"]
            - 如"各产品的销售额"→ []（没有指定具体值，只是分组维度）

            **dateRange（日期范围）**：
            - 用户明确说了日期→ 直接提取，如"上个月""2026年4月"
            - 用户没说日期，但数据集有日期字段→ "默认近7天（日表）/近3个月（月表）"
            - 数据集没有日期字段→ "无日期字段"

            **otherConditions（其他筛选条件）**：除维度值和日期外的额外条件
            - 如"排除退款订单"→ "排除退款"
            - 没有额外条件→ ""

            ### 第三步：判断维度值是否清楚
            对于每个涉及的**非日期维度**的**具体维度值**：

            1. 先检查术语说明，将用户输入值转换为标准值（如"沪上"→"上海"）
            2. 然后在对应维度的维度值列表中查找：
               - **精确匹配维度值列表中的某个值** → 放入 confirmedDimensions
                 - userInput = 用户原始输入（如"沪上"）
                 - matchedValue = 标准值/维度值列表中的值（如"上海"）
               - **不在维度值列表中，但能判断属于哪个维度** → 放入 unclearDimensions（需查库确认）
               - **不在维度值列表中，且无法判断属于哪个维度** → 放入 unclearDimensions（需查库确认）

            **特殊场景处理**：
            - 用户问题不涉及任何具体维度值（如问口径、问用法、问有哪些维度/指标）→ allClear=true
            - 用户问题只提到指标名，没有提维度值 → allClear=true
            - 日期相关的筛选条件 → 始终视为已确认，不放入 unclearDimensions

            ## 输出要求
            请严格按JSON格式输出，不要添加任何额外说明。
            """;

    private static final String LLM_MATCH_PROMPT = """
            ## 角色
            你是一个维度值匹配专家。你的任务是从数据库查询结果中，判断用户提到的维度值是否可以用于SQL的WHERE条件。

            ## 数据集信息
            {{dataSetInfo}}

            ## 用户问题
            {{queryText}}

            ## 数据库查询结果
            {{dbResults}}
            以上是数据库中该维度实际存在的所有值。

            ## 匹配规则

            逐个维度进行判断：

            **1. confirmed（已确认）**
            - 数据库结果中**恰好包含**用户提到的值（完全一致）
            - 用户提到的值可以**直接用于SQL的WHERE条件**

            **2. candidates（有候选值，需用户确认）**
            - 数据库结果中**没有**用户提到的值，但存在**语义相近**的值
            - 例如：用户说"体育"，数据库中有"体育赛事"、"体育精选"
            - 需要返回候选值列表，让用户选择

            **3. not_matched（未匹配）**
            - 数据库结果中**完全没有任何**与用户值相关的值
            - 用户提到的值无法用于WHERE条件，该查询无数据

            **4. dimension_not_exist（维度不存在）**
            - 数据库查询结果为空，该维度在数据集中不存在有效值

            ## 输出要求
            请严格按JSON格式输出，不要添加任何额外说明。
            """;

    @Autowired
    private AgentService agentService;
    @Autowired
    private ModelService modelService;
    @Autowired
    private DatabaseService databaseService;

    @Value("${s2.dimension.value.validation.enabled:true}")
    private Boolean validationEnabled;

    @Value("${s2.dimension.value.fallback-db-limit:100}")
    private int fallbackDbLimit;

    @Override
    public DimensionValueCheckResp validate(DimensionValueCheckReq req, User user) {
        DimensionValueCheckResp resp = new DimensionValueCheckResp();
        resp.setCheckItems(new ArrayList<>());

        if (!validationEnabled) {
            resp.setAllConfirmed(true);
            return resp;
        }

        Agent agent = agentService.getAgent(req.getAgentId());
        if (agent == null || agent.getDataSetIds() == null || agent.getDataSetIds().isEmpty()) {
            resp.setAllConfirmed(true);
            return resp;
        }

        // 1. 调用 getAgentDataSetInfoForValidation 获取含字段名的数据集信息
        String dataSetInfoStr = agentService.getAgentDataSetInfoForValidation(req.getAgentId(),
                req.getQueryText(), user);
        if (StringUtils.isBlank(dataSetInfoStr)) {
            resp.setAllConfirmed(true);
            return resp;
        }

        // 2. 获取数据库连接信息
        DatabaseInfo dbInfo = getDatabaseInfoForAgent(agent);

        // 3. LLM 第一轮判断：是否清楚 + 提取已确认信息
        DimensionValueJudgment judgment =
                llmJudgeClarity(agent, dataSetInfoStr, req.getQueryText());

        if (judgment.isAllClear()) {
            // 全部确认，直接返回
            resp.setAllConfirmed(true);
            resp.setNeedUserConfirm(false);
            resp.setCheckItems(buildConfirmedItems(judgment));
            resp.setConfirmedInfo(buildConfirmedInfoString(judgment));
            resp.setUnconfirmedInfo("");
            resp.setEnrichedContext(judgment.getSummary());
            log.info(
                    "[DimensionValueValidation] agentId={}, queryText={}, allConfirmed=true (LLM clear)",
                    req.getAgentId(), req.getQueryText());
            return resp;
        }

        // 4. 对不确定的维度，查数据库获取所有维度值
        Map<String, List<String>> dbResults = queryUnclearDimensions(dbInfo, judgment, req);

        // 5. LLM 第二轮判断：匹配数据库结果
        DimensionValueMatch matchResult =
                llmMatchValues(agent, dataSetInfoStr, req.getQueryText(), judgment, dbResults);

        // 6. 构建返回结果（区分已确认和不确定信息）
        return buildResponse(matchResult, judgment, req);
    }

    /**
     * LLM 第一轮：判断用户问题中的维度值是否清楚
     */
    private DimensionValueJudgment llmJudgeClarity(Agent agent, String dataSetInfo,
            String queryText) {
        ChatLanguageModel model = getChatModel(agent);
        if (model == null) {
            log.warn("Failed to get chat model, fallback to rule-based validation");
            return ruleBasedJudgment(dataSetInfo, queryText);
        }

        String promptText = PromptTemplate.from(LLM_JUDGMENT_PROMPT)
                .apply(Map.of("dataSetInfo", dataSetInfo, "queryText", queryText)).toUserMessage()
                .singleText();

        log.info("[LLM Round 1] Prompt length: {}", promptText.length());

        try {
            DimensionValueJudgmentExtractor extractor =
                    AiServices.create(DimensionValueJudgmentExtractor.class, model);
            DimensionValueJudgment result = extractor.judge(promptText);
            log.info("[LLM Round 1] Result: allClear={}, unclearDimensions={}", result.isAllClear(),
                    result.getUnclearDimensions());
            return result;
        } catch (Exception e) {
            log.error("[LLM Round 1] Error: {}", e.getMessage());
            return ruleBasedJudgment(dataSetInfo, queryText);
        }
    }

    /**
     * LLM 第二轮：从数据库查询结果中匹配维度值
     */
    private DimensionValueMatch llmMatchValues(Agent agent, String dataSetInfo, String queryText,
            DimensionValueJudgment judgment, Map<String, List<String>> dbResults) {

        ChatLanguageModel model = getChatModel(agent);
        if (model == null) {
            log.warn("Failed to get chat model, fallback to rule-based matching");
            return ruleBasedMatch(judgment, dbResults);
        }

        // 构建数据库查询结果字符串
        StringBuilder dbResultsStr = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : dbResults.entrySet()) {
            dbResultsStr.append("- 维度「").append(entry.getKey()).append("」的数据库值: ")
                    .append(String.join("、", entry.getValue())).append("\n");
        }

        String promptText = PromptTemplate
                .from(LLM_MATCH_PROMPT).apply(Map.of("dataSetInfo", dataSetInfo, "queryText",
                        queryText, "dbResults", dbResultsStr.toString()))
                .toUserMessage().singleText();

        log.info("[LLM Round 2] Prompt length: {}", promptText.length());

        try {
            DimensionValueMatchExtractor extractor =
                    AiServices.create(DimensionValueMatchExtractor.class, model);
            DimensionValueMatch result = extractor.match(promptText);
            log.info("[LLM Round 2] Result: {}", result);
            return result;
        } catch (Exception e) {
            log.error("[LLM Round 2] Error: {}", e.getMessage());
            return ruleBasedMatch(judgment, dbResults);
        }
    }

    /**
     * 查询不确定的维度的所有维度值
     */
    private Map<String, List<String>> queryUnclearDimensions(DatabaseInfo dbInfo,
            DimensionValueJudgment judgment, DimensionValueCheckReq req) {

        Map<String, List<String>> results = new HashMap<>();
        if (dbInfo == null || dbInfo.getDatabase() == null
                || StringUtils.isBlank(dbInfo.getTableName())) {
            log.warn("No database info available for dimension value query");
            return results;
        }

        String dateCondition = buildDateCondition(req);

        for (DimensionValueJudgment.UnclearDim unclearDim : judgment.getUnclearDimensions()) {
            String bizName = unclearDim.getBizName();
            if (StringUtils.isBlank(bizName)) {
                continue;
            }

            String sql;
            if (StringUtils.isNotBlank(dateCondition)) {
                sql = String.format("SELECT DISTINCT %s FROM %s WHERE %s LIMIT %d", bizName,
                        dbInfo.getTableName(), dateCondition, fallbackDbLimit);
            } else {
                sql = String.format("SELECT DISTINCT %s FROM %s LIMIT %d", bizName,
                        dbInfo.getTableName(), fallbackDbLimit);
            }

            log.info("[DB Query] dimension={}, SQL: {}", unclearDim.getDimensionName(), sql);

            try {
                SemanticQueryResp queryResp = databaseService.executeSql(sql, dbInfo.getDatabase());
                if (queryResp != null && !CollectionUtils.isEmpty(queryResp.getResultList())) {
                    List<String> values = queryResp.getResultList().stream().map(row -> {
                        Object val = row.get(bizName);
                        return val != null ? val.toString().trim() : null;
                    }).filter(StringUtils::isNotBlank).distinct().collect(Collectors.toList());
                    results.put(unclearDim.getDimensionName(), values);
                    log.info("[DB Query] dimension={}, found {} values",
                            unclearDim.getDimensionName(), values.size());
                } else {
                    results.put(unclearDim.getDimensionName(), new ArrayList<>());
                    log.info("[DB Query] dimension={}, no results", unclearDim.getDimensionName());
                }
            } catch (Exception e) {
                log.error("[DB Query] Error for dimension {}: {}", unclearDim.getDimensionName(),
                        e.getMessage());
                results.put(unclearDim.getDimensionName(), new ArrayList<>());
            }
        }

        return results;
    }

    /**
     * 构建响应：区分已确认信息和不确定信息
     */
    private DimensionValueCheckResp buildResponse(DimensionValueMatch matchResult,
            DimensionValueJudgment judgment, DimensionValueCheckReq req) {
        DimensionValueCheckResp resp = new DimensionValueCheckResp();
        List<DimValueCheckItem> checkItems = new ArrayList<>();
        boolean allConfirmed = true;
        boolean needUserConfirm = false;
        int candidateCount = 0;
        StringBuilder confirmedInfo = new StringBuilder();
        StringBuilder unconfirmedInfo = new StringBuilder();

        // 构建已确认信息：来自第一轮 LLM 提取的 confirmedInfo + confirmedDimensions
        if (judgment.getConfirmedInfo() != null) {
            DimensionValueJudgment.ConfirmedInfo info = judgment.getConfirmedInfo();
            if (!CollectionUtils.isEmpty(info.getMetrics())) {
                confirmedInfo.append("查询指标：").append(String.join("、", info.getMetrics()))
                        .append("；");
            }
            if (StringUtils.isNotBlank(info.getDateRange())) {
                confirmedInfo.append("日期范围：").append(info.getDateRange()).append("；");
            }
        }

        // 已确认的维度值
        if (!CollectionUtils.isEmpty(judgment.getConfirmedDimensions())) {
            for (DimensionValueJudgment.ConfirmedDim confirmed : judgment
                    .getConfirmedDimensions()) {
                // 优先使用 matchedValue（标准值），无则用 userInput
                String value = StringUtils.isNotBlank(confirmed.getMatchedValue())
                        ? confirmed.getMatchedValue()
                        : confirmed.getUserInput();
                confirmedInfo.append("维度「").append(confirmed.getDimensionName()).append("」：")
                        .append(value).append("（已确认）；");
            }
        }

        // 第二轮匹配结果
        if (!CollectionUtils.isEmpty(matchResult.getCheckItems())) {
            for (DimensionValueMatch.MatchedItem item : matchResult.getCheckItems()) {
                DimValueCheckItem checkItem = new DimValueCheckItem();
                checkItem.setDimensionName(item.getDimensionName());
                checkItem.setUserInput(item.getUserInput());
                checkItem.setBizName(item.getBizName());

                switch (item.getStatus()) {
                    case "confirmed":
                        checkItem.setCheckLevel("llm_confirmed");
                        checkItem.setMatchedValue(item.getMatchedValue());
                        checkItem.setNeedConfirm(false);
                        confirmedInfo.append("维度「").append(item.getDimensionName()).append("」：")
                                .append(item.getMatchedValue()).append("（已确认）；");
                        break;
                    case "candidates":
                        checkItem.setCheckLevel("llm_candidates");
                        checkItem.setCandidates(item.getCandidates());
                        checkItem.setNeedConfirm(true);
                        needUserConfirm = true;
                        allConfirmed = false;
                        candidateCount++;
                        // 结构化格式：方便前端展示 + 代码节点解析
                        unconfirmedInfo.append("- 维度「").append(item.getDimensionName())
                                .append("」：输入值=").append(item.getUserInput()).append(" | 候选值=[")
                                .append(String.join(", ", item.getCandidates())).append("]")
                                .append("\n");
                        break;
                    case "dimension_not_exist":
                        checkItem.setCheckLevel("llm_dimension_not_exist");
                        checkItem.setNeedConfirm(false);
                        allConfirmed = false;
                        unconfirmedInfo.append("- 维度「").append(item.getDimensionName())
                                .append("」：该维度在数据集中无有效值").append("\n");
                        break;
                    case "not_matched":
                    default:
                        checkItem.setCheckLevel("llm_not_matched");
                        checkItem.setNeedConfirm(false);
                        allConfirmed = false;
                        unconfirmedInfo.append("- 维度「").append(item.getDimensionName())
                                .append("」：输入值=").append(item.getUserInput()).append(" | 未匹配到任何候选值")
                                .append("\n");
                        if (resp.getUnmatchedValue() == null) {
                            resp.setUnmatchedValue(item.getUserInput());
                        }
                        break;
                }

                checkItems.add(checkItem);
            }
        }

        // 给 unconfirmedInfo 加标题头
        if (StringUtils.isNotEmpty(unconfirmedInfo)) {
            unconfirmedInfo.insert(0, "【不确定项】\n");
        }

        resp.setCheckItems(checkItems);
        resp.setAllConfirmed(allConfirmed);
        resp.setNeedUserConfirm(needUserConfirm);
        resp.setCandidateCount(candidateCount);

        // 已确认信息：用于传给下一个节点
        if (StringUtils.isNotEmpty(confirmedInfo)) {
            resp.setConfirmedInfo(confirmedInfo.substring(0, confirmedInfo.length() - 1));
        } else {
            resp.setConfirmedInfo("用户问题不涉及具体查询条件");
        }

        // 不确定信息：用于人工介入节点
        if (StringUtils.isNotEmpty(unconfirmedInfo)) {
            resp.setUnconfirmedInfo(unconfirmedInfo.substring(0, unconfirmedInfo.length() - 1));
        } else {
            resp.setUnconfirmedInfo("");
        }

        // 简要汇总（向后兼容）
        resp.setEnrichedContext(resp.getConfirmedInfo());

        log.info(
                "[DimensionValueValidation] agentId={}, queryText={}, allConfirmed={}, needConfirm={}, candidateCount={}, items={}",
                req.getAgentId(), req.getQueryText(), allConfirmed, needUserConfirm, candidateCount,
                checkItems.size());
        return resp;
    }

    // =============== 规则判断的降级方案 ===============

    private DimensionValueJudgment ruleBasedJudgment(String dataSetInfo, String queryText) {
        // 简单规则：如果没有提取到值，默认全部确认
        DimensionValueJudgment result = new DimensionValueJudgment();
        result.setAllClear(true);
        result.setSummary("规则判断：未检测到需要校验的维度值");
        result.setUnclearDimensions(new ArrayList<>());
        result.setConfirmedDimensions(new ArrayList<>());
        DimensionValueJudgment.ConfirmedInfo info = new DimensionValueJudgment.ConfirmedInfo();
        info.setMetrics(new ArrayList<>());
        info.setDateRange("默认近7天（日表）/近3个月（月表）");
        result.setConfirmedInfo(info);
        return result;
    }

    private DimensionValueMatch ruleBasedMatch(DimensionValueJudgment judgment,
            Map<String, List<String>> dbResults) {
        DimensionValueMatch result = new DimensionValueMatch();
        List<DimensionValueMatch.MatchedItem> items = new ArrayList<>();

        for (DimensionValueJudgment.UnclearDim unclearDim : judgment.getUnclearDimensions()) {
            DimensionValueMatch.MatchedItem item = new DimensionValueMatch.MatchedItem();
            item.setDimensionName(unclearDim.getDimensionName());
            item.setUserInput(unclearDim.getUserInput());
            item.setBizName(unclearDim.getBizName());

            List<String> values =
                    dbResults.getOrDefault(unclearDim.getDimensionName(), new ArrayList<>());
            if (values.contains(unclearDim.getUserInput())) {
                item.setStatus("confirmed");
                item.setMatchedValue(unclearDim.getUserInput());
            } else if (!values.isEmpty()) {
                item.setStatus("candidates");
                item.setCandidates(values);
            } else {
                item.setStatus("not_matched");
            }
            items.add(item);
        }

        result.setCheckItems(items);
        return result;
    }

    // =============== 辅助方法 ===============

    /**
     * 获取 agent 关联的 ChatModel
     */
    private ChatLanguageModel getChatModel(Agent agent) {
        try {
            Map<String, ChatApp> chatAppConfig = agent.getChatAppConfig();
            if (chatAppConfig == null || chatAppConfig.isEmpty()) {
                log.warn("Agent {} has no chatAppConfig", agent.getId());
                return null;
            }

            // 优先使用启用的 chat model
            for (ChatApp app : chatAppConfig.values()) {
                if (app != null && app.isEnable() && app.getChatModelConfig() != null) {
                    return ModelProvider.getChatModel(app.getChatModelConfig());
                }
            }

            log.warn("No enabled chat model found for agent {}", agent.getId());
            return null;
        } catch (Exception e) {
            log.error("Failed to get chat model: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取 agent 关联的数据库连接信息和表名
     */
    private DatabaseInfo getDatabaseInfoForAgent(Agent agent) {
        if (agent.getDataSetIds() == null || agent.getDataSetIds().isEmpty()) {
            return null;
        }
        try {
            Long dataSetId = agent.getDataSetIds().iterator().next();
            DataSetResp dataSet = ContextUtils.getBean(DataSetService.class).getDataSet(dataSetId);
            if (dataSet == null) {
                return null;
            }
            List<ModelResp> models =
                    modelService.getAllModelByDomainIds(Lists.newArrayList(dataSet.getDomainId()));
            if (!CollectionUtils.isEmpty(models)) {
                ModelResp model = models.get(0);
                DatabaseInfo info = new DatabaseInfo();
                if (model.getDatabaseId() != null) {
                    info.setDatabase(databaseService.getDatabase(model.getDatabaseId()));
                }
                if (model.getModelDetail() != null) {
                    info.setTableName(model.getModelDetail().getTableQuery());
                }
                if (StringUtils.isBlank(info.getTableName())) {
                    info.setTableName(model.getName());
                }
                return info;
            }
        } catch (Exception e) {
            log.error("Failed to get database info for agent: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 构建日期条件
     */
    private String buildDateCondition(DimensionValueCheckReq req) {
        String startDate, endDate;

        if (StringUtils.isNotBlank(req.getStartDate())
                && StringUtils.isNotBlank(req.getEndDate())) {
            startDate = req.getStartDate();
            endDate = req.getEndDate();
        } else {
            LocalDate today = LocalDate.now();
            LocalDate oneWeekAgo = today.minusDays(7);
            DateTimeFormatter formatter = DateTimeFormatter.BASIC_ISO_DATE;
            startDate = oneWeekAgo.format(formatter);
            endDate = today.format(formatter);
        }

        // 查找日期维度的 bizName
        return String.format("period_id >= '%s' AND period_id <= '%s'", startDate, endDate);
    }

    // =============== LLM 结构化提取接口 ===============

    interface DimensionValueJudgmentExtractor {
        DimensionValueJudgment judge(String text);
    }

    interface DimensionValueMatchExtractor {
        DimensionValueMatch match(String text);
    }

    // =============== LLM 结构化输出 DTO ===============

    @Data
    public static class DimensionValueJudgment {

        @Description("所有查询条件是否都已确认。true=可直接查询，false=有待确认项")
        private boolean allClear;

        @Description("用户问题类型：query_data(查数据)/ask_dimension(问维度)/ask_metric_definition(问口径)/combination(组合查询)/other(其他)")
        private String questionType;

        @Description("已确认的查询要素")
        private ConfirmedInfo confirmedInfo;

        @Description("已确认的维度值列表")
        private List<ConfirmedDim> confirmedDimensions;

        @Description("不清楚的维度值列表（需要查库确认的）")
        private List<UnclearDim> unclearDimensions;

        @Description("判断摘要，用于日志和上下文")
        private String summary;

        @Data
        public static class ConfirmedInfo {

            @Description("识别到的指标名称列表（如[销售额, 访问量]），未提及则为空列表")
            private List<String> metrics;

            @Description("识别到的维度列表（如[产品, 省份]），用户明确指定了筛选值才算")
            private List<String> dimensions;

            @Description("日期范围描述（如'2026年4月1日至4月28日'、'近7天'、'未指定，默认近7天'）")
            private String dateRange;

            @Description("其他筛选条件描述（如有）")
            private String otherConditions;
        }

        @Data
        public static class ConfirmedDim {

            @Description("维度名称，如'产品'、'省份'")
            private String dimensionName;

            @Description("用户输入的值（原始值，如'沪上'）")
            private String userInput;

            @Description("匹配到的标准值（经过术语说明转换后的值，如'上海'。如果无转换则等于userInput）")
            private String matchedValue;

            @Description("维度的实际数据库列名（即维度信息中的'字段名'，如'province_name'）")
            private String bizName;
        }

        @Data
        public static class UnclearDim {

            @Description("维度名称，如'产品'、'省份'")
            private String dimensionName;

            @Description("用户输入的值")
            private String userInput;

            @Description("维度的实际数据库列名（即维度信息中的'字段名'，如'province_name'，不是中文名），用于构建SQL查询")
            private String bizName;

            @Description("不确定的原因：不在预存值中 / mapping未匹配 / 无法判断维度等")
            private String reason;
        }
    }

    @Data
    public static class DimensionValueMatch {

        @Description("匹配结果列表")
        private List<MatchedItem> checkItems;

        @Description("匹配摘要")
        private String summary;

        @Data
        public static class MatchedItem {

            @Description("维度名称")
            private String dimensionName;

            @Description("用户输入的值")
            private String userInput;

            @Description("维度字段名（bizName）")
            private String bizName;

            @Description("匹配状态：confirmed（已确认）、candidates（有候选值需用户确认）、not_matched（未匹配）")
            private String status;

            @Description("匹配到的值（status为confirmed时）")
            private String matchedValue;

            @Description("候选值列表（status为candidates时）")
            private List<String> candidates;
        }
    }

    @lombok.Data
    private static class DatabaseInfo {
        private DatabaseResp database;
        private String tableName;
    }

    /**
     * 构建确认的 check items
     */
    private List<DimValueCheckItem> buildConfirmedItems(DimensionValueJudgment judgment) {
        List<DimValueCheckItem> items = new ArrayList<>();
        if (!CollectionUtils.isEmpty(judgment.getConfirmedDimensions())) {
            for (DimensionValueJudgment.ConfirmedDim confirmed : judgment
                    .getConfirmedDimensions()) {
                DimValueCheckItem item = new DimValueCheckItem();
                item.setDimensionName(confirmed.getDimensionName());
                item.setUserInput(confirmed.getUserInput());
                item.setBizName(confirmed.getBizName());
                item.setCheckLevel("llm_confirmed");
                // 优先使用 matchedValue（标准值），无则用 userInput
                item.setMatchedValue(StringUtils.isNotBlank(confirmed.getMatchedValue())
                        ? confirmed.getMatchedValue()
                        : confirmed.getUserInput());
                item.setNeedConfirm(false);
                items.add(item);
            }
        }
        return items;
    }

    /**
     * 将 LLM 第一轮判断的已确认信息整理成字符串
     */
    private String buildConfirmedInfoString(DimensionValueJudgment judgment) {
        StringBuilder sb = new StringBuilder();

        // 问题类型
        if (StringUtils.isNotBlank(judgment.getQuestionType())) {
            sb.append("问题类型：").append(judgment.getQuestionType()).append("；");
        }

        // 确认的信息
        if (judgment.getConfirmedInfo() != null) {
            DimensionValueJudgment.ConfirmedInfo info = judgment.getConfirmedInfo();
            if (!CollectionUtils.isEmpty(info.getMetrics())) {
                sb.append("查询指标：").append(String.join("、", info.getMetrics())).append("；");
            }
            if (StringUtils.isNotBlank(info.getDateRange())) {
                sb.append("日期范围：").append(info.getDateRange()).append("；");
            }
            if (StringUtils.isNotBlank(info.getOtherConditions())) {
                sb.append("其他条件：").append(info.getOtherConditions()).append("；");
            }
        }

        // 已确认的维度值
        if (!CollectionUtils.isEmpty(judgment.getConfirmedDimensions())) {
            for (DimensionValueJudgment.ConfirmedDim confirmed : judgment
                    .getConfirmedDimensions()) {
                String value = StringUtils.isNotBlank(confirmed.getMatchedValue())
                        ? confirmed.getMatchedValue()
                        : confirmed.getUserInput();
                sb.append("维度「").append(confirmed.getDimensionName()).append("」：").append(value)
                        .append("（已确认）；");
            }
        }

        if (sb.isEmpty()) {
            return "用户问题不涉及具体查询条件";
        }
        return sb.substring(0, sb.length() - 1);
    }
}
