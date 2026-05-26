package com.tencent.supersonic.chat.server.service.impl;

import javax.annotation.Resource;

import com.amazonaws.services.dynamodbv2.xspec.S;
import com.tencent.supersonic.chat.api.pojo.request.CommonChatReq;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.service.AgentService;
import com.tencent.supersonic.chat.server.service.CommonChatService;
import com.tencent.supersonic.common.config.EmbeddingConfig;
import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.common.pojo.enums.DictWordType;
import com.tencent.supersonic.common.pojo.enums.TypeEnums;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.provider.ModelProvider;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.store.embedding.*;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.MapUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 通用对话服务实现
 */
@Slf4j
@Service
public class CommonChatServiceImpl implements CommonChatService {

    @Value("${s2.bi.agent-id:1}")
    private Integer agentId;


    @Resource
    private AgentService agentService;

    public static final String APP_KEY = "S2SQL_PARSER";

    /**
     * 系统提示词模板：根据 type 和 description 生成对应内容
     */
    private static final String REPORT_USER_PROMPT = """

             你是一名专业的企业数据申请助手，需要根据用户输入内容，生成规范、专业、易审批的“申请理由”。

             【输入信息】
             当前任务类型：%s
             任务描述：%s
             where条件：%s
             上一次生成理由：%s

             【核心目标】
             请结合任务信息，生成一段真实、准确、简洁的申请理由，用于企业内部审批场景。

             【通用输出要求】
             1. 理由必须与任务描述、业务用途、查询条件强相关。
             2. 表达需自然、专业、清晰，符合企业内部申请语境。
             3. 禁止出现SQL、字段、表、数据库、脚本等技术术语。
             4. 禁止生成空泛内容，例如：
                - 用于业务分析
                - 用于数据查看
                - 用于日常使用
             5. 字数强制要求（必须严格执行）：
                - 最终输出的理由字数必须在20字到25字之间。
                - 如果生成后自查发现不足20字，必须立即补充业务目的或数据类型描述，直至达标。
                - 禁止为了凑字数而添加无意义的虚词或重复内容。
             6. 仅输出最终理由，不输出解释、分析过程或其它内容。

             【避免与上次重复的强制策略】
             如果“上一次生成理由”不为空，你必须严格遵循以下策略，生成一个业务含义一致但表达完全不同的新理由：
             1. 结构性变化：必须彻底改变句子的主谓宾结构或表达视角，仅替换单个动词（如将“下载”改为“获取”）将被判定为无效变化。
             2. 完备性要求：无论角度如何变化，理由必须包含“业务事项 + 数据类型”两个基本部分，不能为了简化而省略任何一方。
             3. 变化手法示例：
                - 动作转换：将具体的操作动词替换为不同范畴的动作，如“下载”变为“核对”、“整理”、“提取”、“汇总”等。
                - 视角切换：如果上次是目的导向（如“用于产品评估”），本次可改为对象导向（如“为重点产品提供数据依据”）。
                - 具体度调整：上次用词概括，本次可适当具体化；上次描述详细，本次可稍作概括，但均需保持与任务描述的关联。
             4. 典型无效案例（严禁出现）：
                - 上一次：“下载全国全场景活跃用户数据用于产品评估”
                - 无效重复：“获取全国全场景活跃用户数据用于产品评估”（仅替换下载→获取）
                - 无效重复：“用于产品评估下载全国全场景活跃用户数据”（仅调整语序）
                - 无效重复：完全照搬上一次理由，一字不改。
             5. 生成后内部自检：在输出前，必须对比“上一次生成理由”。如果新理由仅通过替换1-2个非实质性词语或调整语序得到，请立即重新构思，直至结构明显不同。

             【不同任务类型生成规则】

             一、取数申请理由
             场景说明：用户提供的“任务描述”通常为SQL执行语句。
             生成要求：
             1. 从SQL语义中提炼真实业务目的。
             2. 不参考where条件。
             3. 不允许输出任何SQL相关内容。
             4. 理由必须完整包含：查询什么业务数据，用于什么业务事项。
             5. 若需应用避免重复策略，可改变业务事项的描述角度（如“核对”变为“整理记录”，“分析”变为“确认结果”），但不得省略数据描述。

             二、报表申请理由
             场景说明：报表申请的本质是“下载数据”。
             生成要求：
             1. 理由必须完整回答两个问题：因何业务事项、下载何范围/类型的数据。
             2. 结合任务描述与where条件，明确下载的数据对象。
             3. 建议采用“为……（业务事项），下载……（数据描述）”的框架，自然满足字数要求。
             4. 若需应用避免重复策略，优先改变“业务事项”的表述方式，不得简化“数据描述”部分。

             三、订阅报表理由
             场景说明：订阅报表会将相关数据定期发送给订阅人。
             生成要求：
             1. 必须体现“持续接收”、“定期查看”、“自动发送”等订阅场景。
             2. 结合任务描述与where条件，说明关注的数据内容。
             3. 理由必须包含：为什么需要持续关注，订阅接收什么数据。
             4. 若需应用避免重复策略，优先通过更换“持续关注”的具体原因或动作，以及“数据内容”的同义描述来实现（如“监控销售动态”对“追踪业绩变化”），确保信息完整。

            【最终要求与自检】
            输出内容必须：简洁专业、贴近真实业务、易于审批人员理解、避免模板化表达。
            在输出最终理由前，必须执行以下自检，不通过的必须重新调整直至全部满足：
            1. 字数检查：理由字数是否在20-25字之间？若不足或超出，立即修正。
            2. 完整性检查：理由是否同时包含了“业务事项”和“数据类型”两个部分？若缺少任何一个，立即补充。
            3. 差异度检查（最关键）：
               - 若“上一次生成理由”不为空，请逐字对比。新理由绝不能与上一次理由完全相同。
               - 如果发现完全一致，或者仅仅改变了标点符号、空格位置，必须立刻推翻，改用全新的动作或不同的业务视角重新生成。
             """;


