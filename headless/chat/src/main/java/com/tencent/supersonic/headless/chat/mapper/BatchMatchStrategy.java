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

@Service
@Slf4j
public abstract class BatchMatchStrategy<T extends MapResult> extends BaseMatchStrategy<T> {

    public static final String LLM_WORDS_SEGMENT_PROMPT = "任务描述：\n"
            + "你是一个专业的数据查询问题分词系统，结合数据的业务知识(维度/指标/业务含义)，将用户关于数据指标查询的自然语言进行准确分割,输出两部分分词结果，请按照规定的工作步骤进行\n"
            + "## 业务知识\n" + "- 维度列表\n" + "- 指标列表\n" + "- 业务含义\n" + "## 用户问题：需要分词的自然语言查询\n"
            + "## 工作步骤\n" + "-第一步：根据知识中的维度列表+指标列表+业务含义，理解用户问题，提取用户问题中提及到的维度/指标，作为输出的第一部分\n"
            + "-第二部：用户问题通过第一步提取后，剩余的词汇排除掉日期词汇，再排除掉排序和描述性词汇，只保留维度的取值作为输出的第二部分\n" + "## 输出内容要求\n"
            + "-确保输出格式严格遵循：第一部分内容&第二部分内容\n" + "-确保输出格式严格遵循：每个部分的多个词语之间，用英文逗号分隔\n"
            + "-如果某个部分没有相关内容，保留空位,任然保留&符号不变\n" + "-如果问题与业务知识所涉及的维度和指标无关，那么两部分内容都保留空位，用&符号分割\n"
            + "-直接返回分词后的结果内容，不要做任何说明，输出的结果严格参考如下的示例\n" + "## 示例\n" + "- 用户问题:国色芳华最近一周的播放次数是多少？\n"
            + "-维度列表：[剧集名称,日期]\n" + "-指标列表：[播放次数,播放人数]\n" + "日期,播放次数&国色芳华\n"
            + "- 用户问题:8月5日四川小屏的活跃用户数\n" + "-维度列表：[省份名称,日期,一级分类,产品名称]\n" + "-指标列表：[活跃用户数,付费用户数]\n"
            + "-日期,活跃用户数&四川,小屏\n" + "-用户问题:咪咕音乐昨日活跃用户排行前十的省份\n" + "-维度列表：[省份名称,日期,一级分类,产品名称]\n"
            + "-指标列表：[活跃用户数,付费用户数]\n" + "-省份名称,活跃用户数&咪咕音乐\n" + "-用户问题:销量排行前十的城市\n"
            + "-维度列表：[省份名称,日期,一级分类,产品名称]\n" + "-指标列表：[活跃用户数,付费用户数,订单数]\n" + "-&订单数\n"
            + "-用户问题:你能查什么数据\n" + "-维度列表：[省份名称,日期,一级分类,产品名称]\n" + "-指标列表：[活跃用户数,付费用户数,订单数]\n"
            + "-&\n" + "## 当前任务\n" + "请处理以下用户问题：\n" + "输入问题为:{{text}}\n"
            + "-维度列表：{{dimensionNames}}\n" + "- 指标列表：{{metricNames}}\n" + "- 术语列表：{{termInfo}}\n";


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
        ChatLanguageModel chatLanguageModel = ModelProvider.getChatModel(chatModelConfig);
        String response = chatLanguageModel.generate(prompt.toUserMessage().singleText());
        if (StringUtils.isNotBlank(response)) {
            log.info("大模型分词返回:{}", response);
            String[] parts = response.split("&");
            String metaPart = parts.length >= 1 ? parts[0].trim() : "";
            String wordsPart = parts.length == 2 ? parts[1].trim() : "";
            log.info("维度/指标分词结果:{}", metaPart);
            log.info("维度值分词结果:{}", wordsPart);

            if (StringUtils.isNotBlank(metaPart)
                    && CollectionUtils.isNotEmpty(Arrays.asList(metaPart.split(",")))) {
                String[] metricsAndDims = metaPart.split(",");

                if (metricsAndDims.length > 0) {
                    List<String> metricsAndDimsList = List.of(metricsAndDims);
                    // 可以在这里添加对metricsAndDims的处理逻辑
                    chatQueryContext.setQueryFilters(metricsAndDimsList);
                    // 提取维度的字段名存入chatQueryContext中
                    List<String> dimensionBizNames = semanticSchema.getDimensions().stream()
                            .filter(dimension -> metricsAndDimsList.contains(dimension.getName()))
                            .map(SchemaElement::getBizName).toList();
                    chatQueryContext.setSegmentDimBizNames(dimensionBizNames);
                }
            }
            if (StringUtils.isNotBlank(wordsPart)
                    && CollectionUtils.isNotEmpty(Arrays.asList(wordsPart.split(",")))) {
                List<String> wordsArray = Arrays.asList(wordsPart.split(","));
                wordsArray = wordsArray.stream().filter(word -> !word.isEmpty()
                        && !"全国".equals(word) && !"全省".equals(word) && !"全部".equals(word)).toList();
                detectSegments.addAll(wordsArray);

            }

        }
    }

    public abstract List<T> detectByBatch(ChatQueryContext chatQueryContext,
            Set<Long> detectDataSetIds, Set<String> detectSegments);
}
