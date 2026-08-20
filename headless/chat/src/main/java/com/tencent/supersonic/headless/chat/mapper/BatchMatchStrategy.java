package com.tencent.supersonic.headless.chat.mapper;

import com.alibaba.fastjson.JSON;
import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.common.pojo.TermConstants;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SemanticSchema;
import com.tencent.supersonic.headless.api.pojo.response.S2Term;
import com.tencent.supersonic.headless.chat.ChatQueryContext;
import com.tencent.supersonic.headless.chat.knowledge.MapResult;
import com.tencent.supersonic.headless.chat.parser.llm.OnePassSCSqlGenStrategy;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.provider.ModelProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static com.tencent.supersonic.headless.chat.mapper.MapperConfig.EMBEDDING_MATCH_USE_LLM_WORDS_SEGMENT;

@Service
@Slf4j
public abstract class BatchMatchStrategy<T extends MapResult> extends BaseMatchStrategy<T> {

    public static final String LLM_WORDS_SEGMENT_PROMPT = "任务描述：\n"
            + "你是一个基于数据知识进行用户意图识别的系统。你需要理解用户自然语言，结合业务知识(维度/指标/业务含义/维度扩展信息)，形成数据查询任务，并输出三部分结果：\n"
            + "1) metaPart：需要查询的维度与指标（只允许来自维度列表与指标列表）；\n"
            + "2) valuePart：维度的具体取值（只保留实体/取值，不要包含日期词汇/排序词/描述性词）；\n"
            + "3) excludeDefaultDims：如果本问题属于分布/枚举/排名/排行/TopN类问题，请输出涉及的维度名（只输出维度名，必须来自维度列表；后续代码会基于这些维度做策略处理）。\n"
            + "\n" + "## 业务知识\n" + "- 维度列表\n" + "- 指标列表\n" + "- 业务含义\n" + "\n" + "## 工作步骤\n"
            + "- 第一步：根据维度列表+指标列表+业务含义，提取本次需要查询的维度与指标，作为 metaPart（必须来自维度列表与指标列表）。\n"
            + "- 第二步：用户问题通过第一步提取后，剩余的词汇提取维度值作为 valuePart。剔除日期/时间词、排序词（如排名、前十、topN）、剔除描述性词汇，只保留实体/取值。\n"
            + "- 第三步：判断是否为分布/枚举/排名/排行/TopN类问题（例如包含：各|每|按|分|排名|排行|名次|top|前N）。\n"
            + "  - 如果是：输出本问题涉及的‘分组/枚举/排行’维度名到 excludeDefaultDims（多个用英文逗号分隔）。不要输出指标名，不要输出‘日期’这类时间维度。\n"
            + "  - 如果不是：excludeDefaultDims 为空字符串。\n"
            + "  - 注意：‘在全国的排名’中的‘全国’是范围描述，不是省份取值，不要把‘全国’放入 valuePart。\n" + "\n"
            + "## 输出格式要求（非常重要）\n"
            + "- 必须严格输出一个 JSON 对象，且必须包含 3 个 key：metaPart、valuePart、excludeDefaultDims。\n"
            + "- 3 个字段的 value 都是字符串；多个词语之间用英文逗号分隔；如果为空则返回空字符串。\n"
            + "- metaPart 中每个词必须来自维度列表或指标列表；excludeDefaultDims 中每个词必须来自维度列表（且不要输出日期/时间维度）。\n"
            + "- valuePart 不要包含：全国/全省/全部，不要包含任何日期格式取值（如 20250913、0824），不要包含‘排名/前十/topN’等排序词。\n"
            + "- 只返回 JSON，不要输出解释、不要加 markdown、不要输出多余文本。\n" + "\n" + "## 示例\n"
            + "- 用户问题: 国色芳华最近一周的播放次数是多少？\n" + "- 维度列表：[剧集名称,日期]\n" + "- 指标列表：[播放次数,播放人数]\n"
            + "{\"metaPart\":\"日期,播放次数\",\"valuePart\":\"国色芳华\",\"excludeDefaultDims\":\"\"}\n"
            + "\n" + "- 用户问题: 昨日各省的活跃用户数\n" + "- 维度列表：[省份名称,日期]\n" + "- 指标列表：[活跃用户数]\n"
            + "{\"metaPart\":\"日期,省份名称,活跃用户数\",\"valuePart\":\"\",\"excludeDefaultDims\":\"省份名称\"}\n"
            + "- 用户问题:8月5日四川小屏场景的活跃用户数\n" + "-维度列表：[省份名称,日期,一级场景分类,二级场景分类,三级场景分类,产品名称]\n"
            + "-指标列表：[活跃用户数,付费用户数]\n"
            + "{\"metaPart\":\"日期,活跃用户数\",\"valuePart\":\"四川,小屏\",\"excludeDefaultDims\":\"\"}\n"
            + "- 用户问题: 昨日四川省在订用户数在全国的排名\n" + "- 维度列表：[省份名称,日期]\n" + "- 指标列表：[在订用户数]\n"
            + "{\"metaPart\":\"日期,在订用户数\",\"valuePart\":\"四川\",\"excludeDefaultDims\":\"省份名称\"}\n"
            + "-用户问题:咪咕音乐20251011活跃用户前十的省份\n" + "-维度列表：[省份名称,日期,一级分类,产品名称]\n"
            + "-指标列表：[活跃用户数,付费用户数]\n"
            + "{\"metaPart\":\"日期,省份名称,活跃用户数\",\"valuePart\":\"咪咕音乐\",\"excludeDefaultDims\":\"省份名称\"}\n"
            + "- 用户问题: 昨日全国的活跃用户数\n" + "- 维度列表：[省份名称,日期]\n" + "- 指标列表：[活跃用户数]\n"
            + "{\"metaPart\":\"日期,活跃用户数\",\"valuePart\":\"\",\"excludeDefaultDims\":\"\"}\n" + "\n"
            + "- 用户问题: 你能查什么数据\n" + "- 维度列表：[省份名称,日期,一级分类,产品名称]\n" + "- 指标列表：[活跃用户数,付费用户数,订单数]\n"
            + "{\"metaPart\":\"\",\"valuePart\":\"\",\"excludeDefaultDims\":\"\"}\n" + "\n"
            + "## 当前任务\n" + "请处理以下用户问题，并严格按照JSON格式返回结果：\n" + "输入问题为:{{text}}\n"
            + "-维度列表：{{dimensionNames}}\n" + "-指标列表：{{metricNames}}\n" + "-业务含义：{{termInfo}}\n"
            + "-维度扩展信息：{{dimensionDefaultValues}}\n";



