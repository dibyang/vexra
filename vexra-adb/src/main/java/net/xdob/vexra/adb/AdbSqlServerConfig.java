package net.xdob.vexra.adb;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * ADB SQL server 的进程启动配置。
 *
 * <p>该对象只负责解析和校验产品入口参数，不直接启动 h2db Server。SQL 解析、JDBC 协议和
 * TCP Server 实现继续由 h2db 提供，ADB 只在启动边界补齐可测试的命令行配置。</p>
 */
public final class AdbSqlServerConfig {
  private final int port;
  private final Path baseDir;
  private final boolean tcpAllowOthers;
  private final boolean ifNotExists;
  private final Path readyFile;
  private final Path stopFile;

  /**
   * 创建 ADB SQL server 启动配置。
   *
   * @param port h2db TCP Server 监听端口
   * @param baseDir 可选数据库根目录
   * @param tcpAllowOthers 是否允许非本机 TCP 连接
   * @param ifNotExists 是否允许远程创建不存在的数据库
   * @param readyFile 可选 ready 文件路径
   * @param stopFile 可选 stop 文件路径
   */
  public AdbSqlServerConfig(int port, Path baseDir, boolean tcpAllowOthers,
      boolean ifNotExists, Path readyFile, Path stopFile) {
    if (port <= 0 || port > 65535) {
      throw new IllegalArgumentException("invalid port: " + port);
    }
    this.port = port;
    this.baseDir = baseDir;
    this.tcpAllowOthers = tcpAllowOthers;
    this.ifNotExists = ifNotExists;
    this.readyFile = readyFile;
    this.stopFile = stopFile;
  }

  /**
   * 从命令行参数解析 SQL server 配置。
   *
   * @param args `--key value` 形式的命令行参数
   * @return 经过校验的启动配置
   */
  public static AdbSqlServerConfig parse(String[] args) {
    Map<String, String> values = parseArgs(args);
    return new AdbSqlServerConfig(
        Integer.parseInt(require(values, "port")),
        optionalPath(values, "baseDir"),
        optionalBoolean(values, "tcpAllowOthers", false),
        optionalBoolean(values, "ifNotExists", false),
        optionalPath(values, "ready"),
        optionalPath(values, "stop"));
  }

  public int getPort() {
    return port;
  }

  public Path getBaseDir() {
    return baseDir;
  }

  public boolean isTcpAllowOthers() {
    return tcpAllowOthers;
  }

  public boolean isIfNotExists() {
    return ifNotExists;
  }

  public Path getReadyFile() {
    return readyFile;
  }

  public Path getStopFile() {
    return stopFile;
  }

  /**
   * 生成 h2db TCP Server 参数。
   *
   * @return 可传给 `Server.createTcpServer(...)` 的参数数组
   */
  public String[] toH2TcpServerArgs() {
    ArgsBuilder builder = new ArgsBuilder();
    builder.add("-tcpPort").add(String.valueOf(port));
    if (tcpAllowOthers) {
      builder.add("-tcpAllowOthers");
    }
    if (ifNotExists) {
      builder.add("-ifNotExists");
    }
    if (baseDir != null) {
      builder.add("-baseDir").add(baseDir.toAbsolutePath().toString());
    }
    return builder.toArray();
  }

  private static Map<String, String> parseArgs(String[] args) {
    Map<String, String> values = new HashMap<>();
    for (int i = 0; i < args.length; i += 2) {
      if (i + 1 >= args.length || !args[i].startsWith("--")) {
        throw new IllegalArgumentException("Illegal argument at index " + i);
      }
      values.put(args[i].substring(2), args[i + 1]);
    }
    return values;
  }

  private static String require(Map<String, String> args, String name) {
    String value = args.get(name);
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException("Missing argument: " + name);
    }
    return value.trim();
  }

  private static Path optionalPath(Map<String, String> args, String name) {
    String value = args.get(name);
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    return Paths.get(value.trim());
  }

  private static boolean optionalBoolean(Map<String, String> args, String name,
      boolean defaultValue) {
    String value = args.get(name);
    if (value == null || value.trim().isEmpty()) {
      return defaultValue;
    }
    return Boolean.parseBoolean(value.trim());
  }

  private static final class ArgsBuilder {
    private String[] values = new String[8];
    private int size;

    private ArgsBuilder add(String value) {
      if (size == values.length) {
        String[] expanded = new String[values.length * 2];
        System.arraycopy(values, 0, expanded, 0, values.length);
        values = expanded;
      }
      values[size++] = value;
      return this;
    }

    private String[] toArray() {
      String[] result = new String[size];
      System.arraycopy(values, 0, result, 0, size);
      return result;
    }
  }
}
