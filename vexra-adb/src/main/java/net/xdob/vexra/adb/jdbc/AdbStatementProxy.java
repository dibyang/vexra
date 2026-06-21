package net.xdob.vexra.adb.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;

/**
 * ADB Statement 包装代理。
 *
 * <p>该代理只尝试接管简单 literal 多值 INSERT。解析失败、目标表不是 ADB 表或语法不在保守
 * 支持范围内时，立即委托给 h2db 原 Statement，避免改变普通 SQL 行为。</p>
 */
final class AdbStatementProxy {

  private AdbStatementProxy() {
  }

  /**
   * 包装 Statement。
   *
   * @param connection h2db 原始连接
   * @param statement h2db 原始 Statement
   * @return 包装后的 Statement
   */
  static Statement wrap(Connection connection, Statement statement) {
    return (Statement) Proxy.newProxyInstance(
        statement.getClass().getClassLoader(),
        new Class<?>[]{Statement.class},
        new Handler(connection, statement));
  }

  private static final class Handler implements InvocationHandler {

    private final Connection connection;
    private final Statement delegate;
    private int lastUpdateCount = -1;

    private Handler(Connection connection, Statement delegate) {
      this.connection = connection;
      this.delegate = delegate;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args)
        throws Throwable {
      String name = method.getName();
      if (isSqlExecution(name, args)) {
        AdbPreparedInsertPlan plan = AdbPreparedInsertPlan.parseLiteral(
            (String) args[0]);
        if (plan != null) {
          Integer count = plan.tryExecuteLiteral(connection);
          if (count != null) {
            lastUpdateCount = count.intValue();
            if ("executeLargeUpdate".equals(name)) {
              return Long.valueOf(count.longValue());
            }
            if ("execute".equals(name)) {
              return Boolean.FALSE;
            }
            return count;
          }
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
      try {
        return method.invoke(delegate, args);
      } catch (java.lang.reflect.InvocationTargetException e) {
        throw e.getCause();
      }
    }

    private static boolean isSqlExecution(String name, Object[] args) {
      return ("executeUpdate".equals(name)
          || "executeLargeUpdate".equals(name)
          || "execute".equals(name))
          && args != null && args.length == 1 && args[0] instanceof String;
    }
  }
}