    private static final String TYPE_REPORT = "报表申请理由";
    private static final String TYPE_DATA = "取数申请理由";
    private static final String TYPE_SUBSCRIBE = "订阅报表申请理由";


    // private final WebClient webClient;
    // private final ObjectMapper objectMapper;
    // private final CrabConfig crabConfig;
    // private final ConcurrentHashMap<String, Disposable> activeSubscriptions = new
    // ConcurrentHashMap<>();
    //
    // @Autowired
    // public CommonChatServiceImpl(WebClient.Builder webClientBuilder, ObjectMapper objectMapper,
    // CrabConfig crabConfig, ChatQueryServiceImpl chatQueryService,
    // ChatManageService chatManageService) {
    // this.objectMapper = objectMapper;
    // this.crabConfig = crabConfig;
    // this.webClient = webClientBuilder.baseUrl(crabConfig.getHost()).build();
    // }


    @Override
    public Flux<String> streamChat(CommonChatReq input) {
        // 1. 构建提示词
        String prompt = getPrompt(input);

        // log.info("生成申请理由的prompt: {}", prompt);
        // 2. 获取流式模型
        StreamingChatLanguageModel streamChatModel;


        try {
            // "2273"
            Agent agent = agentService.getAgent(agentId);
            Map<String, ChatApp> chatAppConfig = agent.getChatAppConfig();
            ChatApp chatApp = chatAppConfig.get(APP_KEY);
            ChatModelConfig chatModelConfig = chatApp.getChatModelConfig();
            streamChatModel = ModelProvider.getStreamingChatModel(chatModelConfig);
        } catch (Exception e) {
            log.error("未正确获助手", e);
            throw new RuntimeException("未正确获助手，无法使用自动生成申请理由");
        }

        // 3. 创建流式解析器

        GenerateApplyReasonStreamExtractor generateApplyReasonStreamExtractor =
                AiServices.create(GenerateApplyReasonStreamExtractor.class, streamChatModel);

        Flux<String> flux = generateApplyReasonStreamExtractor.generateApplyReasonStream(prompt);

        // 记录流式响应日志，针对 API 返回的 created 字段溢出异常做特殊处理
        StringBuilder fullResponse = new StringBuilder();
        return flux.doOnNext(fullResponse::append).doOnComplete(() -> {
            log.info("[SSE-COMPLETE] Full response: {}", fullResponse);
        }).onErrorResume(error -> {
            // 检测是否为 openai4j 的 created 字段 int 溢出异常
            String errorMsg = error.getMessage();
            if (errorMsg != null && errorMsg.contains("out of range of int")
                    && errorMsg.contains("ChatCompletionResponse")) {
                // 该异常不影响业务功能，仅记录日志作为备忘
                log.info("[SSE-INFO] AI模型API返回的created字段数值溢出(int范围)，不影响功能，已记录: {}", errorMsg);
                return Mono.empty();
            }
            // 其他异常继续抛出
            return Mono.error(error);
        });
    }

    @NotNull
    private static String getPrompt(CommonChatReq input) {
        String typeName = "";
        switch (input.getType()) {
            case 1:
                typeName = TYPE_REPORT;
                break;
            case 2:
                typeName = TYPE_DATA;
                break;
            case 3:
                typeName = TYPE_SUBSCRIBE;
                break;
            default:
                break;
        }
        String whereClause = input.getWhere() != null ? input.getWhere() : "无";
        return String.format(REPORT_USER_PROMPT, typeName, input.getDescription(), whereClause,
                input.getLastReason());
    }


