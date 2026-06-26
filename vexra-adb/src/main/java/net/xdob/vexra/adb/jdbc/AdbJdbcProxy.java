package net.xdob.vexra.adb.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * ADB JDBC 包装代理工厂。
 *
 * <p>代理只拦截 {@link Connection#prepareStatement(String)} 和
 * {@link Connection#createStatement()}，其余 JDBC 行为直接委托给 h2db 原连接。这样可以在普通
 * JDBC SQL 上接入 ADB bulk insert，同时保持兼容回退边界清晰。</p>
 */
final class AdbJdbcProxy {

  private AdbJdbcProxy() {
  }

  /**
   * 包装连接对象。
   *
   * @param connection h2db 原始连接
   * @return ADB 包装连接
   */
  static Connection wrap(Connection connection) {
    return (Connection) Proxy.newProxyInstance(
        connection.getClass().getClassLoader(),
        new Class<?>[]{Connection.class},
        new ConnectionHandler(connection));
  }

  private static final class ConnectionHandler implements InvocationHandler {

    private final Connection delegate;

    private ConnectionHandler(Connection delegate) {
      this.delegate = delegate;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args)
        throws Throwable {
      String name = method.getName();
      if ("prepareStatement".equals(name) && args != null
          && args.length > 0 && args[0] instanceof String
          && method.getReturnType().isAssignableFrom(PreparedStatement.class)) {
        PreparedStatement statement;
        try {
          statement = (PreparedStatement) method.invoke(delegate, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
          throw e.getCause();
        }
        AdbPreparedInsertPlan insertPlan = AdbPreparedInsertPlan.parse(
            (String) args[0]);
        if (insertPlan != null && !insertPlan.isBulkInsert()) {
          insertPlan = null;
        }
        AdbPreparedPointLookupPlan pointLookupPlan =
            AdbPreparedPointLookupPlan.parse((String) args[0]);
        AdbPreparedRangeCountPlan rangeCountPlan =
            AdbPreparedRangeCountPlan.parse((String) args[0]);
        AdbTableCountPlan tableCountPlan = AdbTableCountPlan.parse(
            (String) args[0]);
        if (insertPlan != null || pointLookupPlan != null
            || rangeCountPlan != null || tableCountPlan != null) {
          return AdbPreparedStatementProxy.wrap(delegate, statement,
              insertPlan, pointLookupPlan, rangeCountPlan, tableCountPlan);
        }
        return statement;
      }
      if ("createStatement".equals(name)
          && method.getReturnType().isAssignableFrom(Statement.class)) {
        Statement statement;
        try {
          statement = (Statement) method.invoke(delegate, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
          throw e.getCause();
        }
        return AdbStatementProxy.wrap(delegate, statement);
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
      try {
        return method.invoke(delegate, args);
      } catch (java.lang.reflect.InvocationTargetException e) {
        throw e.getCause();
      }
    }
  }
}
