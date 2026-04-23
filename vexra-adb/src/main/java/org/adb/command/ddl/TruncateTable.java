/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.command.ddl;

import org.adb.api.ErrorCode;
import org.adb.command.CommandInterface;
import org.adb.engine.Right;
import org.adb.engine.SessionLocal;
import org.adb.message.DbException;
import org.adb.schema.Sequence;
import org.adb.table.Column;
import org.adb.table.Table;

/**
 * This class represents the statement
 * TRUNCATE TABLE
 */
public class TruncateTable extends DefineCommand {

    private Table table;

    private boolean restart;

    public TruncateTable(SessionLocal session) {
        super(session);
    }

    public void setTable(Table table) {
        this.table = table;
    }

    public void setRestart(boolean restart) {
        this.restart = restart;
    }

    @Override
    public long update() {
        if (!table.canTruncate()) {
            throw DbException.get(ErrorCode.CANNOT_TRUNCATE_1, table.getTraceSQL());
        }
        session.getUser().checkTableRight(table, Right.DELETE);
        table.lock(session, Table.EXCLUSIVE_LOCK);
        long result = table.truncate(session);
        if (restart) {
            for (Column column : table.getColumns()) {
                Sequence sequence = column.getSequence();
                if (sequence != null) {
                    sequence.modify(sequence.getStartValue(), null, null, null, null, null, null);
                    getDatabase().updateMeta(session, sequence);
                }
            }
        }
        return result;
    }

    @Override
    public int getType() {
        return CommandInterface.TRUNCATE_TABLE;
    }

}
