package com.tencent.supersonic.headless.server.utils;

import com.tencent.supersonic.common.pojo.enums.QueryType;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.api.pojo.SchemaElementMatch;
import com.tencent.supersonic.headless.api.pojo.SchemaMapInfo;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.SqlInfo;
import com.tencent.supersonic.headless.api.pojo.enums.ChatWorkflowState;
import com.tencent.supersonic.headless.api.pojo.request.SemanticQueryReq;
import com.tencent.supersonic.headless.api.pojo.response.ParseResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticTranslateResp;
import com.tencent.supersonic.headless.chat.ChatQueryContext;
import com.tencent.supersonic.headless.chat.corrector.SemanticCorrector;
import com.tencent.supersonic.headless.chat.mapper.SchemaMapper;
import com.tencent.supersonic.headless.chat.parser.SemanticParser;
import com.tencent.supersonic.headless.chat.query.QueryManager;
import com.tencent.supersonic.headless.chat.query.SemanticQuery;
import com.tencent.supersonic.headless.core.cache.QueryCache;
import com.tencent.supersonic.headless.core.utils.ComponentFactory;
import com.tencent.supersonic.headless.server.facade.service.SemanticLayerService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.checkerframework.checker.units.qual.A;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ChatWorkflowEngine {

    private final List<SchemaMapper> schemaMappers = CoreComponentFactory.getSchemaMappers();
    private final List<SemanticParser> semanticParsers = CoreComponentFactory.getSemanticParsers();
    private final List<SemanticCorrector> semanticCorrectors =
            CoreComponentFactory.getSemanticCorrectors();
    private final String MAPINFO_IS_NULL_STR="您好~这里是红海ChatBI，您的问题不在我的业务知识范围内，我可以帮您查询咪咕重点产品的核心指标数据、分省、分渠道、分场景的活跃数据，咪咕视频的内容播放数据，比如您可以查询咪咕视频上月的全场景活跃用户，最近一周最火的体育赛事。";

    @Autowired
    private DimensionValuesMatchHelper dimensionValuesMatchHelper;

    public void start(ChatWorkflowState initialState, ChatQueryContext queryCtx) {
        ParseResp parseResult = queryCtx.getParseResp();
        queryCtx.setChatWorkflowState(initialState);
        while (queryCtx.getChatWorkflowState() != ChatWorkflowState.FINISHED) {
            switch (queryCtx.getChatWorkflowState()) {
                case MAPPING:
                    performMapping(queryCtx);
                    if (queryCtx.getIsTip()) {
                        dimensionValuesMatchHelper.dimensionValuesStoreToCache(queryCtx);
                    }
                    if (queryCtx.getMapInfo().isEmpty()) {
                        errDefault(parseResult,queryCtx);
                    }else{
                        queryCtx.setChatWorkflowState(ChatWorkflowState.PARSING);
                    }

//                    if (queryCtx.getMapInfo().isEmpty()) {
//                        parseResult.setState(ParseResp.ParseState.FAILED);
//                        parseResult.setErrorMsg(
//                                "No semantic entities can be mapped against user question.");
//                        queryCtx.setChatWorkflowState(ChatWorkflowState.FINISHED);
//                    } else {
//                        queryCtx.setChatWorkflowState(ChatWorkflowState.PARSING);
//                    }

                    break;
                case PARSING:
                    performParsing(queryCtx);
                    if (queryCtx.getCandidateQueries().isEmpty()) {
                       errDefault(parseResult,queryCtx);
                    } else {
                        List<SemanticParseInfo> parseInfos = queryCtx.getCandidateQueries().stream()
                                .map(SemanticQuery::getParseInfo).collect(Collectors.toList());
                        parseResult.setSelectedParses(parseInfos);
                        if (queryCtx.needSQL() && !StringUtils.endsWithIgnoreCase(
                                queryCtx.getSemanticSchema().getDataSets().get(0).getDataSetName(),
                                "直连模式")) {
                            queryCtx.setChatWorkflowState(ChatWorkflowState.S2SQL_CORRECTING);
                        } else {
                            parseResult.setState(ParseResp.ParseState.COMPLETED);
                            queryCtx.setChatWorkflowState(ChatWorkflowState.FINISHED);
                        }
                    }
                    /**
                     * 原逻辑
                     */
//                    if (queryCtx.getCandidateQueries().isEmpty()) {
//                        parseResult.setState(ParseResp.ParseState.FAILED);
//                        parseResult.setErrorMsg("No semantic queries can be parsed out.");
//                        queryCtx.setChatWorkflowState(ChatWorkflowState.FINISHED);
//                    } else {
//                        List<SemanticParseInfo> parseInfos = queryCtx.getCandidateQueries().stream()
//                                .map(SemanticQuery::getParseInfo).collect(Collectors.toList());
//                        parseResult.setSelectedParses(parseInfos);
//                        if (queryCtx.needSQL() && !StringUtils.endsWithIgnoreCase(
//                                queryCtx.getSemanticSchema().getDataSets().get(0).getDataSetName(),
//                                "直连模式")) {
//                            queryCtx.setChatWorkflowState(ChatWorkflowState.S2SQL_CORRECTING);
//                        } else {
//                            parseResult.setState(ParseResp.ParseState.COMPLETED);
//                            queryCtx.setChatWorkflowState(ChatWorkflowState.FINISHED);
//                        }
//                    }
                    break;
                case S2SQL_CORRECTING:
                    performCorrecting(queryCtx);
                    queryCtx.setChatWorkflowState(ChatWorkflowState.TRANSLATING);
                    break;
                case TRANSLATING:
                    long start = System.currentTimeMillis();
                    performTranslating(queryCtx, parseResult);
                    parseResult.getParseTimeCost().setSqlTime(System.currentTimeMillis() - start);
                    queryCtx.setChatWorkflowState(ChatWorkflowState.FINISHED);
                    break;
                default:
                    if (parseResult.getState().equals(ParseResp.ParseState.PENDING)) {
                        parseResult.setState(ParseResp.ParseState.COMPLETED);
                    }
                    queryCtx.setChatWorkflowState(ChatWorkflowState.FINISHED);
                    break;
            }
        }
    }

    /**
     * 当mapping为空时或queryCtx.getCandidateQueries()调用
     * @param parseResult
     * @param queryCtx
     */
    private void errDefault(ParseResp parseResult,ChatQueryContext queryCtx){
        List<SemanticParseInfo> selectedParses =new ArrayList<>();
        SemanticParseInfo semanticParseInfo = new SemanticParseInfo();
        SqlInfo sqlInfo=new SqlInfo();
        sqlInfo.setParsedS2SQL(MAPINFO_IS_NULL_STR);
        sqlInfo.setCorrectedS2SQL(MAPINFO_IS_NULL_STR);
        sqlInfo.setQuerySQL(null);
        sqlInfo.setResultType("text");
        semanticParseInfo.setSqlInfo(sqlInfo);
        semanticParseInfo.setQueryMode("LLM_S2SQL");
        semanticParseInfo.setQueryType(QueryType.DETAIL);
        selectedParses.add(semanticParseInfo);
        parseResult.setSelectedParses(selectedParses);
        parseResult.setState(ParseResp.ParseState.COMPLETED);
        queryCtx.setChatWorkflowState(ChatWorkflowState.FINISHED);
    }
    private void performMapping(ChatQueryContext queryCtx) {
        if (Objects.isNull(queryCtx.getMapInfo())
                || MapUtils.isEmpty(queryCtx.getMapInfo().getDataSetElementMatches())) {
            schemaMappers.forEach(mapper -> mapper.map(queryCtx));
        }
    }

    private void performParsing(ChatQueryContext queryCtx) {
        semanticParsers.forEach(parser -> {
            parser.parse(queryCtx);
            // log.debug("{} result:{}", parser.getClass().getSimpleName(),
            // JsonUtil.toString(queryCtx));
        });
    }

    private void performCorrecting(ChatQueryContext queryCtx) {
        List<SemanticQuery> candidateQueries = queryCtx.getCandidateQueries();
        if (CollectionUtils.isNotEmpty(candidateQueries)) {
            for (SemanticQuery semanticQuery : candidateQueries) {
                for (SemanticCorrector corrector : semanticCorrectors) {
                    corrector.correct(queryCtx, semanticQuery.getParseInfo());
                    if (!ChatWorkflowState.S2SQL_CORRECTING
                            .equals(queryCtx.getChatWorkflowState())) {
                        break;
                    }
                }
            }
        }
    }

    private void performTranslating(ChatQueryContext queryCtx, ParseResp parseResult) {
        List<SemanticParseInfo> semanticParseInfos = queryCtx.getCandidateQueries().stream()
                .map(SemanticQuery::getParseInfo).collect(Collectors.toList());
        List<String> errorMsg = new ArrayList<>();
        if (StringUtils.isNotBlank(parseResult.getErrorMsg())) {
            errorMsg.add(parseResult.getErrorMsg());
        }
        semanticParseInfos.forEach(parseInfo -> {
            try {
                SemanticQuery semanticQuery = QueryManager.createQuery(parseInfo.getQueryMode());
                if (Objects.isNull(semanticQuery)) {
                    return;
                }
                semanticQuery.setParseInfo(parseInfo);
                SemanticQueryReq semanticQueryReq = semanticQuery.buildSemanticQueryReq();
                SemanticLayerService queryService =
                        ContextUtils.getBean(SemanticLayerService.class);
                SemanticTranslateResp explain =
                        queryService.translate(semanticQueryReq, queryCtx.getRequest().getUser());
                if (explain.isOk()) {
                    parseInfo.getSqlInfo().setQuerySQL(explain.getQuerySQL());
                    parseResult.setState(ParseResp.ParseState.COMPLETED);
                } else {
                    parseResult.setState(ParseResp.ParseState.FAILED);
                }
                if (StringUtils.isNotBlank(explain.getErrMsg())) {
                    errorMsg.add(explain.getErrMsg());
                }
                log.info(
                        "SqlInfoProcessor results:\n"
                                + "Parsed S2SQL: {}\nCorrected S2SQL: {}\nQuery SQL: {}",
                        StringUtils.normalizeSpace(parseInfo.getSqlInfo().getParsedS2SQL()),
                        StringUtils.normalizeSpace(parseInfo.getSqlInfo().getCorrectedS2SQL()),
                        StringUtils.normalizeSpace(parseInfo.getSqlInfo().getQuerySQL()));
            } catch (Exception e) {
                log.warn("get sql info failed:{}", e);
                errorMsg.add(String.format("S2SQL:%s %s", parseInfo.getSqlInfo().getParsedS2SQL(),
                        e.getMessage()));
            }
        });
        if (!errorMsg.isEmpty()) {
            parseResult.setErrorMsg(String.join("\n", errorMsg));
        }
    }
}
