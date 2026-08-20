package com.tencent.supersonic.chat.server.parser.llmnative;

import com.alibaba.fastjson.JSON;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.headless.server.utils.ModelConfigHelper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.provider.ModelProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.tencent.supersonic.headless.chat.parser.llm.OnePassSCSqlGenStrategy.APP_KEY;

/**
 * LLM_NATIVE 模式独立的分词服务。
 *
 * <p>
 * 职责：在 SQL 生成之前，用一次**独立**的 LLM 调用判断"用户问题属于分布/枚举/排名/排行/TopN 类，涉及哪些维度"，
 * 产出的维度列表作为默认值注入"该维度排除默认汇总行（!=）"的权威信号， 语义等价原链路 MAPPING 阶段 {@code BatchMatchStrategy.useLLMSplit()}
 * 产出的 {@code excludeDefaultDims}。
 *
 * <p>
 * 为什么独立成一次调用、而不是让 SQL 生成的 LLM 顺带输出：SQL 生成是整个链路最关键的环节，必须职责单一—— 只做一件事：根据 Schema 生成正确
 * SQL。分词（意图理解）是另一件事，混在一起会让输出格式复杂化、两头都做不稳。
 *
 * <p>
 * 为什么放在本包独立实现、不复用 headless-chat 的 {@code BatchMatchStrategy}：本模式与正常模式是两条互不影响的链路，
 * 提示词与逻辑各自维护、独立演进，避免共享代码改动互相波及。 提示词文本与 {@code BatchMatchStrategy.LLM_WORDS_SEGMENT_PROMPT}
 * 保持一致（完整复制）， 分词行为才能与正常模式对齐。
 *
 * <p>
 * 只消费 excludeDefaultDims：metaPart / valuePart 是原链路给向量召回和词典匹配用的， 本模式没有这些环节，故不处理。
 */
@Slf4j
public class LlmNativeSegmentService {

    /**
     * 分词提示词，与 {@code BatchMatchStrategy.LLM_WORDS_SEGMENT_PROMPT} 保持一致，请勿单独修改， 如需调整分词行为两边要同步改。
     */
    private static final String LLM_WORDS_SEGMENT_PROMPT = "任务描述：\n"
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

    private LlmNativeSegmentService() {}

    /**
     * 执行分词，把识别出的分布维度（bizName，已归一化）写入 {@code context.excludeDefaultDimBizNames}。
     * 失败只记日志不抛异常——信号为空时默认值注入退化为仅靠 GROUP BY，不阻断主流程。
     */
    public static void segment(String queryText, LlmNativeContext context, Agent agent) {
        if (StringUtils.isBlank(queryText)) {
            return;
        }
        try {
            ChatApp chatApp = agent != null && agent.getChatAppConfig() != null
                    ? agent.getChatAppConfig().get(APP_KEY)
                    : null;
            if (chatApp == null) {
                log.warn("[LLM_NATIVE] Agent 未配置 {} ChatApp，跳过分词", APP_KEY);
                return;
            }
            ChatModelConfig modelConfig = ModelConfigHelper.getChatModelConfig(chatApp);
            if (modelConfig == null) {
                log.warn("[LLM_NATIVE] ChatApp 模型配置为空，跳过分词");
                return;
            }

            Prompt prompt = PromptTemplate.from(LLM_WORDS_SEGMENT_PROMPT)
                    .apply(buildVariables(queryText, context));

            // jsonFormat 会写回 ChatApp 内的共享配置对象，调用完必须复位，避免污染后续 SQL 生成调用
            modelConfig.setJsonFormat(true);
            modelConfig.setJsonFormatType("json_object");
            try {
                ChatLanguageModel llm = ModelProvider.getChatModel(modelConfig);
                long start = System.currentTimeMillis();
                String response = llm.generate(prompt.toUserMessage().singleText());
                log.info("[PERFORMANCE] LLM_NATIVE 分词耗时: {}ms, 返回: {}",
                        System.currentTimeMillis() - start, response);
                parseAndApply(response, context);
            } finally {
                modelConfig.setJsonFormat(false);
                modelConfig.setJsonFormatType(null);
            }
        } catch (Exception e) {
            log.warn("[LLM_NATIVE] 分词失败，按无分布维度处理", e);
        }
    }

    /** 构建提示词变量，对齐原 useLLMSplit 的 variable 组装。 */
    private static Map<String, Object> buildVariables(String queryText, LlmNativeContext context) {
        Map<String, Object> variable = new HashMap<>();
        variable.put("text", queryText);
        variable.put("dimensionNames", context.getDimensions().stream()
                .map(LlmNativeContext.DimMeta::getName).collect(Collectors.toList()));
        variable.put("metricNames", context.getMetricNames());
        variable.put("termInfo",
                context.getTermInfoMap().isEmpty() ? "" : context.getTermInfoMap());
        // 带默认值的维度及其默认值，作为 LLM 判断 excludeDefaultDims 的上下文
        Map<String, List<String>> dimensionDefaultValues = context.getDimensions().stream()
                .filter(d -> StringUtils.isNotBlank(d.getName()) && d.getDefaultValues() != null
                        && !d.getDefaultValues().isEmpty())
                .collect(Collectors.toMap(LlmNativeContext.DimMeta::getName,
                        LlmNativeContext.DimMeta::getDefaultValues, (v1, v2) -> v1,
                        LinkedHashMap::new));
        variable.put("dimensionDefaultValues", dimensionDefaultValues);
        return variable;
    }

    /** 解析分词返回的 JSON，提取 excludeDefaultDims 并映射为 bizName 存入 context。 */
    @SuppressWarnings("unchecked")
    private static void parseAndApply(String response, LlmNativeContext context) {
        if (StringUtils.isBlank(response)) {
            return;
        }
        Map<String, String> resultMap;
        try {
            resultMap = JSON.parseObject(response, Map.class);
        } catch (Exception e) {
            log.warn("[LLM_NATIVE] 分词返回不是合法 JSON，忽略: {}", response);
            return;
        }
        String excludeDefaultDims = resultMap.getOrDefault("excludeDefaultDims", "");
        if (StringUtils.isBlank(excludeDefaultDims)) {
            log.info("[LLM_NATIVE] 分词未识别出分布维度");
            return;
        }
        List<String> excludeDims = Arrays.stream(excludeDefaultDims.split(",")).map(String::trim)
                .filter(StringUtils::isNotBlank).distinct().collect(Collectors.toList());
        for (String dimName : excludeDims) {
            String bizName = context.getDimensions().stream()
                    .filter(d -> StringUtils.equalsIgnoreCase(d.getName(), dimName)
                            || StringUtils.equalsIgnoreCase(d.getBizName(), dimName))
                    .map(LlmNativeContext.DimMeta::getBizName).findFirst().orElse(null);
            if (StringUtils.isBlank(bizName)) {
                log.warn("[LLM_NATIVE] 分词维度 [{}] 未匹配到维度，忽略", dimName);
                continue;
            }
            context.getExcludeDefaultDimBizNames()
                    .add(LlmNativeSchemaBuilder.normalizeField(bizName));
        }
        log.info("[LLM_NATIVE] 分词识别的排除默认值维度(bizName): {}", context.getExcludeDefaultDimBizNames());
    }
}
