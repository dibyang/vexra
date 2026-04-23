/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.expression.condition;

import java.util.Arrays;

import org.adb.command.query.Query;
import org.adb.engine.NullsDistinct;
import org.adb.engine.SessionLocal;
import org.adb.expression.Expression;
import org.adb.expression.ValueExpression;
import org.adb.result.LocalResult;
import org.adb.result.ResultTarget;
import org.adb.value.Value;
import org.adb.value.ValueBoolean;
import org.adb.value.ValueNull;

/**
 * Unique predicate as in UNIQUE(SELECT ...)
 */
public class UniquePredicate extends PredicateWithSubquery {

    private static final class Target implements ResultTarget {

        private final int columnCount;

        private final NullsDistinct nullsDistinct;

        private final LocalResult result;

        boolean hasDuplicates;

        Target(int columnCount, NullsDistinct nullsDistinct, LocalResult result) {
            this.columnCount = columnCount;
            this.nullsDistinct = nullsDistinct;
            this.result = result;
        }

        @Override
        public void limitsWereApplied() {
            // Nothing to do
        }

        @Override
        public long getRowCount() {
            // Not required
            return 0L;
        }

        @Override
        public void addRow(Value... values) {
            if (hasDuplicates) {
                return;
            }
            check: switch (nullsDistinct) {
            case DISTINCT:
                for (int i = 0; i < columnCount; i++) {
                    if (values[i] == ValueNull.INSTANCE) {
                        return;
                    }
                }
                break;
            case ALL_DISTINCT:
                for (int i = 0; i < columnCount; i++) {
                    if (values[i] != ValueNull.INSTANCE) {
                        break check;
                    }
                }
                return;
            default:
            }
            if (values.length != columnCount) {
                values = Arrays.copyOf(values, columnCount);
            }
            long expected = result.getRowCount() + 1;
            result.addRow(values);
            if (expected != result.getRowCount()) {
                hasDuplicates = true;
                result.close();
            }
        }
    }

    private final NullsDistinct nullsDistinct;

    public UniquePredicate(Query query, NullsDistinct nullsDistinct) {
        super(query);
        this.nullsDistinct = nullsDistinct;
    }

    @Override
    public Expression optimize(SessionLocal session) {
        super.optimize(session);
        if (query.isStandardDistinct()) {
            return ValueExpression.TRUE;
        }
        return this;
    }

    @Override
    public Value getValue(SessionLocal session) {
        query.setSession(session);
        int columnCount = query.getColumnCount();
        LocalResult result = new LocalResult(session,
                query.getExpressions().toArray(new Expression[0]), columnCount, columnCount);
        result.setDistinct();
        Target target = new Target(columnCount, nullsDistinct, result);
        query.query(Integer.MAX_VALUE, target);
        result.close();
        return ValueBoolean.get(!target.hasDuplicates);
    }

    @Override
    public StringBuilder getUnenclosedSQL(StringBuilder builder, int sqlFlags) {
        builder.append("UNIQUE");
        if (nullsDistinct != NullsDistinct.DISTINCT) {
            nullsDistinct.getSQL(builder.append(' '), 0);
        }
        return super.getUnenclosedSQL(builder, sqlFlags);
    }

}