    @Autowired
    protected MapperConfig mapperConfig;

    @Override
    public List<T> detect(ChatQueryContext chatQueryContext, List<S2Term> terms,
            Set<Long> detectDataSetIds) {

        String text = chatQueryContext.getRequest().getQueryText();
        Set<String> detectSegments = new HashSet<>();
        boolean useLLMWordsSegment = Boolean.parseBoolean(
                mapperConfig.getParameterValue(EMBEDDING_MATCH_USE_LLM_WORDS_SEGMENT));


        if (useLLMWordsSegment) {
            useLLMSplit(detectSegments, text, chatQueryContext);
        } else {
            int embeddingTextSize = Integer.valueOf(
                    mapperConfig.getParameterValue(MapperConfig.EMBEDDING_MAPPER_TEXT_SIZE));

            int embeddingTextStep = Integer.valueOf(
                    mapperConfig.getParameterValue(MapperConfig.EMBEDDING_MAPPER_TEXT_STEP));

            for (int startIndex = 0; startIndex < text.length(); startIndex += embeddingTextStep) {
                int endIndex = Math.min(startIndex + embeddingTextSize, text.length());
                String detectSegment = text.substring(startIndex, endIndex).trim();
                detectSegments.add(detectSegment);
            }
        }
        return detectByBatch(chatQueryContext, detectDataSetIds, detectSegments);
    }

