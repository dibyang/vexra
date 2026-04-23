/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.command.ddl;

import java.util.ArrayList;

import org.adb.api.ErrorCode;
import org.adb.command.CommandInterface;
import org.adb.constraint.Constraint;
import org.adb.engine.Database;
import org.adb.engine.Right;
import org.adb.engine.SessionLocal;
import org.adb.index.Index;
import org.adb.message.DbException;
import org.adb.schema.Schema;
import org.adb.table.Table;

/**
 * This class represents the statement
 * DROP INDEX
 */
public class DropIndex extends SchemaCommand {

    private String indexName;
    private boolean ifExists;

    public DropIndex(SessionLocal session, Schema schema) {
        super(session, schema);
    }

    public void setIfExists(boolean b) {
        ifExists = b;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    @Override
    public long update() {
        Database db = getDatabase();
        Index index = getSchema().findIndex(session, indexName);
        if (index == null) {
            if (!ifExists) {
                throw DbException.get(ErrorCode.INDEX_NOT_FOUND_1, indexName);
            }
        } else {
            Table table = index.getTable();
            session.getUser().checkTableRight(index.getTable(), Right.SCHEMA_OWNER);
            Constraint pkConstraint = null;
            ArrayList<Constraint> constraints = table.getConstraints();
            for (int i = 0; constraints != null && i < constraints.size(); i++) {
                Constraint cons = constraints.get(i);
                if (cons.usesIndex(index)) {
                    // can drop primary key index (for compatibility)
                    if (Constraint.Type.PRIMARY_KEY == cons.getConstraintType()) {
                        for (Constraint c : constraints) {
                            if (c.getReferencedConstraint() == cons) {
                                throw DbException.get(ErrorCode.INDEX_BELONGS_TO_CONSTRAINT_2, indexName,
                                        cons.getName());
                            }
                        }
                        pkConstraint = cons;
                    } else {
                        throw DbException.get(ErrorCode.INDEX_BELONGS_TO_CONSTRAINT_2, indexName, cons.getName());
                    }
                }
            }
            index.getTable().setModified();
            if (pkConstraint != null) {
                db.removeSchemaObject(session, pkConstraint);
            } else {
                db.removeSchemaObject(session, index);
            }
        }
        return 0;
    }

    @Override
    public int getType() {
        return CommandInterface.DROP_INDEX;
    }

}
