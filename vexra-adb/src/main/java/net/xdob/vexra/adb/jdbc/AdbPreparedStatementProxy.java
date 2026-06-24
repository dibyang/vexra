package net.xdob.vexra.adb.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * ADB PreparedStatement 包装代理。
 *
 * <p>代理记录参数化 INSERT 的参数值，执行时优先尝试 ADB bulk insert。计划不匹配、
 * 参数不完整或目标表不是 ADB 表时，会继续委托给 h2db 原 PreparedStatement。</p>
 */
final class AdbPreparedStatementProxy {

  private AdbPreparedStatementProxy() {
  }

  /**
   * 包装 PreparedStatement。
   *
   * @param connection h2db 原始连接
   * @param statement h2db 原始 PreparedStatement
   * @param plan 可尝试 bulk insert 的计划
   * @return 包装后的 PreparedStatement
   */
  static PreparedStatement wrap(Connection connection, PreparedStatement statement,
      AdbPreparedInsertPlan insertPlan,
      AdbPreparedPointLookupPlan pointLookupPlan,
      AdbPreparedRangeCountPlan rangeCountPlan,
      AdbTableCountPlan tableCountPlan) {
    return (PreparedStatement) Proxy.newProxyInstance(
        statement.getClass().getClassLoader(),
        new Class<?>[]{PreparedStatement.class},
        new Handler(connection, statement, insertPlan, pointLookupPlan,
            rangeCountPlan, tableCountPlan));
  }

  private static final class Handler implements InvocationHandler {

    private final Connection connection;
    private final PreparedStatement delegate;
    private final AdbPreparedInsertPlan insertPlan;
    private final AdbPreparedPointLookupPlan pointLookupPlan;
    private final AdbPreparedRangeCountPlan rangeCountPlan;
    private final AdbTableCountPlan tableCountPlan;
    private final Object[] parameters;
    private final boolean[] parameterSet;
    private final SetterCall[] deferredSetters;
    private boolean delegateMayHaveParameters;
    private int lastUpdateCount = -1;

    private Handler(Connection connection, PreparedStatement delegate,
        AdbPreparedInsertPlan insertPlan,
        AdbPreparedPointLookupPlan pointLookupPlan,
        AdbPreparedRangeCountPlan rangeCountPlan,
        AdbTableCountPlan tableCountPlan) {
      this.connection = connection;
      this.delegate = delegate;
      this.insertPlan = insertPlan;
      this.pointLookupPlan = pointLookupPlan;
      this.rangeCountPlan = rangeCountPlan;
      this.tableCountPlan = tableCountPlan;
      int parameterCount = Math.max(parameterCount(insertPlan),
          parameterCount(pointLookupPlan));
      parameterCount = Math.max(parameterCount, parameterCount(rangeCountPlan));
      parameterCount = Math.max(parameterCount, parameterCount(tableCountPlan));
      this.parameters = new Object[parameterCount + 1];
      this.parameterSet = new boolean[parameterCount + 1];
      this.deferredSetters = new SetterCall[parameterCount + 1];
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args)
        throws Throwable {
      String name = method.getName();
      if (isSetter(name, args)) {
        int parameter = ((Integer) args[0]).intValue();
        if (parameter <= 0 || parameter >= parameters.length) {
          return invokeDelegate(method, args);
        }
        parameterSet[parameter] = true;
        parameters[parameter] = "setNull".equals(name) ? null
            : args.length > 1 ? args[1] : null;
        SetterCall setter = deferredSetters[parameter];
        if (setter == null) {
          setter = new SetterCall();
          deferredSetters[parameter] = setter;
        }
        setter.capture(name, method, args);
        return null;
      }
      if ("clearParameters".equals(name)) {
        java.util.Arrays.fill(parameters, null);
        java.util.Arrays.fill(parameterSet, false);
        clearDeferredSetters();
        delegateMayHaveParameters = false;
        return invokeDelegate(method, args);
      }
      if ("close".equals(name) && noSqlArgument(args)) {
        return close(method, args);
      }
      if ("executeQuery".equals(name) && noSqlArgument(args)
          && tableCountPlan != null) {
        ResultSet resultSet = tableCountPlan.tryExecuteQuery(connection);
        if (resultSet != null) {
          return resultSet;
        }
      }
      if ("executeQuery".equals(name) && noSqlArgument(args)
          && pointLookupPlan != null) {
        ResultSet resultSet = pointLookupPlan.tryExecuteQuery(connection,
            parameters, parameterSet);
        if (resultSet != null) {
          return resultSet;
        }
      }
      if ("executeQuery".equals(name) && noSqlArgument(args)
          && rangeCountPlan != null) {
        ResultSet resultSet = rangeCountPlan.tryExecuteQuery(connection,
            parameters, parameterSet);
        if (resultSet != null) {
          return resultSet;
        }
      }
      if ("executeUpdate".equals(name) && noSqlArgument(args)) {
        Integer count = tryExecuteInsert();
        if (count != null) {
          lastUpdateCount = count.intValue();
          return count;
        }
      }
      if ("executeLargeUpdate".equals(name) && noSqlArgument(args)) {
        Integer count = tryExecuteInsert();
        if (count != null) {
          lastUpdateCount = count.intValue();
          return Long.valueOf(count.longValue());
        }
      }
      if ("execute".equals(name) && noSqlArgument(args)) {
        Integer count = tryExecuteInsert();
        if (count != null) {
          lastUpdateCount = count.intValue();
          return Boolean.FALSE;
        }
      }
      if ("getUpdateCount".equals(name) && lastUpdateCount >= 0) {
        return Integer.valueOf(lastUpdateCount);
      }
      if ("unwrap".equals(name) && args != null && args.length == 1
          && args[0] instanceof Class) {
        Class<?> type = (Class<?>) args[0];
        if (type.isInstance(proxy)) {
          return proxy;
        }
        return delegate.unwrap(type);
      }
      if ("isWrapperFor".equals(name) && args != null && args.length == 1
          && args[0] instanceof Class) {
        Class<?> type = (Class<?>) args[0];
        return type.isInstance(proxy) || delegate.isWrapperFor(type);
      }
      if (isExecution(name, args)) {
        replayDeferredSetters();
      }
      return invokeDelegate(method, args);
    }

