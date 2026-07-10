package com.tencent.supersonic.chat.server.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.tencent.supersonic.chat.api.pojo.request.EasybiPureDataReq;
import com.tencent.supersonic.chat.api.pojo.response.EasybiPureDataResp;
import com.tencent.supersonic.chat.server.service.EasybiDataService;
import com.tencent.supersonic.chat.server.util.TokenBudgetTruncator;
import com.tencent.supersonic.common.util.HttpUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * EasyBI 原始数据查询服务实现。 负责透传调用 EasyBI getPureData4ChatBI 接口，并按 token 预算截断数据。
 */
@Slf4j
@Service
public class EasybiDataServiceImpl implements EasybiDataService {

    private static final String NO_DATA_FOUND = "NO_DATA_FOUND";

    private static final String DEFAULT_PROMPT_TEMPLATE = "#角色：你是一位数据分析师，请根据以下数据和用户问题给出解读。\n"
            + "#问题：{{queryText}}\n" + "#数据：\n{{data}}\n" + "#回答：";

    @Value("${easybi.pure-data.base-url:http://10.194.142.14:7999}")
    private String easybiBaseUrl;

    private final TokenBudgetTruncator truncator = new TokenBudgetTruncator();

    @Override
    public EasybiPureDataResp fetchPureData(EasybiPureDataReq req) {
        if (req == null || StringUtils.isBlank(req.getReportId())) {
            return error("reportId 不能为空");
        }
        if (StringUtils.isBlank(req.getEasyBiSession())) {
            return error("easyBiSession 不能为空");
        }

        int maxDataTokens = req.getMaxDataTokens() != null && req.getMaxDataTokens() > 0
                ? req.getMaxDataTokens()
                : 25000;

        String url = easybiBaseUrl + "/report/data/" + req.getReportId() + "/getPureData4ChatBI";
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("easyBiSession", req.getEasyBiSession());

        Map<String, Object> body = req.getParam();
        if (body == null) {
            body = Collections.emptyMap();
        }

        long start = System.currentTimeMillis();
        String respStr;
        try {
            respStr = HttpUtils.post(url, body, headers, String.class);
        } catch (IOException e) {
            log.error("[EasybiDataService] 调用 EasyBI 接口失败, url={}", url, e);
            return error("调用 EasyBI 接口失败：" + e.getMessage());
        }

        log.info("[EasybiDataService] 调用 EasyBI 接口耗时 {}ms, url={}",
                System.currentTimeMillis() - start, url);

        return parseAndTruncate(respStr, maxDataTokens, req.getQueryText(),
                req.getPromptTemplate());
    }

    private EasybiPureDataResp parseAndTruncate(String respStr, int maxDataTokens, String queryText,
            String promptTemplate) {
        try {
            JSONObject root = JSON.parseObject(respStr);
            if (root == null) {
                return error("EasyBI 返回为空");
            }
            JSONObject dataWrapper = root.getJSONObject("data");
            if (dataWrapper == null) {
                return error("EasyBI 返回缺少 data 字段");
            }
            JSONArray innerData = dataWrapper.getJSONArray("data");
            if (innerData == null || innerData.isEmpty()) {
                return noData();
            }

            // 索引 0 为中文表头，索引 1 为英文字段名，从索引 2 开始是数据
            JSONArray headerArray = innerData.getJSONArray(0);
            if (headerArray == null || innerData.size() <= 1) {
                return noData();
            }
            List<Object> headerRow = headerArray.toJavaList(Object.class);

            List<List<Object>> dataRows = new ArrayList<>();
            for (int i = 2; i < innerData.size(); i++) {
                JSONArray rowArray = innerData.getJSONArray(i);
                if (rowArray == null) {
                    continue;
                }
                dataRows.add(rowArray.toJavaList(Object.class));
            }

            if (dataRows.isEmpty()) {
                return noData();
            }

            TokenBudgetTruncator.TruncateResult truncateResult =
                    truncator.truncate(headerRow, dataRows, maxDataTokens);
            if (!truncateResult.isSuccess()) {
                return error(truncateResult.getError());
            }

            String dataText = truncateResult.getResult();
            EasybiPureDataResp resp = EasybiPureDataResp.builder().success(0).result(dataText)
                    .number(truncateResult.getNumber()).token(truncateResult.getToken())
                    .truncated(truncateResult.isTruncated()).error(null).build();

            if (StringUtils.isNotBlank(queryText)) {
                String prompt = assemblePrompt(queryText, dataText, promptTemplate);
                resp.setPrompt(prompt);
            }
            return resp;
        } catch (Exception e) {
            log.error("[EasybiDataService] 解析 EasyBI 响应失败, resp={}",
                    StringUtils.abbreviate(respStr, 500), e);
            return error("解析 EasyBI 响应失败：" + e.getMessage());
        }
    }

    private String assemblePrompt(String queryText, String dataText, String promptTemplate) {
        String template =
                StringUtils.isNotBlank(promptTemplate) ? promptTemplate : DEFAULT_PROMPT_TEMPLATE;
        return template.replace("{{queryText}}", queryText).replace("{{data}}", dataText);
    }

    private EasybiPureDataResp error(String error) {
        return EasybiPureDataResp.builder().success(1).result(null).prompt(null).number(0).token(0)
                .truncated(false).error(error).build();
    }

    private EasybiPureDataResp noData() {
        return EasybiPureDataResp.builder().success(2).result(NO_DATA_FOUND).prompt(null).number(0)
                .token(0).truncated(false).error(null).build();
    }
}