    @Override
    public String normalChat(String whereSql) {
        // 1. 构建提示词
        String prompt = getPrompt(new CommonChatReq(2, whereSql, "无", null));

        // log.info("生成申请理由的prompt: {}", prompt);
        ChatLanguageModel chatLanguageModel;
        try {
            Agent agent = agentService.getAgent(agentId);
            Map<String, ChatApp> chatAppConfig = agent.getChatAppConfig();
            ChatApp chatApp = chatAppConfig.get(APP_KEY);
            ChatModelConfig chatModelConfig = chatApp.getChatModelConfig();
            chatLanguageModel = ModelProvider.getChatModel(chatModelConfig);
        } catch (Exception e) {
            log.error("未正确获助手", e);
            throw new RuntimeException("未正确获助手，无法使用自动生成申请理由");
        }
        return chatLanguageModel.generate(prompt);
    }

    public interface GenerateApplyReasonStreamExtractor {
        /**
         * 生成申请理由
         *
         * @param userMessage 用户消息
         * @return 生成申请理由 流式结果
         */
        @SystemMessage(value = "你是一个申请理由生成助手。理由要简洁明了，避免使用专业术语")
        Flux<String> generateApplyReasonStream(String userMessage);
    }

    @Resource
    private EmbeddingConfig embeddingConfig;

    @Override
    public List<Map<String, Object>> retrieveQuery(String query, String modelId, Integer topK) {
        String collectionName = embeddingConfig.getMetaCollectionName();
        EmbeddingStore<TextSegment> embeddingStore =
                EmbeddingStoreFactoryProvider.getFactory().create(collectionName);
        EmbeddingModel embeddingModel = ModelProvider.getEmbeddingModel();
        Map<String, Object> filterCondition = new LinkedHashMap<>();
        Embedding embeddedText = embeddingModel.embed(query).content();
        filterCondition.put("type", TypeEnums.VALUE.name());
        filterCondition.put("modelId", modelId + DictWordType.NATURE_SPILT);
        Filter filter = createCombinedFilter(filterCondition);
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddedText).maxResults(topK).filter(filter).build();
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);
        return result.matches().stream()
                .sorted(Comparator.comparingDouble((EmbeddingMatch<TextSegment> m) -> m.score())
                        .reversed())
                .map(x -> convertToRetrieval(x, true)).limit(topK).collect(Collectors.toList());
    }


    @Override
    public List<Map<String, Object>> findQuery(String query, String modelId, String dimId) {
        String collectionName = embeddingConfig.getMetaCollectionName();
        EmbeddingStore<TextSegment> embeddingStore =
                EmbeddingStoreFactoryProvider.getFactory().create(collectionName);
        EmbeddingModel embeddingModel = ModelProvider.getEmbeddingModel();
        Map<String, Object> filterCondition = new LinkedHashMap<>();
        Embedding embeddedText = embeddingModel.embed(query).content();
        filterCondition.put("type", TypeEnums.VALUE.name());
        filterCondition.put("modelId", modelId + DictWordType.NATURE_SPILT);
        if (Objects.nonNull(dimId)) {
            filterCondition.put("dimId", Long.parseLong(dimId));
        }

        Filter filter = createCombinedFilter(filterCondition);
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddedText).filter(filter).maxResults(1).build();
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);
        return result.matches().stream().filter(x -> Objects.equals(x.embedded().text(), query))
                .map(x -> convertToRetrieval(x, false)).collect(Collectors.toList());
    }

    private Filter createCombinedFilter(Map<String, Object> criteriaMap) {
        if (MapUtils.isEmpty(criteriaMap)) {
            return null;
        }
        Filter combinedFilter = null;
        for (Map.Entry<String, Object> entry : criteriaMap.entrySet()) {
            String fieldName = entry.getKey();
            Object fieldValue = entry.getValue();
            Filter fieldFilter = null;
            if (fieldValue instanceof List) {
                // Create an OR filter for each value in the list
                for (String value : (List<String>) fieldValue) {
                    IsEqualTo equalToFilter = new IsEqualTo(fieldName, value);
                    fieldFilter = (fieldFilter == null) ? equalToFilter
                            : Filter.or(fieldFilter, equalToFilter);
                }
            } else if (fieldValue instanceof String || fieldValue instanceof Number
                    || fieldValue instanceof Enum) {
                // Create a simple equality filter
                fieldFilter = new IsEqualTo(fieldName, fieldValue);
            }
            // Combine the current field filter with the overall filter using AND logic
            if (fieldFilter != null) {
                combinedFilter = (combinedFilter == null) ? fieldFilter
                        : Filter.and(combinedFilter, fieldFilter);
            }
        }
        return combinedFilter;
    }

    private Map<String, Object> convertToRetrieval(EmbeddingMatch<TextSegment> embeddingMatch,
            boolean flag) {
        Map<String, Object> retrieval = new LinkedHashMap<>();
        TextSegment embedded = embeddingMatch.embedded();
        if (MapUtils.isNotEmpty(embedded.metadata().toMap())) {
            retrieval.put("value", embedded.metadata().getString("newName"));
            retrieval.put("recallText", embedded.metadata().getString("dimValue"));
        }
        if (flag) {
            retrieval.put("similarity", embeddingMatch.score());
        }

        return retrieval;
    }
}
