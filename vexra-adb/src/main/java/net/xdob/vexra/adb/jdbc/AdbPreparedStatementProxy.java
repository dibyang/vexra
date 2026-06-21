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
      AdbPreparedPointLookupPlan pointLookupPlan) {
    return (PreparedStatement) Proxy.newProxyInstance(
        statement.getClass().getClassLoader(),
        new Class<?>[]{PreparedStatement.class},
        new Handler(connection, statement, insertPlan, pointLookupPlan));
  }

  private static final class Handler implements InvocationHandler {

    private final Connection connection;
    private final PreparedStatement delegate;
    private final AdbPreparedInsertPlan insertPlan;
    private final AdbPreparedPointLookupPlan pointLookupPlan;
    private final Object[] parameters;
    private final boolean[] parameterSet;
    private int lastUpdateCount = -1;

    private Handler(Connection connection, PreparedStatement delegate,
        AdbPreparedInsertPlan insertPlan,
        AdbPreparedPointLookupPlan pointLookupPlan) {
      this.connection = connection;
      this.delegate = delegate;
      this.insertPlan = insertPlan;
      this.pointLookupPlan = pointLookupPlan;
      int parameterCount = Math.max(parameterCount(insertPlan),
          parameterCount(pointLookupPlan));
      this.parameters = new Object[parameterCount + 1];
      this.parameterSet = new boolean[parameterCount + 1];
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
        return invokeDelegate(method, args);
      }
      if ("clearParameters".equals(name)) {
        java.util.Arrays.fill(parameters, null);
        java.util.Arrays.fill(parameterSet, false);
        return invokeDelegate(method, args);
      }
      if ("executeQuery".equals(name) && noSqlArgument(args)
          && pointLookupPlan != null) {
        ResultSet resultSet = pointLookupPlan.tryExecuteQuery(connection,
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
      return invokeDelegate(method, args);
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

    private static boolean noSqlArgument(Object[] args) {
      return args == null || args.length == 0;
    }

    private static boolean isSetter(String name, Object[] args) {
      return name != null && name.startsWith("set") && args != null
          && args.length >= 1 && args[0] instanceof Integer;
    }
  }
}
