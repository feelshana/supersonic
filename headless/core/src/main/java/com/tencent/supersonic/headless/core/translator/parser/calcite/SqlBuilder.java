package com.tencent.supersonic.headless.core.translator.parser.calcite;

import com.google.common.collect.Sets;
import com.tencent.supersonic.common.calcite.Configuration;
import com.tencent.supersonic.common.jsqlparser.FieldExpression;
import com.tencent.supersonic.common.jsqlparser.SqlAddHelper;
import com.tencent.supersonic.common.jsqlparser.SqlSelectHelper;
import com.tencent.supersonic.common.pojo.enums.EngineType;
import com.tencent.supersonic.headless.api.pojo.Dimension;
import com.tencent.supersonic.headless.api.pojo.Identify;
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

    public static String createTableSql(SemanticSchemaResp semanticSchema) throws Exception {
        List<SqlNode> selectList = new ArrayList<>();
        for (DimSchemaResp dimSchemaResp : semanticSchema.getDimensions()) {
            if (StringUtils.isNotBlank(dimSchemaResp.getExpr()) && !StringUtils
                    .equalsIgnoreCase(dimSchemaResp.getExpr(), dimSchemaResp.getBizName())) {
                selectList
                        .add(createAlias(dimSchemaResp.getExpr(), dimSchemaResp.getBizName(), pos));
            } else {
                selectList.add(createColumn(dimSchemaResp.getBizName(), pos));
            }

        }
        for (MetricSchemaResp metricSchemaResp : semanticSchema.getMetrics()) {
            if (StringUtils.isBlank(metricSchemaResp.getExpr())) {
                selectList.add(createAlias(metricSchemaResp.getExpr(),
                        metricSchemaResp.getBizName(), pos));
            } else {
                selectList.add(createColumn(metricSchemaResp.getBizName(), pos));
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
            List<String> dimensionRelations) throws Exception {
        SqlNode left = null;
        TableView leftTable = null;
        TableView outerTable = new TableView();
        // Map<String, SqlNode> outerSelect = new HashMap<>();
        Map<String, String> beforeModels = new HashMap<>();
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
                    semanticSchema, dimensionRelations);
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
            List<String> dimensionRelations) {
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
            tableView.setWhere(
                    extractDefaultDimValue(semanticSchema, queryDimensions, dimensionRelations));
        } catch (Exception e) {
            log.error("Failed to create sqlNode for table,tableQuery:{},SqlQuery:{}",
                    dataModel.getModelDetail().getTableQuery(),
                    dataModel.getModelDetail().getSqlQuery(), e);
        }

        return tableView;
    }

    private static SqlNode extractDefaultDimValue(SemanticSchemaResp semanticSchema,
            Set<DimSchemaResp> dimSchemaRespSet, List<String> dimensionRelations) {
        Map<String, String> defaultDimNameMap = semanticSchema.getDimensions().stream()
                .filter(dimSchemaResp -> !org.springframework.util.CollectionUtils
                        .isEmpty(dimSchemaResp.getDefaultValues()))
                .collect(Collectors.toMap(dimSchemaResp -> dimSchemaResp.getBizName(),
                        dimSchemaResp -> dimSchemaResp.getDefaultValues().get(0)));
        if (null == defaultDimNameMap || defaultDimNameMap.isEmpty()) {
            return null;
        }
        Set<String> filterNameList = dimSchemaRespSet.stream().map(DimSchemaResp::getBizName)
                .collect(Collectors.toSet());
        List<SqlNode> andConditions = new ArrayList<>();

        // 判断是否存在维度的维度值和默认值是一致的，存在就剔除筛选，不存在就继续后续的判断。
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
            filterNameList.removeAll(defaultDimFilterNameList);
            semanticSchema.getSegmentDimBizNames().removeAll(defaultDimFilterNameList);
        }
        log.info("需要进行默认值排除的维度:{}", filterNameList);
        log.info("用户问题涉及的维度:{}", semanticSchema.getSegmentDimBizNames());
        for (Map.Entry<String, String> entry : defaultDimNameMap.entrySet()) {
            String defaultDimensionFiledName = entry.getKey();
            if (filterNameList.contains(defaultDimensionFiledName)
                    || semanticSchema.getSegmentDimBizNames().contains(defaultDimensionFiledName)) {
                SqlIdentifier column =
                        new SqlIdentifier(Arrays.asList(defaultDimensionFiledName), pos);
                SqlCharStringLiteral value = SqlLiteral.createCharString(entry.getValue(), pos);
                SqlNode notEqualsCall =
                        SqlStdOperatorTable.NOT_EQUALS.createCall(pos, column, value);
                andConditions.add(notEqualsCall);
            } else if ((!filterNameList.contains(defaultDimensionFiledName))
                    && !hasProvinceCityRelation(defaultDimensionFiledName, filterNameList)
                    && !hasChildCondtion(defaultDimensionFiledName, filterNameList,
                            dimensionRelations)) {

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
                        || StringUtils.equalsIgnoreCase(name, "city"))
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
     * 为表达式创建别名节点
     * 
     * @param expr 原始表达式
     * @param aliasName 别名
     * @param pos 解析位置
     * @return 带别名的表达式节点
     */
    public static SqlNode createAlias(String expr, String aliasName, SqlParserPos pos)
            throws SqlParseException {

        String exprPlusSql = "select " + expr + " from dual";
        SqlNode parsedNode = SqlParser.create(exprPlusSql).parseQuery();
        // 提取表达式部分
        if (parsedNode instanceof SqlSelect) {
            SqlSelect select = (SqlSelect) parsedNode;
            SqlNodeList selectList = select.getSelectList();
            if (selectList.size() > 0) {
                SqlNode exprNode = selectList.get(0);
                // 创建别名标识符
                SqlIdentifier alias = new SqlIdentifier(Arrays.asList(aliasName), pos);

                // 使用AS操作符创建带别名的表达式
                return SqlStdOperatorTable.AS.createCall(pos, exprNode, alias);
            }
        }
        return null;
    }

    public static SqlNode createColumn(String name, SqlParserPos pos) {
        // 创建别名标识符
        SqlIdentifier column = new SqlIdentifier(Arrays.asList(name), pos);

        // 使用AS操作符创建带别名的表达式
        return column;
    }
}
