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
import org.adb.message.DbException;
import org.adb.schema.Schema;
import org.adb.schema.TriggerObject;
import org.adb.table.Table;

/**
 * This class represents the statement
 * DROP TRIGGER
 */
public class DropTrigger extends SchemaCommand {

    private String triggerName;
    private boolean ifExists;

    public DropTrigger(SessionLocal session, Schema schema) {
        super(session, schema);
    }

    public void setIfExists(boolean b) {
        ifExists = b;
    }

    public void setTriggerName(String triggerName) {
        this.triggerName = triggerName;
    }

    @Override
    public long update() {
        Database db = getDatabase();
        TriggerObject trigger = getSchema().findTrigger(triggerName);
        if (trigger == null) {
            if (!ifExists) {
                throw DbException.get(ErrorCode.TRIGGER_NOT_FOUND_1, triggerName);
            }
        } else {
            Table table = trigger.getTable();
            session.getUser().checkTableRight(table, Right.SCHEMA_OWNER);
            db.removeSchemaObject(session, trigger);
        }
        return 0;
    }

    @Override
    public int getType() {
        return CommandInterface.DROP_TRIGGER;
    }

}
