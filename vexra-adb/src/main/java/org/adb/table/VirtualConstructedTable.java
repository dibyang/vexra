/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.table;

import org.adb.engine.SessionLocal;
import org.adb.index.Index;
import org.adb.index.VirtualConstructedTableIndex;
import org.adb.result.ResultInterface;
import org.adb.schema.Schema;

/**
 * A base class for virtual tables that construct all their content at once.
 */
public abstract class VirtualConstructedTable extends VirtualTable {

    protected VirtualConstructedTable(Schema schema, int id, String name) {
        super(schema, id, name);
    }

    /**
     * Read the rows from the table.
     *
     * @param session
     *            the session
     * @return the result
     */
    public abstract ResultInterface getResult(SessionLocal session);

    @Override
    public Index getScanIndex(SessionLocal session) {
        return new VirtualConstructedTableIndex(this, IndexColumn.wrap(columns));
    }

    @Override
    public long getMaxDataModificationId() {
        // TODO optimization: virtual table currently doesn't know the
        // last modified date
        return Long.MAX_VALUE;
    }

}
