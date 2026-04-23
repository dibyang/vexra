/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.command.ddl;

import org.adb.api.ErrorCode;
import org.adb.command.CommandInterface;
import org.adb.engine.Database;
import org.adb.engine.RightOwner;
import org.adb.engine.Role;
import org.adb.engine.SessionLocal;
import org.adb.message.DbException;

/**
 * This class represents the statement
 * CREATE ROLE
 */
public class CreateRole extends DefineCommand {

    private String roleName;
    private boolean ifNotExists;

    public CreateRole(SessionLocal session) {
        super(session);
    }

    public void setIfNotExists(boolean ifNotExists) {
        this.ifNotExists = ifNotExists;
    }

    public void setRoleName(String name) {
        this.roleName = name;
    }

    @Override
    public long update() {
        session.getUser().checkAdmin();
        Database db = getDatabase();
        RightOwner rightOwner = db.findUserOrRole(roleName);
        if (rightOwner != null) {
            if (rightOwner instanceof Role) {
                if (ifNotExists) {
                    return 0;
                }
                throw DbException.get(ErrorCode.ROLE_ALREADY_EXISTS_1, roleName);
            }
            throw DbException.get(ErrorCode.USER_ALREADY_EXISTS_1, roleName);
        }
        int id = getObjectId();
        Role role = new Role(db, id, roleName, false);
        db.addDatabaseObject(session, role);
        return 0;
    }

    @Override
    public int getType() {
        return CommandInterface.CREATE_ROLE;
    }

}
