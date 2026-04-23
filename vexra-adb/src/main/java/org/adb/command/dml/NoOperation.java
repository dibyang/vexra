/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.command.dml;

import org.adb.command.CommandInterface;
import org.adb.command.Prepared;
import org.adb.engine.SessionLocal;
import org.adb.result.ResultInterface;

/**
 * Represents an empty statement or a statement that has no effect.
 */
public class NoOperation extends Prepared {

    public NoOperation(SessionLocal session) {
        super(session);
    }

    @Override
    public long update() {
        return 0;
    }

    @Override
    public boolean isTransactional() {
        return true;
    }

    @Override
    public boolean needRecompile() {
        return false;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ResultInterface queryMeta() {
        return null;
    }

    @Override
    public int getType() {
        return CommandInterface.NO_OPERATION;
    }

}
