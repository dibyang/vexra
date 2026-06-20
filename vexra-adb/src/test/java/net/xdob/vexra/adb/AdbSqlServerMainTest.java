package net.xdob.vexra.adb;

import net.xdob.vexra.adb.db.AdbProductionState;
import net.xdob.vexra.adb.db.AdbUnsupportedProductionFeatureException;
import net.xdob.vexra.adb.db.DbStoreEngine;
import org.h2.tools.Server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADB SQL server 产品入口测试。
 *
 * <p>测试覆盖启动参数解析、h2db TCP Server 构造，以及独立 JVM 中通过产品 main class 启动
 * SQL server 后用 `jdbc:adb:tcp://...` 完成 ADB 表读写。</p>
 */
class AdbSqlServerMainTest {
  @TempDir
  Path tempDir;

  /**
   * 验证 SQL server 命令行参数会转换为 h2db TCP Server 参数。
   */
  @Test
  void shouldParseSqlServerArguments() {
    AdbSqlServerConfig config = AdbSqlServerConfig.parse(new String[] {
        "--port", "19090",
        "--baseDir", tempDir.toString(),
        "--tcpAllowOthers", "true",
        "--ifNotExists", "true",
        "--ready", tempDir.resolve("ready").toString(),
        "--stop", tempDir.resolve("stop").toString()
    });

    assertEquals(19090, config.getPort());
    assertEquals(tempDir, config.getBaseDir());
    assertTrue(config.isTcpAllowOthers());
    assertTrue(config.isIfNotExists());
    assertArrayEquals(new String[] {
        "-tcpPort", "19090",
        "-tcpAllowOthers",
        "-ifNotExists",
        "-baseDir", tempDir.toAbsolutePath().toString()
    }, config.toH2TcpServerArgs());
  }

  /**
   * 验证 SQL server 启动参数可以承载生产范围 guard 参数。
   */
  @Test
  void shouldParseSqlServerProductionGuardArguments() {
    AdbSqlServerConfig config = AdbSqlServerConfig.parse(new String[] {
        "--port", "19091",
        "--adb.production.mode", "mvp-cluster",
        "--adb.production.topology", "2data1witness",
        "--adb.security.tls.enabled", "true",
        "--adb.security.auth.enabled", "true",
        "--adb.security.leastPrivilege.enabled", "true"
    });

    assertEquals(AdbProductionState.CLUSTER_READY,
        config.productionGuard().getState());
  }

  /**
   * 验证 SQL server main 可以构造未启动的 h2db TCP Server。
   *
   * @throws Exception server 构造或关闭失败时抛出
   */
  @Test
  void shouldCreateTcpServerFromConfig() throws Exception {
    AdbSqlServerConfig config = new AdbSqlServerConfig(findFreePort(),
        tempDir, false, true, null, null);

    Server server = AdbSqlServerMain.newServer(config);
    try {
      assertNotNull(server);
    } finally {
      server.stop();
    }
  }

  /**
   * 验证显式生产集群模式缺安全默认值时，SQL server 不会进入 h2db TCP Server 构造。
   */
  @Test
  void shouldRejectSqlServerStartupWithoutSecureProductionDefaults()
      throws Exception {
    AdbSqlServerConfig config = AdbSqlServerConfig.parse(new String[] {
        "--port", String.valueOf(findFreePort()),
        "--adb.production.mode", "mvp-cluster",
        "--adb.production.topology", "2data1witness"
    });

    SQLException error = assertThrows(SQLException.class,
        () -> AdbSqlServerMain.newServer(config));
    assertEquals(AdbUnsupportedProductionFeatureException.SQL_STATE,
        error.getSQLState());
    assertTrue(error.getMessage().contains(
        "mvp cluster requires TLS, auth and least privilege"),
        error.getMessage());
  }

  /**
   * 验证 2 data + witness 且安全默认值完整时，SQL server 启动边界允许构造。
   */
  @Test
  void shouldCreateSqlServerWithSecureProductionDefaults() throws Exception {
    AdbSqlServerConfig config = AdbSqlServerConfig.parse(new String[] {
        "--port", String.valueOf(findFreePort()),
        "--baseDir", tempDir.toString(),
        "--ifNotExists", "true",
        "--adb.production.mode", "mvp-cluster",
        "--adb.production.topology", "2data1witness",
        "--adb.security.tls.enabled", "true",
        "--adb.security.auth.enabled", "true",
        "--adb.security.leastPrivilege.enabled", "true"
    });

    Server server = AdbSqlServerMain.newServer(config);
    try {
      assertNotNull(server);
    } finally {
      server.stop();
    }
  }

