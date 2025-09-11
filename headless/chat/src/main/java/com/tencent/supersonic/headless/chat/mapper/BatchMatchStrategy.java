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
            + "你是一个专业的数据查询分词系统，负责将用户关于数据指标查询的自然语言问题准确分割。\n" + "## 输入内容\n" + "- 用户问题：需要分词的自然语言查询\n"
            + "- 维度列表：{{dimensionNames}}\n" + "- 指标列表：{{metricNames}}\n" + "- 术语映射：{{termInfo}}\n"
            + "## 输出要求\n" + "请严格按照以下两部分输出，用分号分隔，不要输出任何其他内容：\n" + "第一部分：仅包含维度值信息（排除日期和维度指标列表中的内容）\n"
            + "- 从用户问题中提取不属于已知维度、指标的词汇\n" + "- 多个词语用英文逗号分隔\n" + "- 如果没有相关内容，保留空位\n" + "\n"
            + "第二部分：仅包含用户问题中涉及的已知维度和指标\n" + "- 必须严格匹配维度列表和指标列表中的项目\n" + "- 多个项目用英文逗号分隔\n"
            + "- 如果没有相关内容，保留空位\n" + "\n" + "## 处理规则\n"
            + "1. 日期相关词汇（如\"6月\"、\"最近一周\"、\"8月5日\"）不放入第一部分\n" + "2. 完全匹配维度列表和指标列表的词汇不放入第一部分\n"
            + "3. 术语映射表中的内容应按映射后的含义处理\n" + "4. 确保输出格式严格遵循：第一部分内容;第二部分内容\n" + "\n" + "## 示例\n"
            + "国色芳华最近一周的播放次数是多少？\n" + "国色芳华;日期,播放次数\n" + "\n" + "8月5日四川小屏的活跃用户数\n"
            + "四川,小屏;日期,活跃用户数\n" + "\n" + "6月咪咕音乐极速版的活跃用户数\n" + "咪咕音乐极速版;日期,活跃用户数\n" + "\n"
            + "8月28日球队通的在订用户数\n" + "球队通;日期,在订用户数\n" + "\n" + "## 当前任务\n" + "请处理以下用户问题：\n"
            + "输入问题为:{{text}}";


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
            // List<String> words = Arrays.stream(response.split(",")).toList();
            // log.info("使用大模型分词后的结果为: {}", JSON.toJSONString(words));
            // detectSegments.addAll(words);
            String[] parts = response.split(";");
            List<String> words = Arrays.stream(parts[0].split(",")).toList();
            log.info("使用大模型分词后的结果为: {}", JSON.toJSONString(parts));
            if (parts.length == 2) {
                List<String> metricsAndDims = Arrays.stream(parts[1].split(",")).toList();
                detectSegments.addAll(words);
                // 可以在这里添加对metricsAndDims的处理逻辑
                chatQueryContext.setQueryFilters(metricsAndDims);
            } else if (parts.length == 1) {
                detectSegments.addAll(words);
            }
        }
    }

    public abstract List<T> detectByBatch(ChatQueryContext chatQueryContext,
            Set<Long> detectDataSetIds, Set<String> detectSegments);
}
