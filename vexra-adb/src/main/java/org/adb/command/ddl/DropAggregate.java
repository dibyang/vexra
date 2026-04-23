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
import org.adb.schema.UserAggregate;

/**
 * This class represents the statement
 * DROP AGGREGATE
 */
public class DropAggregate extends SchemaOwnerCommand {

    private String name;
    private boolean ifExists;

    public DropAggregate(SessionLocal session, Schema schema) {
        super(session, schema);
    }

    @Override
    long update(Schema schema) {
        Database db = getDatabase();
        UserAggregate aggregate = schema.findAggregate(name);
        if (aggregate == null) {
            if (!ifExists) {
                throw DbException.get(ErrorCode.AGGREGATE_NOT_FOUND_1, name);
            }
        } else {
            db.removeSchemaObject(session, aggregate);
        }
        return 0;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setIfExists(boolean ifExists) {
        this.ifExists = ifExists;
    }

    @Override
    public int getType() {
        return CommandInterface.DROP_AGGREGATE;
    }

}
