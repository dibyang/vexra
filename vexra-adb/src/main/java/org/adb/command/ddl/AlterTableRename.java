/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.command.ddl;

import org.adb.api.ErrorCode;
import org.adb.command.CommandInterface;
import org.adb.engine.Database;
import org.adb.engine.SessionLocal;
import org.adb.message.DbException;
import org.adb.schema.Schema;
import org.adb.table.Table;

/**
 * This class represents the statement
 * ALTER TABLE RENAME
 */
public class AlterTableRename extends AlterTable {

    private String newTableName;
    private boolean hidden;

    public AlterTableRename(SessionLocal session, Schema schema) {
        super(session, schema);
    }

    public void setNewTableName(String name) {
        newTableName = name;
    }

    @Override
    public long update(Table table) {
        Database db = getDatabase();
        Table t = getSchema().findTableOrView(session, newTableName);
        if (t != null && hidden && newTableName.equals(table.getName())) {
            if (!t.isHidden()) {
                t.setHidden(hidden);
                table.setHidden(true);
                db.updateMeta(session, table);
            }
            return 0;
        }
        if (t != null || newTableName.equals(table.getName())) {
            throw DbException.get(ErrorCode.TABLE_OR_VIEW_ALREADY_EXISTS_1, newTableName);
        }
        if (table.isTemporary()) {
            throw DbException.getUnsupportedException("temp table");
        }
        db.renameSchemaObject(session, table, newTableName);
        return 0;
    }

    @Override
    public int getType() {
        return CommandInterface.ALTER_TABLE_RENAME;
    }

    public void setHidden(boolean hidden) {
        this.hidden = hidden;
    }

}