    private Object close(Method method, Object[] args) throws Throwable {
      Throwable closeFailure = null;
      try {
        if (pointLookupPlan != null) {
          pointLookupPlan.close();
        }
        if (rangeCountPlan != null) {
          rangeCountPlan.close();
        }
      } catch (Throwable t) {
        closeFailure = t;
      }
      Object result = null;
      Throwable delegateFailure = null;
      try {
        result = invokeDelegate(method, args);
      } catch (Throwable t) {
        delegateFailure = t;
      }
      if (closeFailure != null) {
        if (delegateFailure != null) {
          closeFailure.addSuppressed(delegateFailure);
        }
        throw closeFailure;
      }
      if (delegateFailure != null) {
        throw delegateFailure;
      }
      return result;
    }

    private void replayDeferredSetters() throws Throwable {
      boolean hasDeferredSetter = false;
      for (SetterCall setter : deferredSetters) {
        if (setter != null && setter.isActive()) {
          hasDeferredSetter = true;
          break;
        }
      }
      if (!hasDeferredSetter) {
        return;
      }
      if (delegateMayHaveParameters) {
        delegate.clearParameters();
      }
      for (SetterCall setter : deferredSetters) {
        if (setter != null && setter.isActive()) {
          setter.invoke(delegate);
        }
      }
      delegateMayHaveParameters = true;
    }

    private void clearDeferredSetters() {
      for (SetterCall setter : deferredSetters) {
        if (setter != null) {
          setter.clear();
        }
      }
    }

    private Object invokeDelegate(Method method, Object[] args) throws Throwable {
      try {
        return method.invoke(delegate, args);
      } catch (java.lang.reflect.InvocationTargetException e) {
        throw e.getCause();
      }
    }

    private Integer tryExecuteInsert() throws java.sql.SQLException {
      return insertPlan == null ? null
          : insertPlan.tryExecute(connection, parameters, parameterSet);
    }

    private static int parameterCount(AdbPreparedInsertPlan plan) {
      return plan == null ? 0 : plan.parameterCount();
    }

    private static int parameterCount(AdbPreparedPointLookupPlan plan) {
      return plan == null ? 0 : plan.parameterCount();
    }

    private static int parameterCount(AdbPreparedRangeCountPlan plan) {
      return plan == null ? 0 : plan.parameterCount();
    }

    private static int parameterCount(AdbTableCountPlan plan) {
      return plan == null ? 0 : plan.parameterCount();
    }

    private static boolean noSqlArgument(Object[] args) {
      return args == null || args.length == 0;
    }

    private static boolean isExecution(String name, Object[] args) {
      return noSqlArgument(args) && ("execute".equals(name)
          || "executeQuery".equals(name)
          || "executeUpdate".equals(name)
          || "executeLargeUpdate".equals(name));
    }

