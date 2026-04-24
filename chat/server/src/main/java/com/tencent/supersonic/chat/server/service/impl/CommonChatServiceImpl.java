package com.tencent.supersonic.chat.server.service.impl;

import com.tencent.supersonic.chat.api.pojo.request.CommonChatReq;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.service.AgentService;
import com.tencent.supersonic.chat.server.service.CommonChatService;
import com.tencent.supersonic.common.config.EmbeddingConfig;
import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.common.pojo.enums.DictWordType;
import com.tencent.supersonic.common.pojo.enums.TypeEnums;
import com.tencent.supersonic.common.util.StringUtil;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import javax.annotation.Resource;
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
            
            当前任务类型：%s
            任务描述：%s
            where条件：%s
            字数必须大于20个字，但是不能超过25个字
            注意：理由要简洁明了，避免使用专业术语，类型要求如下：
                 取数申请理由：用户提供的任务描述为sql执行语句，需要将sql语句提炼一下生成理由，where条件不需要参考
                 报表申请理由：用户提供的任务描述(报表名称+报表描述)为报表生成需求，where条件是查询数据的条件描述，需要将需求提炼一下生成理由
            """;


    private static final String TYPE_REPORT = "报表申请理由";
    private static final String TYPE_DATA = "取数申请理由";


//    private final WebClient webClient;
//    private final ObjectMapper objectMapper;
//    private final CrabConfig crabConfig;
//    private final ConcurrentHashMap<String, Disposable> activeSubscriptions = new ConcurrentHashMap<>();
//
//    @Autowired
//    public CommonChatServiceImpl(WebClient.Builder webClientBuilder, ObjectMapper objectMapper,
//                               CrabConfig crabConfig, ChatQueryServiceImpl chatQueryService,
//                               ChatManageService chatManageService) {
//        this.objectMapper = objectMapper;
//        this.crabConfig = crabConfig;
//        this.webClient = webClientBuilder.baseUrl(crabConfig.getHost()).build();
//    }


    @Override
    public Flux<String> streamChat(CommonChatReq input) {
        // 1. 构建提示词
        String typeName = input.getType() == 1 ? TYPE_REPORT : TYPE_DATA;
        String whereClause = input.getWhere() != null ? input.getWhere() : "无";
        String prompt = String.format(REPORT_USER_PROMPT, typeName, input.getDescription(), whereClause);

        log.info("生成申请理由的prompt: {}", prompt);
        // 2. 获取流式模型
        StreamingChatLanguageModel streamChatModel;


        try {
//            "2273"
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

        GenerateApplyReasonStreamExtractor generateApplyReasonStreamExtractor = AiServices.create(GenerateApplyReasonStreamExtractor.class, streamChatModel);

        Flux<String> flux = generateApplyReasonStreamExtractor.generateApplyReasonStream(prompt);

        // 记录流式响应日志
        StringBuilder fullResponse = new StringBuilder();
        return flux.doOnNext(fullResponse::append).doOnComplete(() -> {
            log.info("[SSE-COMPLETE] Full response: {}", fullResponse);
        }).doOnError(error -> {
            log.error("[SSE-ERROR] Error occurred: {}", error.getMessage(), error);
        });
    }


    @Override
    public String normalChat(String whereSql) {
        // 1. 构建提示词
        String typeName = TYPE_DATA;
        String prompt = String.format(REPORT_USER_PROMPT, typeName, whereSql, "无");

        log.info("生成申请理由的prompt: {}", prompt);
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
    public List<Retrieval> retrieveQuery(String query,String modelId) {
        String collectionName = embeddingConfig.getMetaCollectionName();
        EmbeddingStore<TextSegment> embeddingStore = EmbeddingStoreFactoryProvider.getFactory().create(collectionName);
        EmbeddingModel embeddingModel = ModelProvider.getEmbeddingModel();
        Map<String, Object> filterCondition = new LinkedHashMap<>();
        Embedding embeddedText = embeddingModel.embed(query).content();
        filterCondition.put("type", Arrays.asList(TypeEnums.VALUE.name(), TypeEnums.DIMENSION_VALUE_ALIAS.name()));
        filterCondition.put("modelId", modelId + DictWordType.NATURE_SPILT);
        Filter filter = createCombinedFilter(filterCondition);
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder().queryEmbedding(embeddedText).filter(filter).build();
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);
        return result.matches().stream().map(this::convertToRetrieval)
                .sorted(Comparator.comparingDouble(Retrieval::getSimilarity).reversed())
                .collect(Collectors.toList());
    }


    @Override
    public List<Retrieval> findQuery(String query,String modelId, String dimId) {
        String collectionName = embeddingConfig.getMetaCollectionName();
        EmbeddingStore<TextSegment> embeddingStore = EmbeddingStoreFactoryProvider.getFactory().create(collectionName);
        EmbeddingModel embeddingModel = ModelProvider.getEmbeddingModel();
        Map<String, Object> filterCondition = new LinkedHashMap<>();
        Embedding embeddedText = embeddingModel.embed(query).content();
        filterCondition.put("type", Arrays.asList(TypeEnums.VALUE.name(), TypeEnums.DIMENSION_VALUE_ALIAS.name()));
        filterCondition.put("dimValue", query);
        filterCondition.put("modelId", modelId + DictWordType.NATURE_SPILT);
        if (Objects.nonNull(dimId)) {
            filterCondition.put("dimId", Long.parseLong(dimId));
        }

        Filter filter = createCombinedFilter(filterCondition);
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder().queryEmbedding(embeddedText).filter(filter).build();
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);
        return result.matches().stream().map(this::convertToRetrieval)
                .sorted(Comparator.comparingDouble(Retrieval::getSimilarity).reversed())
                .collect(Collectors.toList());
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

    private Retrieval convertToRetrieval(EmbeddingMatch<TextSegment> embeddingMatch) {
        Retrieval retrieval = new Retrieval();
        TextSegment embedded = embeddingMatch.embedded();
        retrieval.setSimilarity(embeddingMatch.score());
        retrieval.setId(TextSegmentConvert.getQueryId(embedded));
        retrieval.setQuery(embedded.text());

        Map<String, Object> metadata = new HashMap<>();
        if (Objects.nonNull(embedded) && MapUtils.isNotEmpty(embedded.metadata().toMap())) {
            metadata.putAll(embedded.metadata().toMap());
        }
        retrieval.setMetadata(metadata);
        return retrieval;
    }
}
