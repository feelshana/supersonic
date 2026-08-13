package com.tencent.supersonic.headless.core.translator.parser.calcite;

import com.google.common.collect.Sets;
import com.tencent.supersonic.common.calcite.Configuration;
import com.tencent.supersonic.common.jsqlparser.FieldExpression;
import com.tencent.supersonic.common.jsqlparser.SqlAddHelper;
import com.tencent.supersonic.common.jsqlparser.SqlSelectHelper;
import com.tencent.supersonic.common.pojo.BiReportConfigDO;
import com.tencent.supersonic.common.pojo.enums.EngineType;
import com.tencent.supersonic.headless.api.pojo.Dimension;
import com.tencent.supersonic.headless.api.pojo.Identify;
import com.tencent.supersonic.headless.api.pojo.SchemaItem;
import com.tencent.supersonic.headless.api.pojo.enums.IdentifyType;
import com.tencent.supersonic.headless.api.pojo.response.*;
import com.tencent.supersonic.headless.core.pojo.JoinRelation;
import com.tencent.supersonic.headless.core.pojo.Ontology;
import com.tencent.supersonic.headless.core.pojo.OntologyQuery;
import com.tencent.supersonic.headless.core.pojo.QueryStatement;
import com.tencent.supersonic.headless.core.translator.parser.Constants;
import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.dialect.CalciteSqlDialect;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.validate.SqlValidatorScope;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Triple;
import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DefaultUndirectedGraph;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class SqlBuilder {

    private final S2CalciteSchema schema;
    private final SqlValidatorScope scope;
    public static final SqlParserPos pos = SqlParserPos.ZERO;

    public SqlBuilder(S2CalciteSchema schema) {
        this.schema = schema;
        this.scope = SchemaBuilder.getScope(schema);
    }

    public static String createTableSql(SemanticSchemaResp semanticSchema, EngineType engineType)
            throws Exception {
        List<SqlNode> selectList = new ArrayList<>();
        for (DimSchemaResp dimSchemaResp : semanticSchema.getDimensions()) {
            if (StringUtils.isNotBlank(dimSchemaResp.getExpr()) && !StringUtils
                    .equalsIgnoreCase(dimSchemaResp.getExpr(), dimSchemaResp.getBizName())) {
                selectList.add(createAlias(dimSchemaResp.getExpr(), dimSchemaResp.getBizName(),
                        pos, engineType));
            } else {
                selectList.add(createColumn(dimSchemaResp.getBizName(), pos, engineType));
            }

        }
        for (MetricSchemaResp metricSchemaResp : semanticSchema.getMetrics()) {
            if (StringUtils.isNotBlank(metricSchemaResp.getExpr()) && !StringUtils
                    .equalsIgnoreCase(metricSchemaResp.getExpr(), metricSchemaResp.getBizName())) {
                selectList.add(createAlias(metricSchemaResp.getExpr(),
                        metricSchemaResp.getBizName(), pos, engineType));
            } else {
                selectList.add(createColumn(metricSchemaResp.getBizName(), pos, engineType));
            }
        }
        SqlNodeList selectListNode = new SqlNodeList(selectList, pos);

        SqlIdentifier tableName = new SqlIdentifier(Arrays.asList(semanticSchema.getModelResps()
                .getFirst().getModelDetail().getTableQuery().split("\\.")), pos);
        SqlSelect sqlSelect = new SqlSelect(pos, null, selectListNode, tableName, null, null, null,
                null, null, null, null, null);
        return sqlSelect.toString();

    }

    public String buildOntologySql(QueryStatement queryStatement) throws Exception {
        OntologyQuery ontologyQuery = queryStatement.getOntologyQuery();
        Ontology ontology = queryStatement.getOntology();
        SemanticSchemaResp semanticSchema = queryStatement.getSemanticSchema();
        semanticSchema.setSegmentDimBizNames(queryStatement.getSegmentDimBizNames());
        if (ontologyQuery.getLimit() == null) {
            ontologyQuery.setLimit(0L);
        }

        Set<ModelResp> dataModels = ontologyQuery.getModels();
        if (dataModels == null || dataModels.isEmpty()) {
            throw new Exception("data model not found");
        }

        TableView tableView;
        if (!CollectionUtils.isEmpty(ontology.getJoinRelations()) && dataModels.size() > 1) {
            Set<ModelResp> models = probeRelatedModels(dataModels, queryStatement.getOntology());
            tableView = render(ontologyQuery, models, scope, schema, semanticSchema,
                    queryStatement.getDimensionRelations());
        } else {
            tableView = render(ontologyQuery, dataModels, scope, schema, semanticSchema,
                    queryStatement.getDimensionRelations());
        }

        SqlNode parserNode = tableView.build();
        DatabaseResp database = queryStatement.getOntology().getDatabase();
        EngineType engineType = EngineType.fromString(database.getType());
        try {
            // parserNode = optimizeParseNode(parserNode, engineType);
        } catch (Exception e) {
            // failure in optimization phase doesn't affect the query result,
            // just ignore it
            log.error("optimizeParseNode error", e);
        }
        return SemanticNode.getSql(parserNode, engineType);
    }

    private Set<ModelResp> probeRelatedModels(Set<ModelResp> dataModels, Ontology ontology) {
        List<JoinRelation> joinRelations = ontology.getJoinRelations();
        Graph<String, DefaultEdge> graph = buildGraph(joinRelations);
        DijkstraShortestPath<String, DefaultEdge> dijkstraAlg = new DijkstraShortestPath<>(graph);
        Set<String> queryModels =
                dataModels.stream().map(ModelResp::getName).collect(Collectors.toSet());
        GraphPath<String, DefaultEdge> selectedGraphPath = null;
        for (String fromModel : queryModels) {
            for (String toModel : queryModels) {
                if (!fromModel.equals(toModel)) {
                    GraphPath<String, DefaultEdge> path = dijkstraAlg.getPath(fromModel, toModel);
                    if (isGraphPathContainsAll(path, queryModels)) {
                        selectedGraphPath = path;
                        break;
                    }
                }
            }
        }
        if (selectedGraphPath == null) {
            return dataModels;
        }
        Set<String> modelNames = Sets.newLinkedHashSet();
        for (DefaultEdge edge : selectedGraphPath.getEdgeList()) {
            modelNames.add(selectedGraphPath.getGraph().getEdgeSource(edge));
            modelNames.add(selectedGraphPath.getGraph().getEdgeTarget(edge));
        }
        return modelNames.stream().map(m -> ontology.getModelMap().get(m))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean isGraphPathContainsAll(GraphPath<String, DefaultEdge> graphPath,
            Set<String> vertex) {
        Set<String> allVertex = Sets.newHashSet();
        for (DefaultEdge edge : graphPath.getEdgeList()) {
            allVertex.add(graphPath.getGraph().getEdgeSource(edge));
            allVertex.add(graphPath.getGraph().getEdgeTarget(edge));
        }
        Collection<String> intersect =
                org.apache.commons.collections.CollectionUtils.intersection(vertex, allVertex);

        return intersect.size() == vertex.size() ? true : false;
    }

    private Graph<String, DefaultEdge> buildGraph(List<JoinRelation> joinRelations) {
        Graph<String, DefaultEdge> directedGraph = new DefaultUndirectedGraph<>(DefaultEdge.class);
        for (JoinRelation joinRelation : joinRelations) {
            directedGraph.addVertex(joinRelation.getLeft());
            directedGraph.addVertex(joinRelation.getRight());
            directedGraph.addEdge(joinRelation.getLeft(), joinRelation.getRight());
        }
        return directedGraph;
    }

    private SqlNode optimizeParseNode(SqlNode parserNode, EngineType engineType)
            throws SqlParseException {
        if (Objects.isNull(schema.getRuntimeOptions())
                || Objects.isNull(schema.getRuntimeOptions().getEnableOptimize())
                || !schema.getRuntimeOptions().getEnableOptimize()) {
            return parserNode;
        }

        SqlNode optimizeNode = null;
        SqlNode sqlNode = SqlParser.create(SemanticNode.getSql(parserNode, engineType),
                Configuration.getParserConfig(engineType)).parseStmt();
        if (Objects.nonNull(sqlNode)) {
            optimizeNode = SemanticNode.optimize(scope, schema, sqlNode, engineType);
        }

        if (Objects.nonNull(optimizeNode)) {
            return optimizeNode;
        }

        return parserNode;
    }

    private TableView render(OntologyQuery ontologyQuery, Set<ModelResp> dataModels,
            SqlValidatorScope scope, S2CalciteSchema schema, SemanticSchemaResp semanticSchema,
            List<BiReportConfigDO> dimensionRelations) throws Exception {
        SqlNode left = null;
        TableView leftTable = null;
        TableView outerTable = new TableView();
        // Map<String, SqlNode> outerSelect = new HashMap<>();
        Map<String, String> beforeModels = new HashMap<>();
        Set<String> whereDimBizNames = ontologyQuery.getWhereDimBizNames();
        // EngineType engineType =
        // EngineType.fromString(schema.getOntology().getDatabase().getType());

        for (ModelResp dataModel : dataModels) {
            final Set<DimSchemaResp> queryDimensions =
                    ontologyQuery.getDimensionsByModel(dataModel.getName());
            final Set<MetricSchemaResp> queryMetrics =
                    ontologyQuery.getMetricsByModel(dataModel.getName());

            List<String> primary = new ArrayList<>();
            for (Identify identify : dataModel.getIdentifiers()) {
                primary.add(identify.getName());
            }

            TableView tableView = renderOne(queryMetrics, queryDimensions, dataModel, scope, schema,
                    semanticSchema, dimensionRelations, whereDimBizNames);
            log.info("【内层表的sql】:\n{}", StringUtils.normalizeSpace(tableView.getTable().toString()));
            String alias = Constants.JOIN_TABLE_PREFIX + dataModel.getName();
            tableView.setAlias(alias);
            tableView.setPrimary(primary);
            tableView.setDataModel(dataModel);
            // for (String field : tableView.getFields()) {
            // outerSelect.put(field, SemanticNode.parse(alias + "." + field, scope, engineType));
            // }
            if (left == null) {
                left = SemanticNode.buildAs(tableView.getAlias(), getTable(tableView));
            } else {
                left = buildJoin(left, leftTable, tableView, beforeModels, dataModel, schema,
                        scope);
            }
            leftTable = tableView;
            beforeModels.put(dataModel.getName(), leftTable.getAlias());
        }

        // for (Map.Entry<String, SqlNode> entry : outerSelect.entrySet()) {
        // outerTable.getSelect().add(entry.getValue());
        // }
        outerTable.getSelect().add(SqlIdentifier.STAR);
        outerTable.setTable(left);
        outerTable.setWhere(leftTable.getWhere());

        return outerTable;
    }

    private SqlNode getTable(TableView tableView) {
        return SemanticNode.getTable(tableView.getTable());
    }

    private SqlNode buildJoin(SqlNode leftNode, TableView leftTable, TableView rightTable,
            Map<String, String> before, ModelResp dataModel, S2CalciteSchema schema,
            SqlValidatorScope scope) throws Exception {
        EngineType engineType = EngineType.fromString(schema.getOntology().getDatabase().getType());
        SqlNode condition =
                getCondition(leftTable, rightTable, dataModel, schema, scope, engineType);
        SqlLiteral sqlLiteral = SemanticNode.getJoinSqlLiteral("");
        JoinRelation matchJoinRelation = getMatchJoinRelation(before, rightTable, schema);
        SqlNode joinRelationCondition;
        if (!org.apache.commons.collections.CollectionUtils
                .isEmpty(matchJoinRelation.getJoinCondition())) {
            sqlLiteral = SemanticNode.getJoinSqlLiteral(matchJoinRelation.getJoinType());
            joinRelationCondition = getCondition(matchJoinRelation, scope, engineType);
            condition = joinRelationCondition;
        }

        return new SqlJoin(SqlParserPos.ZERO, leftNode,
                SqlLiteral.createBoolean(false, SqlParserPos.ZERO), sqlLiteral,
                SemanticNode.buildAs(rightTable.getAlias(), getTable(rightTable)),
                SqlLiteral.createSymbol(JoinConditionType.ON, SqlParserPos.ZERO), condition);
    }

    private JoinRelation getMatchJoinRelation(Map<String, String> before, TableView tableView,
            S2CalciteSchema schema) {
        JoinRelation matchJoinRelation = JoinRelation.builder().build();
        if (!CollectionUtils.isEmpty(schema.getJoinRelations())) {
            for (JoinRelation joinRelation : schema.getJoinRelations()) {
                if (joinRelation.getRight().equalsIgnoreCase(tableView.getDataModel().getName())
                        && before.containsKey(joinRelation.getLeft())) {
                    matchJoinRelation.setJoinCondition(joinRelation.getJoinCondition().stream()
                            .map(r -> Triple.of(
                                    before.get(joinRelation.getLeft()) + "." + r.getLeft(),
                                    r.getMiddle(), tableView.getAlias() + "." + r.getRight()))
                            .collect(Collectors.toList()));
                    matchJoinRelation.setJoinType(joinRelation.getJoinType());
                    // Added join condition judgment to solve the problem of join condition order
                } else if (joinRelation.getLeft()
                        .equalsIgnoreCase(tableView.getDataModel().getName())
                        && before.containsKey(joinRelation.getRight())) {
                    List<Triple<String, String, String>> candidateJoinCon = joinRelation
                            .getJoinCondition().stream()
                            .map(r -> Triple.of(
                                    before.get(joinRelation.getRight()) + "." + r.getRight(),
                                    r.getMiddle(), tableView.getAlias() + "." + r.getLeft()))
                            .collect(Collectors.toList());
                    // added by jerryjzhang on 20250214
                    // use the one with the most conditions to join left and right tables
                    if (matchJoinRelation.getJoinCondition() == null || candidateJoinCon
                            .size() > matchJoinRelation.getJoinCondition().size()) {
                        matchJoinRelation.setJoinCondition(candidateJoinCon);
                        matchJoinRelation.setJoinType(joinRelation.getJoinType());
                    }
                }
            }
        }
        return matchJoinRelation;
    }

    private SqlNode getCondition(JoinRelation joinRelation, SqlValidatorScope scope,
            EngineType engineType) throws Exception {
        SqlNode condition = null;
        for (Triple<String, String, String> con : joinRelation.getJoinCondition()) {
            List<SqlNode> ons = new ArrayList<>();
            ons.add(SemanticNode.parse(con.getLeft(), scope, engineType));
            ons.add(SemanticNode.parse(con.getRight(), scope, engineType));
            if (Objects.isNull(condition)) {
                condition = new SqlBasicCall(SemanticNode.getBinaryOperator(con.getMiddle()), ons,
                        SqlParserPos.ZERO, null);
                continue;
            }
            SqlNode addCondition = new SqlBasicCall(SemanticNode.getBinaryOperator(con.getMiddle()),
                    ons, SqlParserPos.ZERO, null);
            condition = new SqlBasicCall(SqlStdOperatorTable.AND,
                    new ArrayList<>(Arrays.asList(condition, addCondition)), SqlParserPos.ZERO,
                    null);
        }
        return condition;
    }

    private SqlNode getCondition(TableView left, TableView right, ModelResp dataModel,
            S2CalciteSchema schema, SqlValidatorScope scope, EngineType engineType)
            throws Exception {

        Set<String> selectLeft = SemanticNode.getSelect(left.getTable());
        Set<String> selectRight = SemanticNode.getSelect(right.getTable());
        selectLeft.retainAll(selectRight);
        SqlNode condition = null;
        for (String on : selectLeft) {
            if (!isDimension(on, dataModel, schema)) {
                continue;
            }
            if (isForeign(on, left.getDataModel().getIdentifiers())) {
                if (!isPrimary(on, right.getDataModel().getIdentifiers())) {
                    continue;
                }
            }
            if (isForeign(on, right.getDataModel().getIdentifiers())) {
                if (!isPrimary(on, left.getDataModel().getIdentifiers())) {
                    continue;
                }
            }
            List<SqlNode> ons = new ArrayList<>();
            ons.add(SemanticNode.parse(left.getAlias() + "." + on, scope, engineType));
            ons.add(SemanticNode.parse(right.getAlias() + "." + on, scope, engineType));
            if (condition == null) {
                condition =
                        new SqlBasicCall(SqlStdOperatorTable.EQUALS, ons, SqlParserPos.ZERO, null);
                continue;
            }
            SqlNode addCondition =
                    new SqlBasicCall(SqlStdOperatorTable.EQUALS, ons, SqlParserPos.ZERO, null);
            condition = new SqlBasicCall(SqlStdOperatorTable.AND,
                    new ArrayList<>(Arrays.asList(condition, addCondition)), SqlParserPos.ZERO,
                    null);
        }
        return condition;
    }

    public static TableView renderOne(Set<MetricSchemaResp> queryMetrics,
            Set<DimSchemaResp> queryDimensions, ModelResp dataModel, SqlValidatorScope scope,
            S2CalciteSchema schema, SemanticSchemaResp semanticSchema,
            List<BiReportConfigDO> dimensionRelations, Set<String> whereDimBizNames) {
        TableView tableView = new TableView();
        EngineType engineType = EngineType.fromString(schema.getOntology().getDatabase().getType());
        // Set<String> queryFields = tableView.getFields();
        // if (Objects.nonNull(queryMetrics)) {
        // queryMetrics.stream().forEach(m -> queryFields.addAll(m.getFields()));
        // }
        // if (Objects.nonNull(queryDimensions)) {
        // queryDimensions.stream().forEach(d -> queryFields.addAll(d.getFields()));
        // }

        try {
            // for (String field : queryFields) {
            // tableView.getSelect().add(SemanticNode.parse(field, scope, engineType));
            // }


            // tableView.getSelect().add(SqlIdentifier.STAR);
            tableView.setTable(DataModelNode.build(dataModel, scope, semanticSchema));
            tableView.setWhere(extractDefaultDimValue(semanticSchema, queryDimensions,
                    dimensionRelations, whereDimBizNames));
        } catch (Exception e) {
            log.error("Failed to create sqlNode for table,tableQuery:{},SqlQuery:{}",
                    dataModel.getModelDetail().getTableQuery(),
                    dataModel.getModelDetail().getSqlQuery(), e);
        }

        return tableView;
    }

    private static SqlNode extractDefaultDimValue(SemanticSchemaResp semanticSchema,
            Set<DimSchemaResp> dimSchemaRespSet, List<BiReportConfigDO> dimensionRelations,
            Set<String> whereDimBizNames) {

        // 获取所有有默认值的维度
        Map<String, String> defaultDimNameMap = semanticSchema.getDimensions().stream()
                .filter(dimSchemaResp -> !org.springframework.util.CollectionUtils
                        .isEmpty(dimSchemaResp.getDefaultValues()))
                .collect(Collectors.toMap(SchemaItem::getBizName,
                        dimSchemaResp -> dimSchemaResp.getDefaultValues().getFirst()));
        if (defaultDimNameMap.isEmpty()) {
            return null;
        }
        List<String> childCondtionList = null;
        List<String> siblingCondtionList = null;
        if (CollectionUtils.isNotEmpty(dimensionRelations)) {
            // 1.判断是否属于层级分类维度
            BiReportConfigDO childCondtionConfigDO = dimensionRelations.stream()
                    .filter(configDO -> configDO.getType() == 1).findFirst().orElse(null);
            if (childCondtionConfigDO != null) {
                childCondtionList =
                        Arrays.asList(childCondtionConfigDO.getDimRelation().split(","));
            }
            // 2.判断是否存在同级维度
            BiReportConfigDO siblingCondtionConfigDO = dimensionRelations.stream()
                    .filter(configDO -> configDO.getType() == 2).findFirst().orElse(null);
            if (siblingCondtionConfigDO != null) {
                siblingCondtionList =
                        Arrays.asList(siblingCondtionConfigDO.getDimRelation().split(","));
            }
        }

        // WHERE子句中出现的维度bizName
        Set<String> effectiveWhereDimBizNames = new HashSet<>(whereDimBizNames);
        // 剔除用户已显式用=默认值的维度，避免对已筛选到默认值的维度追加!=条件
        Set<String> defaultDimFilterNameList = dimSchemaRespSet.stream().filter(dim -> {
            if (CollectionUtils.isEmpty(dim.getDefaultValues())) {
                return false;
            }
            if (StringUtils.isBlank(dim.getCurrentValue())) {
                return false;
            }
            String defaultValue = dim.getDefaultValues().get(0);
            String currentValue = dim.getCurrentValue();
            return defaultValue.equals(currentValue);
        }).map(DimSchemaResp::getBizName).collect(Collectors.toSet());

        if (!CollectionUtils.isEmpty(defaultDimFilterNameList)) {
            effectiveWhereDimBizNames.removeAll(defaultDimFilterNameList);
        }

        List<SqlNode> andConditions = new ArrayList<>();
        log.info("需要进行默认值处理的WHERE维度:{}", effectiveWhereDimBizNames);
        for (Map.Entry<String, String> entry : defaultDimNameMap.entrySet()) {
            String defaultDimensionFiledName = entry.getKey();
            // 用户指定了维度值（WHERE中）→ 排除默认值
            if (effectiveWhereDimBizNames.contains(defaultDimensionFiledName)) {
                SqlIdentifier column =
                        new SqlIdentifier(Arrays.asList(defaultDimensionFiledName), pos);
                SqlCharStringLiteral value = SqlLiteral.createCharString(entry.getValue(), pos);
                SqlNode notEqualsCall =
                        SqlStdOperatorTable.NOT_EQUALS.createCall(pos, column, value);
                andConditions.add(notEqualsCall);
                // 用户未指定维度值 → 设为默认值筛选条件，联级关系则跳出
            } else if (!hasProvinceCityRelation(defaultDimensionFiledName,
                    effectiveWhereDimBizNames)
                    && !hasChildCondtion(defaultDimensionFiledName, effectiveWhereDimBizNames,
                            childCondtionList)
                    && !hasSiblingCondition(defaultDimensionFiledName, effectiveWhereDimBizNames,
                            siblingCondtionList)) {

                SqlIdentifier column =
                        new SqlIdentifier(Arrays.asList(defaultDimensionFiledName), pos);
                SqlCharStringLiteral value = SqlLiteral.createCharString(entry.getValue(), pos);
                SqlNode columnCondition = SqlStdOperatorTable.EQUALS.createCall(pos, column, value);
                andConditions.add(columnCondition);
            }
        }
        if (CollectionUtils.isEmpty(andConditions)) {
            return null;
        }
        return produceAndConditions(andConditions);

    }

    private static boolean hasSiblingCondition(String defaultDimensionFiledName,
            Set<String> filterNameList, List<String> siblingRelations) {
        if (CollectionUtils.isEmpty(siblingRelations)) {
            return false;
        }

        // 遍历所有同级维度关系配置
        for (String relation : siblingRelations) {
            // 按照 "/" 分割同级维度关系
            List<String> relationDims = Arrays.asList(relation.split("/"));

            // 如果当前维度在此关系中
            if (relationDims.contains(defaultDimensionFiledName)) {
                // 检查是否有其他同级维度出现在查询条件中
                for (String siblingDim : relationDims) {
                    if (!siblingDim.equals(defaultDimensionFiledName)
                            && filterNameList.contains(siblingDim)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }


    private static boolean isQuoteProvinceTop(String dimensionFiledName) {
        if (!dimensionFiledName.startsWith("province")) {
            return false;
        }
        return false;


    }

    private static boolean hasChildCondtion(String defaultDimensionFiledName,
            Set<String> filterNameList, List<String> dimRelations) {
        if (CollectionUtils.isEmpty(dimRelations)) {
            return false;
        }

        // 1.判断是否属于层级分类维度
        String dimRelation = dimRelations.stream()
                .filter(dr -> dr.contains(defaultDimensionFiledName)).findFirst().orElse(null);
        if (null == dimRelation) {
            return false;
        }
        if (!dimRelation.contains(defaultDimensionFiledName)) {
            return false;
        }
        // 2.判断下级分类条件是否存在
        List<String> dimRelationList = Arrays.asList(dimRelation.split("/"));

        int levelIndex = dimRelationList.indexOf(defaultDimensionFiledName);
        if (levelIndex == dimRelationList.size() - 1) {
            return false;
        }
        List<String> childList = dimRelationList.subList(levelIndex + 1, dimRelationList.size());
        // 3.判断子维度是否在查询条件中

        return CollectionUtils.isNotEmpty(CollectionUtils.intersection(childList, filterNameList));

    }

    private static SqlNode produceAndConditions(List<SqlNode> andConditions) {
        SqlNode left = andConditions.get(0);
        for (int i = 1; i <= andConditions.size() - 1; i++) {
            SqlNode right = andConditions.get(i);
            SqlNode and = SqlStdOperatorTable.AND.createCall(SqlParserPos.ZERO, left, right);
            left = and;
        }
        return left;

    }

    // 问题的条件中包含城市，即使条件不包含省份，也不能添加不能加省份='全国'的默认条件,为ture代表是 问城市&&检查省份的情况
    public static boolean hasProvinceCityRelation(String dimensionName,
            Set<String> filterNameList) {
        if (!(StringUtils.equalsIgnoreCase(dimensionName, "provinceName")
                || StringUtils.equalsIgnoreCase(dimensionName, "province_name")
                || StringUtils.equalsIgnoreCase(dimensionName, "province"))) {
            return false;
        }
        return filterNameList.stream()
                .filter(name -> StringUtils.equalsIgnoreCase(name, "city_name")
                        || StringUtils.equalsIgnoreCase(name, "cityName")
                        || StringUtils.equalsIgnoreCase(name, "city")
                        || StringUtils.equalsIgnoreCase(name, "城市")
                        || StringUtils.equalsIgnoreCase(name, "城市名称")
                        || StringUtils.equalsIgnoreCase(name, "地市")
                        || StringUtils.equalsIgnoreCase(name, "地市名称"))
                .count() > 0;
    }

    private static boolean isDimension(String name, ModelResp dataModel, S2CalciteSchema schema) {
        Optional<Dimension> dimension = dataModel.getModelDetail().getDimensions().stream()
                .filter(d -> d.getName().equalsIgnoreCase(name)).findFirst();
        if (dimension.isPresent()) {
            return true;
        }
        Optional<Identify> identify = dataModel.getIdentifiers().stream()
                .filter(i -> i.getName().equalsIgnoreCase(name)).findFirst();
        if (identify.isPresent()) {
            return true;
        }
        if (schema.getDimensions().containsKey(dataModel.getName())) {
            Optional<DimSchemaResp> dataSourceDim = schema.getDimensions().get(dataModel.getName())
                    .stream().filter(d -> d.getName().equalsIgnoreCase(name)).findFirst();
            if (dataSourceDim.isPresent()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isForeign(String name, List<Identify> identifies) {
        Optional<Identify> identify =
                identifies.stream().filter(i -> i.getName().equalsIgnoreCase(name)).findFirst();
        if (identify.isPresent()) {
            return IdentifyType.foreign.equals(identify.get().getType());
        }
        return false;
    }

    private static boolean isPrimary(String name, List<Identify> identifies) {
        Optional<Identify> identify =
                identifies.stream().filter(i -> i.getName().equalsIgnoreCase(name)).findFirst();
        if (identify.isPresent()) {
            return IdentifyType.primary.equals(identify.get().getType());
        }
        return false;
    }

    /**
     * 创建标识符节点。如果标识符包含中文、特殊字符或不符合 Calcite 未引用标识符规则， 则使用反引号包裹，避免 Calcite 解析失败。
     *
     * @param name 标识符名称
     * @param pos 解析位置
     * @return 标识符节点
     */
    private static SqlIdentifier createIdentifier(String name, SqlParserPos pos,
            EngineType engineType) {
        if (StringUtils.isBlank(name) || isSimpleIdentifier(name)) {
            return new SqlIdentifier(Arrays.asList(name), pos);
        }
        try {
            // 对反引号本身做转义，避免注入问题
            String quoted = "`" + name.replace("`", "``") + "`";
            SqlNode parsed = SqlParser.create(quoted,
                    Configuration.getParserConfig(engineType)).parseExpression();
            if (parsed instanceof SqlIdentifier) {
                return (SqlIdentifier) parsed;
            }
        } catch (SqlParseException e) {
            log.warn("Failed to parse quoted identifier: {}", name, e);
        }
        // 兜底：按原方式创建
        return new SqlIdentifier(Arrays.asList(name), pos);
    }

    /**
     * 判断是否为 Calcite 可直接识别的未引用标识符： 以字母或下划线开头，后续仅包含字母、数字、下划线。
     *
     * @param name 标识符名称
     * @return true 表示不需要加引号
     */
    private static boolean isSimpleIdentifier(String name) {
        return name.matches("^[a-zA-Z_][a-zA-Z0-9_]*$");
    }

    /**
     * 对 SQL 表达式中的常见全角符号做半角转换。
     * 只处理 SQL 语法符号（括号、逗号、算数符等），不处理中文文字。
     * 防止 Calcite 解析 expr 时遇到全角符号报词法错误。
     */
    private static String normalizeExprSymbols(String expr) {
        if (StringUtils.isBlank(expr)) {
            return expr;
        }
        return expr.replace('（', '(').replace('）', ')').replace('，', ',')
                .replace('；', ';').replace('＝', '=').replace('＞', '>')
                .replace('＜', '<').replace('＋', '+').replace('－', '-')
                .replace('＊', '*').replace('／', '/');
    }

    /**
     * 为表达式创建别名节点
     * 
     * @param expr 原始表达式
     * @param aliasName 别名
     * @param pos 解析位置
     * @return 带别名的表达式节点
     */
    public static SqlNode createAlias(String expr, String aliasName, SqlParserPos pos,
            EngineType engineType) throws SqlParseException {

        expr = normalizeExprSymbols(expr);
        String exprPlusSql = "select " + expr + " from dual";
        SqlNode parsedNode = SqlParser.create(exprPlusSql,
                Configuration.getParserConfig(engineType)).parseQuery();
        // 提取表达式部分
        // 这里对内层sql的表达式做了别名，与外层的表达式保持一致，如果此处变动，外层表达式（getDimensionExpressions方法）也要变动
        if (parsedNode instanceof SqlSelect) {
            SqlSelect select = (SqlSelect) parsedNode;
            SqlNodeList selectList = select.getSelectList();
            if (selectList.size() > 0) {
                SqlNode exprNode = selectList.get(0);
                // 创建别名标识符
                SqlIdentifier alias = createIdentifier(aliasName, pos, engineType);

                // 使用AS操作符创建带别名的表达式
                return SqlStdOperatorTable.AS.createCall(pos, exprNode, alias);
            }
        }
        return null;
    }

    public static SqlNode createColumn(String name, SqlParserPos pos, EngineType engineType) {
        // 创建别名标识符
        SqlIdentifier column = createIdentifier(name, pos, engineType);

        // 使用AS操作符创建带别名的表达式
        return column;
    }
}
