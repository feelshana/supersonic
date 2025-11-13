package com.tencent.supersonic.headless.chat.mapper;

import com.alibaba.fastjson.JSON;
import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SemanticSchema;
import com.tencent.supersonic.headless.api.pojo.request.QueryNLReq;
import com.tencent.supersonic.headless.api.pojo.response.S2Term;
import com.tencent.supersonic.headless.chat.ChatQueryContext;
import com.tencent.supersonic.headless.chat.knowledge.MapResult;
import com.tencent.supersonic.headless.chat.parser.ParserConfig;
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

import static com.tencent.supersonic.headless.chat.mapper.MapperConfig.EMBEDDING_MAPPER_USE_LLM;
import static com.tencent.supersonic.headless.chat.mapper.MapperConfig.EMBEDDING_MATCH_USE_LLM_WORDS_SEGMENT;
import static com.tencent.supersonic.headless.chat.parser.ParserConfig.PARSER_FORMAT_JSON_TYPE;

@Service
@Slf4j
public abstract class BatchMatchStrategy<T extends MapResult> extends BaseMatchStrategy<T> {

    public static final String LLM_WORDS_SEGMENT_PROMPT = "任务描述：\n"
            + "你是一个基于数据知识，进行用户意图识别的系统，理解用户的自然语言，结合数据的业务知识(维度/指标/业务含义)，形成数据查询任务，输出两部分结果：1.需要查询的维度与指标，2.维度的具体取值，请按照规定的工作步骤进行，并以JSON格式返回结果\n"
            + "## 业务知识\n" + "- 维度列表\n" + "- 指标列表\n" + "- 业务含义\n" + "## 用户问题：需要分词的自然语言查询\n"
            + "## 工作步骤\n"
            + "-第一步：根据业务知识中的维度列表+指标列表+业务含义，理解用户问题，将用户的问题翻译为维度和指标的查询，作为输出的第一部分。请注意，第一部分中的内容必须来源于维度列表与指标列表\n"
            + "-第二步：用户问题通过第一步提取后，剩余的词汇排除掉日期词汇，再排除掉排序和描述性词汇，只保留维度的取值作为输出的第二部分\n" + "## 输出格式要求\n"
            + "-请严格按照JSON格式输出，格式为：{\"metaPart\":\"第一部分内容\",\"valuePart\":\"第二部分内容\"}\n"
            + "-每个部分的多个词语之间，用英文逗号分隔\n" + "-如果某个部分没有相关内容，则对应字段为空字符串\n"
            + "-直接返回JSON格式的结果，不要做任何说明或其他文本\n" + "-对于20250913,0824这种类似日期格式的词汇，视为日期维度的取值，不应放入第二部分\n"
            + "## 示例\n" + "- 用户问题:国色芳华最近一周的播放次数是多少？\n" + "-维度列表：[剧集名称,日期]\n" + "-指标列表：[播放次数,播放人数]\n"
            + "{\"metaPart\":\"日期,播放次数\",\"valuePart\":\"国色芳华\"}\n" + "- 用户问题:8月5日四川小屏场景的活跃用户数\n"
            + "-维度列表：[省份名称,日期,一级场景分类,二级场景分类,三级场景分类,产品名称]\n" + "-指标列表：[活跃用户数,付费用户数]\n"
            + "{\"metaPart\":\"日期,活跃用户数\",\"valuePart\":\"四川,小屏\"}\n"
            + "-用户问题:咪咕音乐20251011活跃用户排行前十的省份\n" + "-维度列表：[省份名称,日期,一级分类,产品名称]\n"
            + "-指标列表：[活跃用户数,付费用户数]\n" + "{\"metaPart\":\"日期,省份名称,活跃用户数\",\"valuePart\":\"咪咕音乐\"}\n"
            + "-用户问题:销量排行前十的城市\n" + "-维度列表：[省份名称,日期,一级分类,产品名称]\n" + "-指标列表：[活跃用户数,付费用户数,订单数]\n"
            + "{\"metaPart\":\"\",\"valuePart\":\"订单数\"}\n" + "-用户问题:你能查什么数据\n"
            + "-维度列表：[省份名称,日期,一级分类,产品名称]\n" + "-指标列表：[活跃用户数,付费用户数,订单数]\n"
            + "{\"metaPart\":\"\",\"valuePart\":\"\"}\n" + "## 当前任务\n"
            + "请处理以下用户问题，并严格按照JSON格式返回结果：\n" + "输入问题为:{{text}}\n" + "-维度列表：{{dimensionNames}}\n"
            + "-指标列表：{{metricNames}}\n" + "-业务含义：{{termInfo}}\n";



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
        ChatApp chatApp = chatQueryContext.getRequest().getChatAppConfig()
                .get(OnePassSCSqlGenStrategy.APP_KEY);

        Prompt prompt = PromptTemplate.from(LLM_WORDS_SEGMENT_PROMPT).apply(variable);
        ChatModelConfig chatModelConfig = chatApp.getChatModelConfig();

        chatModelConfig.setJsonFormat(true);
        chatModelConfig.setJsonFormatType("json_object");

        ChatLanguageModel chatLanguageModel = ModelProvider.getChatModel(chatModelConfig);
        String response = chatLanguageModel.generate(prompt.toUserMessage().singleText());
        if (StringUtils.isNotBlank(response)) {
            log.info("大模型分词返回:{}", response);

            try {
                // 解析JSON格式的响应
                Map<String, String> resultMap = JSON.parseObject(response, Map.class);
                String metaPart = resultMap.getOrDefault("metaPart", "");
                String valuePart = resultMap.getOrDefault("valuePart", "");

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
            } catch (Exception e) {
                log.error("解析大模型分词结果失败，响应内容: {}", response, e);
            }
        }
    }

    public abstract List<T> detectByBatch(ChatQueryContext chatQueryContext,
            Set<Long> detectDataSetIds, Set<String> detectSegments);
}
