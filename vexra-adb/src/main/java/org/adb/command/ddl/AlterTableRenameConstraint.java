/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.command.ddl;

import org.adb.api.ErrorCode;
import org.adb.command.CommandInterface;
import org.adb.constraint.Constraint;
import org.adb.constraint.Constraint.Type;
import org.adb.engine.Database;
import org.adb.engine.Right;
import org.adb.engine.SessionLocal;
import org.adb.engine.User;
import org.adb.message.DbException;
import org.adb.schema.Schema;
import org.adb.table.Table;

/**
 * This class represents the statement
 * ALTER TABLE RENAME CONSTRAINT
 */
public class AlterTableRenameConstraint extends AlterTable {

    private String constraintName;
    private String newConstraintName;

    public AlterTableRenameConstraint(SessionLocal session, Schema schema) {
        super(session, schema);
    }

    public void setConstraintName(String string) {
        constraintName = string;
    }

    public void setNewConstraintName(String newName) {
        this.newConstraintName = newName;
    }

    @Override
    public long update(Table table) {
        Constraint constraint = getSchema().findConstraint(session, constraintName);
        Database db = getDatabase();
        if (constraint == null || constraint.getConstraintType() == Type.DOMAIN || constraint.getTable() != table) {
            throw DbException.get(ErrorCode.CONSTRAINT_NOT_FOUND_1, constraintName);
        }
        if (getSchema().findConstraint(session, newConstraintName) != null
                || newConstraintName.equals(constraintName)) {
            throw DbException.get(ErrorCode.CONSTRAINT_ALREADY_EXISTS_1, newConstraintName);
        }
        User user = session.getUser();
        Table refTable = constraint.getRefTable();
        if (refTable != table) {
            user.checkTableRight(refTable, Right.SCHEMA_OWNER);
        }
        db.renameSchemaObject(session, constraint, newConstraintName);
        return 0;
    }

    @Override
    public int getType() {
        return CommandInterface.ALTER_TABLE_RENAME_CONSTRAINT;
    }

}
