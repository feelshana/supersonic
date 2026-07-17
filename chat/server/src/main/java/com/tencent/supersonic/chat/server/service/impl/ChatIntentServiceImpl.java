package com.tencent.supersonic.chat.server.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.tencent.supersonic.chat.api.pojo.enums.ChatIntentType;
import com.tencent.supersonic.chat.api.pojo.request.ChatIntentReq;
import com.tencent.supersonic.chat.api.pojo.response.ChatIntentContext;
import com.tencent.supersonic.chat.api.pojo.response.ChatIntentResp;
import com.tencent.supersonic.chat.server.service.ChatIntentService;
import com.tencent.supersonic.chat.server.service.ChatManageService;
import com.tencent.supersonic.common.config.ChatModel;
import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AppModule;
import com.tencent.supersonic.common.service.ChatModelService;
import com.tencent.supersonic.common.util.ChatAppManager;
import com.tencent.supersonic.headless.server.utils.ModelConfigHelper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.provider.ModelProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class ChatIntentServiceImpl implements ChatIntentService {

    public static final String APP_KEY = "INTENT_CLASSIFIER";

    private static final String DEFAULT_PROMPT =
            "你是一个严格的意图识别专家。你的任务只有一个：判断用户的提问是否属于\"请求基于当前页面数据进行数据解读/分析\"。\n" + "\n" + "【判断标准】\n"
                    + "只有当用户明确表达了以下意图时，才输出 type = 1：\n"
                    + "1. 用户引用了\"当前\"、\"本\"、\"这个\"等指向当前页面/报表/仪表盘的指示词\n"
                    + "2. 用户使用了\"解读\"、\"分析\"、\"看看\"、\"说明\"、\"评价\"、\"总结\"、\"怎么看\"、\"介绍一下\"等分析类动词\n"
                    + "3. 用户的提问对象是\"数据\"、\"报表\"、\"页面\"、\"图表\"等当前页面的内容\n" + "\n" + "【判断示例】\n"
                    + "✅ type = 1 的情况：\n" + "- \"帮我解读一下当前数据\"\n" + "- \"分析一下这个报表\"\n"
                    + "- \"看看本页面的数据\"\n" + "- \"总结一下当前的图表\"\n" + "- \"帮我分析当前数据\"\n"
                    + "- \"这个报表说明了什么\"\n" + "- \"当前数据怎么看\"\n" + "- \"根据当前页面数据给个分析\"\n"
                    + "- \"评价一下本报表的数据表现\"\n" + "\n" + "❌ type = 0 的情况（虽然包含\"解读/分析\"但非当前页面）：\n"
                    + "- \"帮我解读一下上个月的报表\" → 非当前页面\n" + "- \"分析一下全省的数据\" → 未指定\"当前\"\n"
                    + "- \"查询一下活跃用户数\" → 纯数据查询，非解读\n" + "- \"福建省的排名是多少\" → 纯查询\n"
                    + "- \"这个指标是什么意思\" → 指标解释，非数据解读\n" + "- \"你好\" / \"在吗\" → 无关问候\n" + "\n"
                    + "【特别提醒】\n"
                    + "1. 如果用户的问题可以用\"一次SQL查询\"直接回答（如查排名、查某指标值），即使包含\"看看\"等词，也应判为 type = 0\n"
                    + "2. 数据解读 = 综合性分析（多维度、多指标、有结论），不是单点数值查询\n" + "3. 宁可漏判，不可误判。不确定时输出 0\n" + "\n"
                    + "【输出格式】\n" + "只输出一个 JSON 对象，格式为：{\"type\": 1} 或 {\"type\": 0}\n"
                    + "不要输出任何其他内容、解释或标点。\n" + "当前用户问题：\n" + "{{queryText}}";

    private static final Pattern TYPE_PATTERN =
            Pattern.compile("\"type\"\\s*[:=]\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy年MM月dd日");

    private static final int MAX_CHAT_NAME_LENGTH = 50;

    private final ChatManageService chatManageService;
    private final ChatModelService chatModelService;
    private final Integer configuredModelId;

    @Autowired
    public ChatIntentServiceImpl(ChatManageService chatManageService,
            ChatModelService chatModelService,
            @Value("${chat.intent.classifier.prompt:}") String customPrompt,
            @Value("${chat.intent.classifier.model-id:3}") Integer configuredModelId) {
        this.chatManageService = chatManageService;
        this.chatModelService = chatModelService;
        this.configuredModelId = configuredModelId;
        registerChatApp(customPrompt);
    }

    private void registerChatApp(String customPrompt) {
        String prompt = StringUtils.isNotBlank(customPrompt) ? customPrompt : DEFAULT_PROMPT;
        ChatAppManager.register(APP_KEY,
                ChatApp.builder().name("askdata意图分类器").description("判断 askdata 分支下用户意图是数据解读还是数据查询")
                        .prompt(prompt).appModule(AppModule.CHAT).enable(true)
                        .chatModelId(configuredModelId).build());
    }

    @Override
    public ChatIntentResp classify(ChatIntentReq req, User user) {
        // 1. 外层路由不是 askdata，直接交给 Dify 的 LLM 分支处理
        if (!isAskDataRoute(req.getRoute())) {
            return ChatIntentResp.builder().type(ChatIntentType.DIRECT_REPLY).directReply(null)
                    .reason("route=" + req.getRoute() + "，非 askdata，不进入问数/解读分支").build();
        }

        String queryText = StringUtils.defaultString(req.getQueryText()).trim();

        // 2. LLM 二次意图分类：先判断是解读还是问数
        int intentType = classifyIntent(queryText);

        // 3. 根据分类结果分别做参数校验
        if (intentType == 1) {
            return handleDataInterpretation(req, queryText);
        }
        return handleDataQuery(req, queryText, user);
    }

    private ChatIntentResp handleDataInterpretation(ChatIntentReq req, String queryText) {
        // 数据解读也依赖当前页面/报表，没选报表时要给出明确提示
        if (isReportEmpty(req)) {
            if (isDashboardPresent(req)) {
                return directReply("抱歉，仪表盘暂未开放数据分析功能，仅支持报表数据分析，推荐您前往以下报表进行数据分析：\n\n",
                        "interpretation_dashboard_not_supported");
            }
            return directReply("当前未选择报表，无法进行数据解读。请进入对应报表后，点击报表查看页右下角的ChatBI提问。\n\n",
                    "interpretation_no_report");
        }

        // 数据解读不强制要求 agentId/limit
        return ChatIntentResp.builder().type(ChatIntentType.DATA_INTERPRETATION).intentType(1)
                .chatContext(buildContext(req, null)).reason("intent=interpretation，报表参数合法")
                .build();
    }

    private ChatIntentResp handleDataQuery(ChatIntentReq req, String queryText, User user) {
        if (isReportEmpty(req)) {
            if (isDashboardPresent(req)) {
                return directReply("抱歉，仪表盘暂未开放数据分析功能，仅支持报表数据分析，推荐您前往以下报表进行数据分析：\n\n",
                        "query_dashboard_not_supported");
            }
            return directReply("当前未选择报表，无法进行问数。请进入对应报表后，点击报表查看页右下角的ChatBI提问。\n\n",
                    "query_no_report");
        }

        Integer agentId = req.getAgentId();
        Integer limit = req.getLimit();
        if (agentId == null && limit == null) {
            return directReply("当前报表尚未进行训练，无法进行问数。\n\n", "query_not_trained");
        }
        if (agentId == null && limit != null) {
            return directReply("当前用户暂无权限查看报表数据，请联系报表管理人员开通权限。", "query_no_permission");
        }

        Long chatId = prepareChatId(req, user, agentId);
        return ChatIntentResp.builder().type(ChatIntentType.DATA_QUERY).intentType(0)
                .chatContext(buildContext(req, chatId))
                .reason("intent=query，参数校验通过，chatId=" + chatId).build();
    }

    private Long prepareChatId(ChatIntentReq req, User user, Integer agentId) {
        String conversationId = StringUtils.defaultString(req.getConversationId());
        String lastConversationId = StringUtils.defaultString(req.getLastConversationId());
        Long lastChatId = req.getLastChatId();

        if (StringUtils.isNotBlank(conversationId) && conversationId.equals(lastConversationId)
                && lastChatId != null) {
            log.info("reuse chatId={} for conversationId={}", lastChatId, conversationId);
            return lastChatId;
        }

        String chatName = buildChatName(req);
        Long chatId = chatManageService.addChat(user, chatName, agentId);
        log.info("create new chatId={} for conversationId={}", chatId, conversationId);
        return chatId;
    }

    private String buildChatName(ChatIntentReq req) {
        String text = StringUtils.defaultString(req.getQueryText()).trim();
        if (StringUtils.isBlank(text)) {
            return "新对话";
        }
        if (text.length() <= MAX_CHAT_NAME_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_CHAT_NAME_LENGTH) + "...";
    }

    private ChatIntentContext buildContext(ChatIntentReq req, Long chatId) {
        return ChatIntentContext.builder().chatId(chatId).conversationId(req.getConversationId())
                .currentDate(LocalDate.now().format(DATE_FORMATTER)).build();
    }

    private int classifyIntent(String queryText) {
        Optional<ChatApp> appOpt = ChatAppManager.getApp(APP_KEY);
        if (!appOpt.isPresent() || !appOpt.get().isEnable()) {
            log.warn("INTENT_CLASSIFIER ChatApp not registered or disabled, default to query");
            return 0;
        }

        ChatApp chatApp = appOpt.get();
        ensureChatModelId(chatApp);

        Map<String, Object> variables = new HashMap<>();
        variables.put("queryText", queryText);
        Prompt prompt = PromptTemplate.from(chatApp.getPrompt()).apply(variables);

        ChatModelConfig modelConfig = ModelConfigHelper.getChatModelConfig(chatApp);
        ChatLanguageModel chatLanguageModel = ModelProvider.getChatModel(modelConfig);
        Response<AiMessage> response = chatLanguageModel.generate(prompt.toUserMessage());
        String answer = response.content().text();

        log.info("IntentClassifier modelReq:\n{} \nmodelResp:\n{}", prompt.text(), answer);

        return parseIntentType(answer);
    }

    private void ensureChatModelId(ChatApp chatApp) {
        if (chatApp.getChatModelId() != null) {
            return;
        }
        Integer modelId = configuredModelId;
        if (modelId == null && chatModelService != null) {
            List<ChatModel> models = chatModelService.getChatModels(User.getDefaultUser());
            if (!CollectionUtils.isEmpty(models)) {
                modelId = models.get(0).getId();
            }
        }
        if (modelId != null) {
            chatApp.setChatModelId(modelId);
        }
    }

    private int parseIntentType(String answer) {
        if (StringUtils.isBlank(answer)) {
            return 0;
        }
        try {
            Matcher matcher = TYPE_PATTERN.matcher(answer);
            if (matcher.find()) {
                int type = Integer.parseInt(matcher.group(1));
                return type == 1 ? 1 : 0;
            }
            JSONObject json = JSONObject.parseObject(answer.trim());
            if (json != null && json.containsKey("type")) {
                return json.getInteger("type") == null ? 0 : (json.getInteger("type") == 1 ? 1 : 0);
            }
        } catch (Exception e) {
            log.warn("failed to parse intent answer: {}, default to 0", answer, e);
        }
        return 0;
    }

    private boolean isAskDataRoute(String route) {
        // route 为空时默认按 askdata 处理，避免 Dify 漏传时直接走 LLM 分支
        return StringUtils.isBlank(route)
                || "askdata".equalsIgnoreCase(StringUtils.defaultString(route));
    }

    private boolean isReportEmpty(ChatIntentReq req) {
        return StringUtils.isBlank(req.getReportId()) && StringUtils.isBlank(req.getReportName());
    }

    private boolean isDashboardPresent(ChatIntentReq req) {
        return StringUtils.isNotBlank(req.getDashboardId())
                || StringUtils.isNotBlank(req.getDashboardName());
    }

    private ChatIntentResp directReply(String text, String reason) {
        return ChatIntentResp.builder().type(ChatIntentType.DIRECT_REPLY).directReply(text)
                .reason(reason).build();
    }
}
