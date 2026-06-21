package net.xdob.vexra.adb.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.h2.value.Value;

/**
 * ADB JDBC 快路径使用的最小 ResultSet。
 *
 * <p>该实现只服务于主键点查快路径，覆盖常见的 {@code next/getString/getLong/getInt}
 * 等读取方法；未覆盖的方法返回 JDBC 友好的默认值或抛出不支持异常。</p>
 */
final class AdbSimpleResultSet {

  private AdbSimpleResultSet() {
  }

  /**
   * 创建最多一行的 ResultSet。
   *
   * @param columns 列名
   * @param values 行值；为 {@code null} 表示空结果集
   * @return ResultSet 代理
   */
  static ResultSet singleRow(List<String> columns, Value[] values) {
    return (ResultSet) Proxy.newProxyInstance(
        ResultSet.class.getClassLoader(),
        new Class<?>[]{ResultSet.class},
        new Handler(columns, values));
  }

  private static final class Handler implements InvocationHandler {

    private final List<String> columns;
    private final Value[] values;
    private boolean beforeFirst = true;
    private boolean onRow;
    private boolean closed;
    private boolean wasNull;

    private Handler(List<String> columns, Value[] values) {
      this.columns = columns;
      this.values = values;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args)
        throws Throwable {
      String name = method.getName();
      if ("next".equals(name)) {
        if (closed || !beforeFirst || values == null) {
          onRow = false;
          beforeFirst = false;
          return Boolean.FALSE;
        }
        beforeFirst = false;
        onRow = true;
        return Boolean.TRUE;
      }
      if ("close".equals(name)) {
        closed = true;
        onRow = false;
        return null;
      }
      if ("isClosed".equals(name)) {
        return Boolean.valueOf(closed);
      }
      if ("wasNull".equals(name)) {
        return Boolean.valueOf(wasNull);
      }
      if ("findColumn".equals(name)) {
        return Integer.valueOf(findColumn((String) args[0]));
      }
      if ("getString".equals(name)) {
        Value value = value(args[0]);
        return value == null ? null : value.getString();
      }
      if ("getLong".equals(name)) {
        Value value = value(args[0]);
        return Long.valueOf(value == null ? 0L : value.getLong());
      }
      if ("getInt".equals(name)) {
        Value value = value(args[0]);
        return Integer.valueOf(value == null ? 0 : value.getInt());
      }
      if ("getBoolean".equals(name)) {
        Value value = value(args[0]);
        return Boolean.valueOf(value != null && value.getBoolean());
      }
      if ("getObject".equals(name)) {
        Value value = value(args[0]);
        return value == null ? null : value.getString();
      }
      if ("unwrap".equals(name) && args != null && args.length == 1
          && args[0] instanceof Class) {
        Class<?> type = (Class<?>) args[0];
        if (type.isInstance(proxy)) {
          return proxy;
        }
        throw new SQLException("Not a wrapper for " + type.getName());
      }
      if ("isWrapperFor".equals(name) && args != null && args.length == 1
          && args[0] instanceof Class) {
        return Boolean.valueOf(((Class<?>) args[0]).isInstance(proxy));
      }
      return defaultValue(method.getReturnType(), name);
    }

    private Value value(Object column) throws SQLException {
      if (!onRow || closed) {
        throw new SQLException("ResultSet is not positioned on a row");
      }
      int index = column instanceof Number
          ? ((Number) column).intValue() : findColumn(String.valueOf(column));
      if (index < 1 || index > values.length) {
        throw new SQLException("Invalid column index: " + index);
      }
      Value value = values[index - 1];
      wasNull = value == null || value == org.h2.value.ValueNull.INSTANCE;
      return wasNull ? null : value;
    }

    private int findColumn(String column) throws SQLException {
      for (int i = 0; i < columns.size(); i++) {
        if (columns.get(i).equalsIgnoreCase(column)) {
          return i + 1;
        }
      }
      throw new SQLException("Unknown column: " + column);
    }

    private static Object defaultValue(Class<?> type, String name)
        throws SQLException {
      if (type == Void.TYPE) {
        return null;
      }
      if (type == Boolean.TYPE) {
        return Boolean.FALSE;
      }
      if (type == Integer.TYPE) {
        return Integer.valueOf(0);
      }
      if (type == Long.TYPE) {
        return Long.valueOf(0L);
      }
      if (type == Double.TYPE) {
        return Double.valueOf(0D);
      }
      if (type == Float.TYPE) {
        return Float.valueOf(0F);
      }
      if (type == Short.TYPE) {
        return Short.valueOf((short) 0);
      }
      if (type == Byte.TYPE) {
        return Byte.valueOf((byte) 0);
      }
      if (type == Character.TYPE) {
        return Character.valueOf((char) 0);
      }
      if ("getMetaData".equals(name)) {
        throw new SQLException("ResultSet metadata is not supported by ADB fast path");
      }
      return null;
    }
  }
}