    // 通过llm进行分词
    private void useLLMSplit(Set<String> detectSegments, String text,
            ChatQueryContext chatQueryContext) {
        Map<String, Object> variable = new HashMap<>();
        variable.put("text", text);
        SemanticSchema semanticSchema = chatQueryContext.getSemanticSchema();
        // 取出所有的维度名称
        List<String> dimensionNames =
                semanticSchema.getDimensions().stream().map(SchemaElement::getName).toList();
        // 取出所有的指标名称
        List<String> metricNames =
                semanticSchema.getMetrics().stream().map(SchemaElement::getName).toList();
        variable.put("dimensionNames", dimensionNames);
        variable.put("metricNames", metricNames);
        if (semanticSchema.getTerms() != null && !semanticSchema.getTerms().isEmpty()) {
            // 取出所有的术语信息放入map集合中,key为术语名称,value为术语描述
            Map<String, String> termInfo = semanticSchema.getTerms().stream().collect(
                    Collectors.toMap(SchemaElement::getName, SchemaElement::getDescription));
            variable.put("termInfo", termInfo);
        } else {
            variable.put("termInfo", "");
        }

        // dimension -> defaultValues (optional context for LLM to decide excludeDefaultDims)
        Map<String, List<String>> dimensionDefaultValues = semanticSchema.getDimensions().stream()
                .filter(d -> d != null && StringUtils.isNotBlank(d.getName())
                        && d.getDefaultValues() != null && !d.getDefaultValues().isEmpty())
                .collect(Collectors.toMap(SchemaElement::getName, SchemaElement::getDefaultValues,
                        (v1, v2) -> v1, LinkedHashMap::new));
        variable.put("dimensionDefaultValues", dimensionDefaultValues);

        ChatApp chatApp = chatQueryContext.getRequest().getChatAppConfig()
                .get(OnePassSCSqlGenStrategy.APP_KEY);

        Prompt prompt = PromptTemplate.from(LLM_WORDS_SEGMENT_PROMPT).apply(variable);
        ChatModelConfig chatModelConfig = chatApp.getChatModelConfig();

        chatModelConfig.setJsonFormat(true);
        chatModelConfig.setJsonFormatType("json_object");

        ChatLanguageModel chatLanguageModel = ModelProvider.getChatModel(chatModelConfig);
        long llmSplitStart = System.currentTimeMillis();
        String response = chatLanguageModel.generate(prompt.toUserMessage().singleText());
        log.info("[PERFORMANCE] LLM分词耗时: {}ms", System.currentTimeMillis() - llmSplitStart);
        if (StringUtils.isNotBlank(response)) {
            log.info("用户的问题是：{}，大模型分词返回:{}", text, response);

            try {
                // 解析JSON格式的响应
                Map<String, String> resultMap = JSON.parseObject(response, Map.class);
                String metaPart = resultMap.getOrDefault("metaPart", "");
                String valuePart = resultMap.getOrDefault("valuePart", "");
                String excludeDefaultDims = resultMap.getOrDefault("excludeDefaultDims", "");


                log.info("维度/指标分词结果:{}", metaPart);
                log.info("维度值分词结果:{}", valuePart);

                if (StringUtils.isNotBlank(metaPart)
                        && CollectionUtils.isNotEmpty(Arrays.asList(metaPart.split(",")))) {
                    String[] metricsAndDims = metaPart.split(",");

                    if (metricsAndDims.length > 0) {
                        List<String> metricsAndDimsList = List.of(metricsAndDims);
                        // 过滤掉‘日期’维度
                        metricsAndDimsList = metricsAndDimsList.stream()
                                .filter(dim -> !dim.contains("日期")).collect(Collectors.toList());
                        // 可以在这里添加对metricsAndDims的处理逻辑
                        chatQueryContext.setQueryFilters(metricsAndDimsList);
                        // 提取维度的字段名存入chatQueryContext中
                        List<String> finalMetricsAndDimsList = metricsAndDimsList;
                        List<String> dimensionBizNames = semanticSchema.getDimensions().stream()
                                .filter(dimension -> finalMetricsAndDimsList
                                        .contains(dimension.getName()))
                                .map(SchemaElement::getBizName).toList();
                        chatQueryContext.setSegmentDimBizNames(dimensionBizNames);
                    }
                }
                if (StringUtils.isNotBlank(valuePart)
                        && CollectionUtils.isNotEmpty(Arrays.asList(valuePart.split(",")))) {
                    List<String> wordsArray = Arrays.asList(valuePart.split(","));
                    wordsArray =
                            wordsArray.stream().filter(word -> !word.isEmpty() && !"全国".equals(word)
                                    && !"全省".equals(word) && !"全部".equals(word)).toList();
                    detectSegments.addAll(wordsArray);
                }

                if (StringUtils.isNotBlank(excludeDefaultDims) && CollectionUtils
                        .isNotEmpty(Arrays.asList(excludeDefaultDims.split(",")))) {
                    List<String> excludeDims = Arrays.stream(excludeDefaultDims.split(","))
                            .map(String::trim).filter(StringUtils::isNotBlank).distinct().toList();

                    // Keep only dimensions that exist in schema and have defaultValues
                    Set<String> validDimNamesWithDefault =
                            new HashSet<>(dimensionDefaultValues.keySet());
                    Set<String> termConfigDimNames = getTermConfigDimNames(semanticSchema);
                    validDimNamesWithDefault.addAll(termConfigDimNames);

                    excludeDims = excludeDims.stream().filter(validDimNamesWithDefault::contains)
                            .toList();
                    chatQueryContext.setExcludeDefaultDimNames(excludeDims);
                    log.info("excludeDefaultDims from words segment: {}", excludeDims);
                }

            } catch (Exception e) {
                log.error("解析大模型分词结果失败，响应内容: {}", response, e);
            }
        }
    }

