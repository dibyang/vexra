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
import org.adb.schema.FunctionAlias;
import org.adb.schema.Schema;

/**
 * This class represents the statement
 * DROP ALIAS
 */
public class DropFunctionAlias extends SchemaOwnerCommand {

    private String aliasName;
    private boolean ifExists;

    public DropFunctionAlias(SessionLocal session, Schema schema) {
        super(session, schema);
    }

    @Override
    long update(Schema schema) {
        Database db = getDatabase();
        FunctionAlias functionAlias = schema.findFunction(aliasName);
        if (functionAlias == null) {
            if (!ifExists) {
                throw DbException.get(ErrorCode.FUNCTION_ALIAS_NOT_FOUND_1, aliasName);
            }
        } else {
            db.removeSchemaObject(session, functionAlias);
        }
        return 0;
    }

    public void setAliasName(String name) {
        this.aliasName = name;
    }

    public void setIfExists(boolean ifExists) {
        this.ifExists = ifExists;
    }

    @Override
    public int getType() {
        return CommandInterface.DROP_ALIAS;
    }

}
