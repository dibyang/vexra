/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.command.ddl;

import java.util.ArrayList;

import org.adb.command.CommandInterface;
import org.adb.command.Prepared;
import org.adb.engine.Procedure;
import org.adb.engine.SessionLocal;
import org.adb.expression.Parameter;

/**
 * This class represents the statement
 * PREPARE
 */
public class PrepareProcedure extends DefineCommand {

    private String procedureName;
    private Prepared prepared;

    public PrepareProcedure(SessionLocal session) {
        super(session);
    }

    @Override
    public void checkParameters() {
        // no not check parameters
    }

    @Override
    public long update() {
        Procedure proc = new Procedure(procedureName, prepared);
        prepared.setParameterList(parameters);
        prepared.setPrepareAlways(prepareAlways);
        prepared.prepare();
        session.addProcedure(proc);
        return 0;
    }

    public void setProcedureName(String name) {
        this.procedureName = name;
    }

    public void setPrepared(Prepared prep) {
        this.prepared = prep;
    }

    @Override
    public ArrayList<Parameter> getParameters() {
        return new ArrayList<>(0);
    }

    @Override
    public int getType() {
        return CommandInterface.PREPARE;
    }

}
