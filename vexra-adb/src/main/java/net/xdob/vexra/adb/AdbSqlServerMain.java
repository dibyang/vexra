package net.xdob.vexra.adb;

import org.h2.tools.Server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.util.Collections;

/**
 * ADB SQL/JDBC 服务的产品级 JVM 入口。
 *
 * <p>该入口只负责启动 h2db TCP Server，并让 h2db 继续承担 JDBC、SQL parser、Server 和 tools
 * 能力。ADB 通过 h2db 插件注册 `jdbc:adb:*` 前缀和 ADB table provider，因此这里不复制或改造 h2db
 * 的网络协议实现。</p>
 */
public final class AdbSqlServerMain {
  /** ADB SQL server 默认 main class 名称，供脚本和 smoke 测试使用。 */
  public static final String MAIN_CLASS = AdbSqlServerMain.class.getName();

  private AdbSqlServerMain() {
  }

  /**
   * 启动 ADB SQL server 进程。
   *
   * @param args `--key value` 形式的启动参数
   */
  public static void main(String[] args) {
    try {
      run(AdbSqlServerConfig.parse(args));
    } catch (Throwable t) {
      t.printStackTrace(System.err);
      System.err.flush();
      System.exit(1);
    }
  }

  /**
   * 按配置启动 h2db TCP Server 并等待外部关闭信号。
   *
   * @param config SQL server 启动配置
   * @throws Exception server 启动、ready 文件写入或等待关闭过程中发生异常时抛出
   */
  public static void run(AdbSqlServerConfig config) throws Exception {
    Server server = newServer(config);
    try {
      server.start();
      writeReadyFile(config.getReadyFile(), config.getPort());
      waitForShutdownSignal(config.getStopFile());
    } finally {
      server.stop();
    }
  }

  /**
   * 创建未启动的 h2db TCP Server。
   *
   * @param config SQL server 启动配置
   * @return 未启动的 h2db Server
   * @throws SQLException h2db Server 构造失败时抛出
   */
  public static Server newServer(AdbSqlServerConfig config)
      throws SQLException {
    return Server.createTcpServer(config.toH2TcpServerArgs());
  }

  private static void writeReadyFile(Path readyFile, int port)
      throws IOException {
    if (readyFile == null) {
      return;
    }
    Path parent = readyFile.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.write(readyFile, Collections.singletonList("ready " + port),
        StandardCharsets.UTF_8, StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
  }

  private static void waitForShutdownSignal(Path stopFile)
      throws InterruptedException {
    if (stopFile == null) {
      while (true) {
        Thread.sleep(1000L);
      }
    }
    while (!Files.exists(stopFile)) {
      Thread.sleep(200L);
    }
  }
}
