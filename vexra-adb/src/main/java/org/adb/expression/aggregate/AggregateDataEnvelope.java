/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.expression.aggregate;

import java.util.ArrayList;

import org.adb.engine.SessionLocal;
import org.adb.expression.Expression;
import org.adb.expression.ExpressionColumn;
import org.adb.index.Index;
import org.adb.mvstore.db.MVSpatialIndex;
import org.adb.table.Column;
import org.adb.table.TableFilter;
import org.adb.util.geometry.GeometryUtils;
import org.adb.value.Value;
import org.adb.value.ValueGeometry;
import org.adb.value.ValueNull;

/**
 * Data stored while calculating an aggregate.
 */
final class AggregateDataEnvelope extends AggregateData {

    private double[] envelope;

    /**
     * Get the index (if any) for the column specified in the geometry
     * aggregate.
     *
     * @param on
     *            the expression (usually a column expression)
     * @return the index, or null
     */
    static Index getGeometryColumnIndex(Expression on) {
        if (on instanceof ExpressionColumn) {
            ExpressionColumn col = (ExpressionColumn) on;
            Column column = col.getColumn();
            if (column.getType().getValueType() == Value.GEOMETRY) {
                TableFilter filter = col.getTableFilter();
                if (filter != null) {
                    ArrayList<Index> indexes = filter.getTable().getIndexes();
                    if (indexes != null) {
                        for (int i = 1, size = indexes.size(); i < size; i++) {
                            Index index = indexes.get(i);
                            if (index instanceof MVSpatialIndex && index.isFirstColumn(column)) {
                                return index;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    @Override
    void add(SessionLocal session, Value v) {
        if (v == ValueNull.INSTANCE) {
            return;
        }
        envelope = GeometryUtils.union(envelope, v.convertToGeometry(null).getEnvelopeNoCopy());
    }

    @Override
    Value getValue(SessionLocal session) {
        return ValueGeometry.fromEnvelope(envelope);
    }

}
