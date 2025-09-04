package com.tencent.supersonic.headless.server.aspect;

import com.google.common.collect.Sets;
import com.tencent.supersonic.auth.api.authorization.pojo.AuthRes;
import com.tencent.supersonic.auth.api.authorization.pojo.DimensionFilter;
import com.tencent.supersonic.auth.api.authorization.request.QueryAuthResReq;
import com.tencent.supersonic.auth.api.authorization.response.AuthorizedResourceResp;
import com.tencent.supersonic.auth.api.authorization.service.AuthService;
import com.tencent.supersonic.common.jsqlparser.FieldExpression;
import com.tencent.supersonic.common.jsqlparser.SqlAddHelper;
import com.tencent.supersonic.common.jsqlparser.SqlReplaceHelper;
import com.tencent.supersonic.common.jsqlparser.SqlSelectHelper;
import com.tencent.supersonic.common.pojo.Filter;
import com.tencent.supersonic.common.pojo.QueryAuthorization;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.enums.FilterOperatorEnum;
import com.tencent.supersonic.common.pojo.enums.SensitiveLevelEnum;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.api.pojo.MetaFilter;
import com.tencent.supersonic.headless.api.pojo.request.QuerySqlReq;
import com.tencent.supersonic.headless.api.pojo.request.QueryStructReq;
import com.tencent.supersonic.headless.api.pojo.request.SchemaFilterReq;
import com.tencent.supersonic.headless.api.pojo.request.SemanticQueryReq;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticSchemaResp;
import com.tencent.supersonic.headless.server.service.ModelService;
import com.tencent.supersonic.headless.server.service.SchemaService;
import com.tencent.supersonic.headless.server.utils.QueryStructUtils;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Component
@Aspect
@Slf4j
public class DimensionDefaultValueAspect {
    @Autowired
    private SchemaService schemaService;
    @Autowired
    private AuthService authService;

    @Pointcut("@annotation(com.tencent.supersonic.headless.server.annotation.DefaultDimValueCheck)")
    private void defaultDimValueCheck() {}

    @Around("defaultDimValueCheck()")
    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        // 1. check args
        Object[] objects = joinPoint.getArgs();

        QuerySqlReq sqlReq = (QuerySqlReq) objects[0];
        if (sqlReq.getDataSetName() != null) {
            String escapedTable = SqlReplaceHelper.escapeTableName(sqlReq.getDataSetName());
            sqlReq.setSql(sqlReq.getSql().replaceAll(String.format(" %s ", sqlReq.getDataSetName()),
                    String.format(" %s ", escapedTable)));
        }
        if (sqlReq == null) {
            throw new InvalidArgumentException("queryReq is not Invalid");
        }

        SemanticSchemaResp semanticSchemaResp = getSemanticSchemaResp(sqlReq);
        Map<String, String> defaultDimNameMap = semanticSchemaResp.getDimensions().stream()
                .filter(dimSchemaResp -> !CollectionUtils.isEmpty(dimSchemaResp.getDefaultValues()))
                .collect(Collectors.toMap(dimSchemaResp -> dimSchemaResp.getName(),
                        dimSchemaResp -> dimSchemaResp.getDefaultValues().get(0)));
        if (CollectionUtils.isEmpty(defaultDimNameMap)) {
            return joinPoint.proceed();
        }


        String querySQL = sqlReq.getSql();
        Set<String> filterNameList = SqlSelectHelper.getFilterExpression(querySQL).stream()
                .map(FieldExpression::getFieldName).collect(Collectors.toSet());
        Set<String> orderByNameList = SqlSelectHelper.getOrderByExpressions(querySQL).stream()
                .map(FieldExpression::getFieldName).collect(Collectors.toSet());
        String correctedSql = querySQL;

        for (Map.Entry<String, String> entry : defaultDimNameMap.entrySet()) {
            String dimensionName = entry.getKey();

            if ((!filterNameList.contains(dimensionName))
                    && !hasProvinceCityRelation(dimensionName, filterNameList)) {

                correctedSql = SqlAddHelper.addWhere(correctedSql, dimensionName, entry.getValue());

            }
        }
        sqlReq.setSql(correctedSql);
        return joinPoint.proceed();
    }

    // 问题的条件中包含城市，即使条件不包含省份，也不能添加不能加省份='全国'的默认条件,为ture代表是 问城市&&检查省份的情况
    private boolean hasProvinceCityRelation(String dimensionName, Set<String> filterNameList) {
        if (!(StringUtils.equals(dimensionName, "省份") || StringUtils.equals(dimensionName, "省份名称")
                || StringUtils.equals(dimensionName, "省份名"))) {
            return false;
        }
        return filterNameList.stream()
                .filter(name -> StringUtils.equals(name, "地市") || StringUtils.equals(name, "地市名称")
                        || StringUtils.equals(name, "地市名") || StringUtils.equals(name, "城市")
                        || StringUtils.equals(name, "城市名称") || StringUtils.equals(name, "城市名"))
                .count() > 0;
    }


    private SemanticSchemaResp getSemanticSchemaResp(SemanticQueryReq semanticQueryReq) {
        SchemaFilterReq filter = new SchemaFilterReq();
        filter.setModelIds(semanticQueryReq.getModelIds());
        filter.setDataSetId(semanticQueryReq.getDataSetId());
        return schemaService.fetchSemanticSchema(filter);
    }

}
