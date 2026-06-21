package net.xdob.vexra.adb.jdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;
import net.xdob.vexra.adb.h2plugin.AdbJdbcUrlPrefixProvider;

/**
 * ADB JDBC URL 的轻量包装 Driver。
 *
 * <p>该 Driver 只接管 {@code jdbc:adb:*} 兼容前缀，真实连接、SQL 解析和大多数 JDBC
 * 行为仍委托给 h2db Driver。包装层只在可安全识别参数化多值 INSERT 时，把
 * {@code PreparedStatement} 路由到 ADB 表级 bulk insert 热路径。</p>
 */
public final class AdbDriver implements Driver {

  private final org.h2.Driver delegate = new org.h2.Driver();

  static {
    try {
      AdbDriver driver = new AdbDriver();
      DriverManager.registerDriver(driver);
      preferAdbDriverForAdbUrl();
    } catch (SQLException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * 创建 ADB 兼容 URL 连接。
   *
   * @param url JDBC URL
   * @param info JDBC 连接属性
   * @return 包装后的连接；非 ADB URL 返回 {@code null}
   * @throws SQLException 底层 h2db 连接失败时抛出
   */
  @Override
  public Connection connect(String url, Properties info) throws SQLException {
    if (!acceptsURL(url)) {
      return null;
    }
    Connection connection = delegate.connect(url, info);
    return AdbJdbcProxy.wrap(connection);
  }

  /**
   * 判断是否接管指定 URL。
   *
   * @param url JDBC URL
   * @return 是否为 {@code jdbc:adb:*}
   */
  @Override
  public boolean acceptsURL(String url) {
    return url != null && url.regionMatches(true, 0,
        AdbJdbcUrlPrefixProvider.URL_PREFIX, 0,
        AdbJdbcUrlPrefixProvider.URL_PREFIX.length());
  }

  /**
   * 返回 Driver 属性信息。
   *
   * @param url JDBC URL
   * @param info JDBC 连接属性
   * @return 底层 h2db Driver 属性
   * @throws SQLException 属性读取失败时抛出
   */
  @Override
  public DriverPropertyInfo[] getPropertyInfo(String url, Properties info)
      throws SQLException {
    return delegate.getPropertyInfo(url, info);
  }

  /**
   * 返回主版本号。
   *
   * @return h2db Driver 主版本号
   */
  @Override
  public int getMajorVersion() {
    return delegate.getMajorVersion();
  }

  /**
   * 返回次版本号。
   *
   * @return h2db Driver 次版本号
   */
  @Override
  public int getMinorVersion() {
    return delegate.getMinorVersion();
  }

  /**
   * 声明是否 JDBC compliant。
   *
   * @return 委托 Driver 的声明
   */
  @Override
  public boolean jdbcCompliant() {
    return delegate.jdbcCompliant();
  }

  /**
   * 返回父级 logger。
   *
   * @return 父级 logger
   * @throws SQLFeatureNotSupportedException 当前环境不支持时抛出
   */
  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    return delegate.getParentLogger();
  }

  private static void preferAdbDriverForAdbUrl() throws SQLException {
    List<Driver> h2Drivers = new ArrayList<>();
    Enumeration<Driver> drivers = DriverManager.getDrivers();
    while (drivers.hasMoreElements()) {
      Driver driver = drivers.nextElement();
      if (driver instanceof org.h2.Driver) {
        h2Drivers.add(driver);
      }
    }
    for (Driver h2Driver : h2Drivers) {
      DriverManager.deregisterDriver(h2Driver);
    }
    for (Driver h2Driver : h2Drivers) {
      DriverManager.registerDriver(h2Driver);
    }
  }
}
