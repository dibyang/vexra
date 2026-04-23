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
import org.adb.constraint.ConstraintDomain;
import org.adb.engine.SessionLocal;
import org.adb.message.DbException;
import org.adb.schema.Domain;
import org.adb.schema.Schema;

/**
 * This class represents the statement ALTER DOMAIN DROP CONSTRAINT
 */
public class AlterDomainDropConstraint extends AlterDomain {

    private String constraintName;
    private final boolean ifConstraintExists;

    public AlterDomainDropConstraint(SessionLocal session, Schema schema, boolean ifConstraintExists) {
        super(session, schema);
        this.ifConstraintExists = ifConstraintExists;
    }

    public void setConstraintName(String string) {
        constraintName = string;
    }

    @Override
    long update(Schema schema, Domain domain) {
        Constraint constraint = schema.findConstraint(session, constraintName);
        if (constraint == null || constraint.getConstraintType() != Type.DOMAIN
                || ((ConstraintDomain) constraint).getDomain() != domain) {
            if (!ifConstraintExists) {
                throw DbException.get(ErrorCode.CONSTRAINT_NOT_FOUND_1, constraintName);
            }
        } else {
            getDatabase().removeSchemaObject(session, constraint);
        }
        return 0;
    }

    @Override
    public int getType() {
        return CommandInterface.ALTER_DOMAIN_DROP_CONSTRAINT;
    }

}
