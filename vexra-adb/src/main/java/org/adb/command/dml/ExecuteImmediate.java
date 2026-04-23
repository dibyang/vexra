/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.command.dml;

import org.adb.api.ErrorCode;
import org.adb.command.CommandInterface;
import org.adb.command.Prepared;
import org.adb.engine.SessionLocal;
import org.adb.expression.Expression;
import org.adb.message.DbException;
import org.adb.result.ResultInterface;

/**
 * This class represents the statement
 * EXECUTE IMMEDIATE.
 */
public class ExecuteImmediate extends Prepared {

    private Expression statement;

    public ExecuteImmediate(SessionLocal session, Expression statement) {
        super(session);
        this.statement = statement.optimize(session);
    }

    @Override
    public long update() {
        String sql = statement.getValue(session).getString();
        if (sql == null) {
            throw DbException.getInvalidValueException("SQL command", null);
        }
        Prepared command = session.prepare(sql);
        if (command.isQuery()) {
            throw DbException.get(ErrorCode.SYNTAX_ERROR_2, sql, "<not a query>");
        }
        return command.update();
    }

    @Override
    public boolean isTransactional() {
        return true;
    }

    @Override
    public int getType() {
        return CommandInterface.EXECUTE_IMMEDIATELY;
    }

    @Override
    public ResultInterface queryMeta() {
        return null;
    }

}