  /**
   * 验证独立 JVM 中的产品 SQL server 入口可以承载 `jdbc:adb:tcp://...` 读写。
   *
   * @throws Exception 子进程启动、JDBC 连接或 SQL 执行失败时抛出
   */
  @Test
  void shouldServeAdbJdbcTcpTrafficFromForkedMainClass() throws Exception {
    int port = findFreePort();
    Path serverDir = tempDir.resolve("server");
    Path ready = serverDir.resolve("ready");
    Path stop = serverDir.resolve("stop");
    String databaseName = "adb-sql-smoke";
    ProcessHandle handle = startSqlServerProcess(port, serverDir, ready, stop);
    String databasePath = serverDir.resolve(databaseName).toAbsolutePath()
        .toString().replace('\\', '/');

    try {
      waitForReady(handle, ready);
      executeJdbcSmoke(port, databaseName);
    } catch (Exception e) {
      throw withProcessLog(e, handle);
    } finally {
      stopProcess(handle, stop);
      DbStoreEngine.close(databasePath);
    }
  }

  private ProcessHandle startSqlServerProcess(int port, Path serverDir,
      Path ready, Path stop) throws IOException {
    List<String> command = new ArrayList<>();
    command.add(javaExecutable());
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(AdbSqlServerMain.MAIN_CLASS);
    command.add("--port");
    command.add(String.valueOf(port));
    command.add("--baseDir");
    command.add(serverDir.toAbsolutePath().toString());
    command.add("--ifNotExists");
    command.add("true");
    command.add("--ready");
    command.add(ready.toString());
    command.add("--stop");
    command.add(stop.toString());

    ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    Process process = builder.start();
    StringBuilder logText = new StringBuilder();
    Thread logReader = startLogReader(process.getInputStream(), logText);
    return new ProcessHandle(process, logText, logReader);
  }

  private static void executeJdbcSmoke(int port, String databaseName)
      throws Exception {
    String url = "jdbc:adb:tcp://127.0.0.1:" + port + "/" + databaseName
        + ";DB_CLOSE_DELAY=0";
    try (Connection connection = new org.h2.Driver().connect(url,
        new Properties());
         Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
      statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (1, 'tcp')");
      assertEquals("tcp", singleString(statement,
          "SELECT NAME FROM TEST WHERE ID = 1"));
    }
  }

  private static String singleString(Statement statement, String sql)
      throws Exception {
    try (ResultSet resultSet = statement.executeQuery(sql)) {
      resultSet.next();
      return resultSet.getString(1);
    }
  }

  private static void waitForReady(ProcessHandle handle, Path ready)
      throws Exception {
    long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
    while (!Files.exists(ready)) {
      if (!handle.process.isAlive()) {
        fail("SQL server process exited before ready"
            + System.lineSeparator() + readLog(handle));
      }
      if (System.currentTimeMillis() > deadline) {
        fail("Timed out waiting for SQL server process"
            + System.lineSeparator() + readLog(handle));
      }
      Thread.sleep(200L);
    }
  }

  private static Thread startLogReader(InputStream input,
      StringBuilder logText) {
    Thread thread = new Thread(() -> readProcessLog(input, logText),
        "adb-sql-server-log-reader");
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  private static void readProcessLog(InputStream input, StringBuilder logText) {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(input,
        StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        synchronized (logText) {
          logText.append(line).append(System.lineSeparator());
        }
      }
    } catch (IOException e) {
      synchronized (logText) {
        logText.append("failed to read process log: ")
            .append(e.getMessage()).append(System.lineSeparator());
      }
    }
  }

  private static Exception withProcessLog(Exception cause, ProcessHandle handle) {
    AssertionError error = new AssertionError(cause.getMessage()
        + System.lineSeparator() + readLog(handle), cause);
    return new Exception(error);
  }

  private static String readLog(ProcessHandle handle) {
    StringBuilder builder = new StringBuilder();
    synchronized (handle.logText) {
      if (handle.logText.length() == 0) {
        builder.append("log is empty");
      } else {
        builder.append(handle.logText);
      }
    }
    return builder.toString();
  }

  private static void stopProcess(ProcessHandle handle, Path stop) {
    try {
      Files.createDirectories(stop.getParent());
      Files.write(stop, Collections.singletonList("stop"),
          StandardCharsets.UTF_8, StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
    } catch (IOException ignored) {
      // 清理阶段继续尝试关闭进程，避免 stop 文件失败造成 JVM 泄漏。
    }
    try {
      if (!handle.process.waitFor(10, TimeUnit.SECONDS)) {
        handle.process.destroy();
        if (!handle.process.waitFor(5, TimeUnit.SECONDS)) {
          handle.process.destroyForcibly();
          handle.process.waitFor(10, TimeUnit.SECONDS);
        }
      }
      handle.logReader.join(TimeUnit.SECONDS.toMillis(2));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      handle.process.destroyForcibly();
    }
  }

  private static String javaExecutable() {
    String name = System.getProperty("os.name", "")
        .toLowerCase().contains("win") ? "java.exe" : "java";
    return new File(new File(System.getProperty("java.home"), "bin"),
        name).getAbsolutePath();
  }

  private static int findFreePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static final class ProcessHandle {
    private final Process process;
    private final StringBuilder logText;
    private final Thread logReader;

    private ProcessHandle(Process process, StringBuilder logText,
        Thread logReader) {
      this.process = process;
      this.logText = logText;
      this.logReader = logReader;
    }
  }
}
