package com.tencent.supersonic.common.jsqlparser;

import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.FromItemVisitorAdapter;
import org.apache.commons.lang3.StringUtils;

import java.util.Set;

public class TableNameReplaceVisitor extends FromItemVisitorAdapter {

    private Set<String> notReplaceTables;
    private String tableName;
    private Set<String> tableWithAlias;

    public TableNameReplaceVisitor(String tableName, Set<String> notReplaceTables,
            Set<String> tableWithAlias) {
        this.tableName = tableName;
        this.notReplaceTables = notReplaceTables;
        this.tableWithAlias = tableWithAlias;
    }

    @Override
    public void visit(Table table) {
        if (notReplaceTables.contains(table.getName())
                || StringUtils.endsWithIgnoreCase(table.getName(), "dual")) {
            return;
        }
        table.setName(tableName);
        if (null != table.getAlias()) {
            tableWithAlias.add(tableName);
        }
    }
}
