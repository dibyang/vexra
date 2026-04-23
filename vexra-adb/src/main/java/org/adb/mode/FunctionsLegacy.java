/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.mode;

import java.util.HashMap;

import org.adb.engine.SessionLocal;
import org.adb.expression.Expression;
import org.adb.message.DbException;
import org.adb.value.TypeInfo;
import org.adb.value.Value;

/**
 * This class implements some legacy functions not available in Regular mode.
 */
public class FunctionsLegacy extends ModeFunction {

    private static final HashMap<String, FunctionInfo> FUNCTIONS = new HashMap<>();

    private static final int IDENTITY = 6001;

    private static final int SCOPE_IDENTITY = IDENTITY + 1;

    static {
        FUNCTIONS.put("IDENTITY", new FunctionInfo("IDENTITY", IDENTITY, 0, Value.BIGINT, true, false));
        FUNCTIONS.put("SCOPE_IDENTITY",
                new FunctionInfo("SCOPE_IDENTITY", SCOPE_IDENTITY, 0, Value.BIGINT, true, false));
    }

    /**
     * Returns mode-specific function for a given name, or {@code null}.
     *
     * @param upperName
     *            the upper-case name of a function
     * @return the function with specified name or {@code null}
     */
    public static FunctionsLegacy getFunction(String upperName) {
        FunctionInfo info = FUNCTIONS.get(upperName);
        if (info != null) {
            return new FunctionsLegacy(info);
        }
        return null;
    }

    private FunctionsLegacy(FunctionInfo info) {
        super(info);
    }

    @Override
    public Value getValue(SessionLocal session) {
        switch (info.type) {
        case IDENTITY:
        case SCOPE_IDENTITY:
            return session.getLastIdentity().convertTo(type);
        default:
            throw DbException.getInternalError("type=" + info.type);
        }
    }

    @Override
    public Expression optimize(SessionLocal session) {
        type = TypeInfo.getTypeInfo(info.returnDataType);
        return this;
    }

}
