/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.command.ddl;

import org.adb.api.ErrorCode;
import org.adb.command.CommandInterface;
import org.adb.engine.Database;
import org.adb.engine.Right;
import org.adb.engine.SessionLocal;
import org.adb.index.Index;
import org.adb.message.DbException;
import org.adb.schema.Schema;

/**
 * This class represents the statement
 * ALTER INDEX RENAME
 */
public class AlterIndexRename extends DefineCommand {

    private boolean ifExists;
    private Schema oldSchema;
    private String oldIndexName;
    private String newIndexName;

    public AlterIndexRename(SessionLocal session) {
        super(session);
    }

    public void setIfExists(boolean b) {
        ifExists = b;
    }

    public void setOldSchema(Schema old) {
        oldSchema = old;
    }

    public void setOldName(String name) {
        oldIndexName = name;
    }

    public void setNewName(String name) {
        newIndexName = name;
    }

    @Override
    public long update() {
        Database db = getDatabase();
        Index oldIndex = oldSchema.findIndex(session, oldIndexName);
        if (oldIndex == null) {
            if (!ifExists) {
                throw DbException.get(ErrorCode.INDEX_NOT_FOUND_1,
                        newIndexName);
            }
            return 0;
        }
        if (oldSchema.findIndex(session, newIndexName) != null ||
                newIndexName.equals(oldIndexName)) {
            throw DbException.get(ErrorCode.INDEX_ALREADY_EXISTS_1,
                    newIndexName);
        }
        session.getUser().checkTableRight(oldIndex.getTable(), Right.SCHEMA_OWNER);
        db.renameSchemaObject(session, oldIndex, newIndexName);
        return 0;
    }

    @Override
    public int getType() {
        return CommandInterface.ALTER_INDEX_RENAME;
    }

}