    private static boolean isSetter(String name, Object[] args) {
      return name != null && name.startsWith("set") && args != null
          && args.length >= 1 && args[0] instanceof Integer;
    }
  }

  private static final class SetterCall {

    private static final int NONE = 0;
    private static final int REFLECTIVE = 1;
    private static final int LONG = 2;
    private static final int INT = 3;
    private static final int STRING = 4;
    private static final int BOOLEAN = 5;
    private static final int DOUBLE = 6;
    private static final int FLOAT = 7;
    private static final int OBJECT = 8;
    private static final int NULL = 9;
    private static final int BIG_DECIMAL = 10;
    private static final int BYTES = 11;

    private int kind;
    private int parameter;
    private Object value;
    private int sqlType;
    private String typeName;
    private Method method;
    private Object[] args;

    private boolean isActive() {
      return kind != NONE;
    }

    private void clear() {
      kind = NONE;
      parameter = 0;
      value = null;
      sqlType = 0;
      typeName = null;
      method = null;
      args = null;
    }

    /**
     * 记录最近一次参数 setter 调用。
     *
     * <p>ADB fast path 命中时这些 setter 不会回放到 h2db delegate，因此常见 setter
     * 只保存参数编号和值，避免每次调用都分配反射回放对象和参数数组副本。无法直接安全回放的
     * setter 仍保留原反射路径。</p>
     */
    private void capture(String name, Method method, Object[] args) {
      clear();
      parameter = ((Integer) args[0]).intValue();
      if ("setLong".equals(name) && args.length == 2
          && args[1] instanceof Long) {
        kind = LONG;
        value = args[1];
        return;
      }
      if ("setInt".equals(name) && args.length == 2
          && args[1] instanceof Integer) {
        kind = INT;
        value = args[1];
        return;
      }
      if ("setString".equals(name) && args.length == 2) {
        kind = STRING;
        value = args[1];
        return;
      }
      if ("setBoolean".equals(name) && args.length == 2
          && args[1] instanceof Boolean) {
        kind = BOOLEAN;
        value = args[1];
        return;
      }
      if ("setDouble".equals(name) && args.length == 2
          && args[1] instanceof Double) {
        kind = DOUBLE;
        value = args[1];
        return;
      }
      if ("setFloat".equals(name) && args.length == 2
          && args[1] instanceof Float) {
        kind = FLOAT;
        value = args[1];
        return;
      }
      if ("setObject".equals(name) && args.length == 2) {
        kind = OBJECT;
        value = args[1];
        return;
      }
      if ("setNull".equals(name) && args.length >= 2
          && args[1] instanceof Integer) {
        kind = NULL;
        sqlType = ((Integer) args[1]).intValue();
        typeName = args.length >= 3 ? (String) args[2] : null;
        return;
      }
      if ("setBigDecimal".equals(name) && args.length == 2) {
        kind = BIG_DECIMAL;
        value = args[1];
        return;
      }
      if ("setBytes".equals(name) && args.length == 2) {
        kind = BYTES;
        value = args[1];
        return;
      }
      kind = REFLECTIVE;
      this.method = method;
      this.args = args == null ? null : args.clone();
    }

    private void invoke(PreparedStatement delegate) throws Throwable {
      switch (kind) {
        case LONG:
          delegate.setLong(parameter, ((Long) value).longValue());
          return;
        case INT:
          delegate.setInt(parameter, ((Integer) value).intValue());
          return;
        case STRING:
          delegate.setString(parameter, (String) value);
          return;
        case BOOLEAN:
          delegate.setBoolean(parameter, ((Boolean) value).booleanValue());
          return;
        case DOUBLE:
          delegate.setDouble(parameter, ((Double) value).doubleValue());
          return;
        case FLOAT:
          delegate.setFloat(parameter, ((Float) value).floatValue());
          return;
        case OBJECT:
          delegate.setObject(parameter, value);
          return;
        case NULL:
          if (typeName == null) {
            delegate.setNull(parameter, sqlType);
          } else {
            delegate.setNull(parameter, sqlType, typeName);
          }
          return;
        case BIG_DECIMAL:
          delegate.setBigDecimal(parameter, (java.math.BigDecimal) value);
          return;
        case BYTES:
          delegate.setBytes(parameter, (byte[]) value);
          return;
        case REFLECTIVE:
          break;
        default:
          return;
      }
      try {
        method.invoke(delegate, args);
      } catch (java.lang.reflect.InvocationTargetException e) {
        throw e.getCause();
      }
    }
  }
}
