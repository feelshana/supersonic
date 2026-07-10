package com.tencent.supersonic.chat.server.util;

import com.tencent.supersonic.common.util.JsonUtil;
import dev.langchain4j.model.Tokenizer;
import dev.langchain4j.model.openai.OpenAiTokenizer;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 token 预算的数据截断器。 使用 OpenAI cl100k_base tokenizer 计算每行数据的 token，并在预算内保留尽可能多的数据行。
 */
@Slf4j
public class TokenBudgetTruncator {

    private static final String TRUNC_NOTICE = "\n\n【注意：由于篇幅限制，部分信息已被截断】";

    private final Tokenizer tokenizer;

    public TokenBudgetTruncator() {
        // 使用 gpt-4 对应的 cl100k_base tokenizer，中文/英文 token 估算更接近实际模型
        this.tokenizer = new OpenAiTokenizer("gpt-4");
    }

    public TokenBudgetTruncator(Tokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    /**
     * 对二维表数据进行 token 预算截断。
     *
     * @param headerRow 表头行（不会被截断）
     * @param dataRows 数据行
     * @param maxDataTokens 数据部分最大 token 数
     * @return 截断结果
     */
    public TruncateResult truncate(List<Object> headerRow, List<List<Object>> dataRows,
            int maxDataTokens) {
        if (maxDataTokens <= 0) {
            return errorResult("maxDataTokens 必须大于 0");
        }
        if (headerRow == null || headerRow.isEmpty()) {
            return errorResult("表头为空");
        }

        String headerLine = JsonUtil.toString(headerRow);
        int headerTokens = estimateTokenCount(headerLine + "\n");
        if (headerTokens > maxDataTokens) {
            return errorResult("表头 token 数已超过预算：" + headerTokens);
        }

        int noticeTokens = estimateTokenCount(TRUNC_NOTICE);
        int rowBudget = maxDataTokens - headerTokens - noticeTokens;
        int totalRows = dataRows == null ? 0 : dataRows.size();

        List<RowToken> rowTokenList = new ArrayList<>(Math.max(totalRows, 0));
        long allRowsTokenSum = 0L;
        for (int i = 0; i < totalRows; i++) {
            String rowLine = JsonUtil.toString(dataRows.get(i));
            int tokens = estimateTokenCount(rowLine + "\n");
            allRowsTokenSum += tokens;
            rowTokenList.add(new RowToken(rowLine, tokens));
        }

        // 全部数据在预算内，直接返回
        if (allRowsTokenSum <= rowBudget) {
            String result = buildResultString(headerLine, rowTokenList, totalRows, false);
            int token = estimateTokenCount(result);
            return successResult(result, totalRows, token, false);
        }

        // 二分查找能容纳的最大行数
        int left = 0;
        int right = totalRows;
        while (left < right) {
            int mid = (left + right + 1) >>> 1;
            long sum = 0L;
            for (int i = 0; i < mid; i++) {
                sum += rowTokenList.get(i).getTokens();
            }
            if (sum <= rowBudget) {
                left = mid;
            } else {
                right = mid - 1;
            }
        }

        int keepRows = left;
        String candidate = buildResultString(headerLine, rowTokenList, keepRows, true);
        int actualTokens = estimateTokenCount(candidate);

        // 实际 token 可能因换行/截断提示差异超出预算，逐行回退兜底
        while (actualTokens > maxDataTokens && keepRows > 0) {
            keepRows--;
            candidate = buildResultString(headerLine, rowTokenList, keepRows, true);
            actualTokens = estimateTokenCount(candidate);
        }

        // 如果连截断提示都放不下，只保留表头
        if (keepRows <= 0 && headerTokens + noticeTokens > maxDataTokens) {
            int token = estimateTokenCount(headerLine);
            return successResult(headerLine, 0, token, true);
        }

        return successResult(candidate, keepRows, actualTokens, true);
    }

    private int estimateTokenCount(String text) {
        if (StringUtils.isBlank(text)) {
            return 0;
        }
        return tokenizer.estimateTokenCountInText(text);
    }

    private String buildResultString(String headerLine, List<RowToken> rowTokenList, int keepRows,
            boolean truncated) {
        StringBuilder sb = new StringBuilder();
        sb.append(headerLine);
        for (int i = 0; i < keepRows; i++) {
            sb.append('\n').append(rowTokenList.get(i).getRowLine());
        }
        if (truncated && keepRows < rowTokenList.size()) {
            sb.append(TRUNC_NOTICE);
        }
        return sb.toString();
    }

    private TruncateResult errorResult(String error) {
        TruncateResult result = new TruncateResult();
        result.setSuccess(false);
        result.setError(error);
        return result;
    }

    private TruncateResult successResult(String result, int number, int token, boolean truncated) {
        TruncateResult truncateResult = new TruncateResult();
        truncateResult.setResult(result);
        truncateResult.setNumber(number);
        truncateResult.setToken(token);
        truncateResult.setTruncated(truncated);
        return truncateResult;
    }

    @Data
    public static class RowToken {
        private final String rowLine;
        private final int tokens;
    }

    @Data
    public static class TruncateResult {
        private boolean success = true;
        private String result;
        private int number;
        private int token;
        private boolean truncated;
        private String error;
    }
}
