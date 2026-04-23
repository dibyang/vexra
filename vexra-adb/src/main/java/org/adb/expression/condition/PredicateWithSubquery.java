/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.expression.condition;

import org.adb.command.query.Query;
import org.adb.engine.SessionLocal;
import org.adb.expression.Expression;
import org.adb.expression.ExpressionVisitor;
import org.adb.table.ColumnResolver;
import org.adb.table.TableFilter;
import org.adb.util.StringUtils;

/**
 * Base class for predicates with a subquery.
 */
abstract class PredicateWithSubquery extends Condition {

    /**
     * The subquery.
     */
    final Query query;

    PredicateWithSubquery(Query query) {
        this.query = query;
    }

    @Override
    public void mapColumns(ColumnResolver resolver, int level, int state) {
        query.mapColumns(resolver, level + 1);
    }

    @Override
    public Expression optimize(SessionLocal session) {
        query.prepare();
        return this;
    }

    @Override
    public void setEvaluatable(TableFilter tableFilter, boolean value) {
        query.setEvaluatable(tableFilter, value);
    }

    @Override
    public StringBuilder getUnenclosedSQL(StringBuilder builder, int sqlFlags) {
        return StringUtils.indent(builder.append('('), query.getPlanSQL(sqlFlags), 4, false).append(')');
    }

    @Override
    public void updateAggregate(SessionLocal session, int stage) {
        query.updateAggregate(session, stage);
    }

    @Override
    public boolean isEverything(ExpressionVisitor visitor) {
        return query.isEverything(visitor);
    }

    @Override
    public int getCost() {
        return query.getCostAsExpression();
    }

}