    public abstract List<T> detectByBatch(ChatQueryContext chatQueryContext,
            Set<Long> detectDataSetIds, Set<String> detectSegments);

    private static final String TERM_DEFAULT_CONFIG_NAME = TermConstants.DEFAULT_DIM_VALUE_CONFIG;

    private Set<String> getTermConfigDimNames(SemanticSchema semanticSchema) {
        List<SchemaElement> terms = semanticSchema.getTerms();
        if (CollectionUtils.isEmpty(terms)) {
            return Collections.emptySet();
        }
        SchemaElement configTerm = terms.stream()
                .filter(t -> TERM_DEFAULT_CONFIG_NAME.equals(t.getName())).findFirst().orElse(null);
        if (configTerm == null || StringUtils.isBlank(configTerm.getDescription())) {
            return Collections.emptySet();
        }
        try {
            Map<String, String> bizNameToDefault =
                    JSON.parseObject(configTerm.getDescription(), Map.class);
            if (bizNameToDefault == null || bizNameToDefault.isEmpty()) {
                return Collections.emptySet();
            }
            Set<String> bizNames = bizNameToDefault.keySet();
            return semanticSchema.getDimensions().stream()
                    .filter(d -> d != null && StringUtils.isNotBlank(d.getBizName())
                            && bizNames.contains(d.getBizName()))
                    .map(SchemaElement::getName).filter(StringUtils::isNotBlank)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("Failed to parse term '{}' description: {}", TERM_DEFAULT_CONFIG_NAME,
                    configTerm.getDescription(), e);
            return Collections.emptySet();
        }
    }
}
