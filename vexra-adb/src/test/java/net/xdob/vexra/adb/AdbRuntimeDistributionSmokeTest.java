package net.xdob.vexra.adb;

import net.xdob.vexra.adb.db.DbStoreEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ADB runtime 发行包脚本级 smoke 测试。
 *
 * <p>该测试不直接使用 test classpath 中的 main class，而是解压 `adbRuntimeDist` 生成的 zip，
 * 执行发行包内 `bin/adb-sql-server` 脚本，再通过真实 TCP/JDBC 连接完成 ADB 表读写。</p>
 */
class AdbRuntimeDistributionSmokeTest {
  @TempDir
  Path tempDir;

  /**
   * 验证 runtime zip 解包后的 SQL server 脚本可以启动并承载 JDBC 流量。
   *
   * @throws Exception 解包、进程启动、JDBC 访问或清理失败时抛出
   */
  @Test
  void shouldStartSqlServerFromRuntimeDistributionScript() throws Exception {
    Path runtimeDir = tempDir.resolve("runtime");
    extract(runtimeZip(), runtimeDir);

    int port = findFreePort();
    Path serverDir = tempDir.resolve("server");
    Path ready = serverDir.resolve("ready");
    Path stop = serverDir.resolve("stop");
    String databaseName = "adb-runtime-script-smoke";
    RuntimeProcessHandle handle = startSqlServerScript(runtimeDir, port,
        serverDir, ready, stop);
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

  /**
   * 验证 runtime zip 包含集群编排计划脚本。
   *
   * @throws Exception 解包失败时抛出
   */
  @Test
  void shouldIncludeClusterPlanScriptInRuntimeDistribution() throws Exception {
    Path runtimeDir = tempDir.resolve("runtime-plan-script");
    extract(runtimeZip(), runtimeDir);

    assertTrue(Files.exists(clusterPlanScript(runtimeDir)));
  }

  /**
   * 验证 runtime zip 包含并可执行集群预检脚本。
   *
   * @throws Exception 解包、配置写入或脚本执行失败时抛出
   */
  @Test
  void shouldRunClusterPreflightFromRuntimeDistribution() throws Exception {
    Path runtimeDir = tempDir.resolve("rtp");
    extract(runtimeZip(), runtimeDir);
    Path config = writePreflightConfig(runtimeDir);

    ProcessResult result = runScript(clusterPreflightScript(runtimeDir),
        "--config", config.toAbsolutePath().toString(), "--strictFiles",
        "false");

    assertEquals(0, result.exitCode, result.output);
    assertTrue(result.output.contains("PASS"));
    assertTrue(result.output.contains("OK topology=2data1witness"));
  }

  private RuntimeProcessHandle startSqlServerScript(Path runtimeDir, int port,
      Path serverDir, Path ready, Path stop) throws IOException {
    Path script = sqlServerScript(runtimeDir);
    List<String> command = new ArrayList<>();
    if (isWindows()) {
      command.add("cmd.exe");
      command.add("/c");
    }
    command.add(script.toAbsolutePath().toString());
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
    builder.directory(script.getParent().toFile());
    builder.redirectErrorStream(true);
    Process process = builder.start();
    StringBuilder logText = new StringBuilder();
    Thread logReader = startLogReader(process.getInputStream(), logText);
    return new RuntimeProcessHandle(process, logText, logReader);
  }

  private static void executeJdbcSmoke(int port, String databaseName)
      throws Exception {
    String url = "jdbc:adb:tcp://127.0.0.1:" + port + "/" + databaseName
        + ";DB_CLOSE_DELAY=0";
    try (Connection connection = new org.h2.Driver().connect(url,
        new Properties());
         Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
      statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (1, 'dist')");
      assertEquals("dist", singleString(statement,
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

  private static Path runtimeZip() throws IOException {
    Path distributions = Paths.get("build", "distributions");
    if (!Files.isDirectory(distributions)) {
      distributions = Paths.get("vexra-adb", "build", "distributions");
    }
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(distributions,
        "*runtime*.zip")) {
      for (Path path : stream) {
        return path;
      }
    }
    throw new IOException("runtime distribution zip not found in "
        + distributions.toAbsolutePath());
  }

  private static void extract(Path zip, Path targetDir) throws IOException {
    Files.createDirectories(targetDir);
    try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
      ZipEntry entry;
      while ((entry = input.getNextEntry()) != null) {
        Path target = targetDir.resolve(entry.getName()).normalize();
        if (!target.startsWith(targetDir)) {
          throw new IOException("Illegal zip entry: " + entry.getName());
        }
        if (entry.isDirectory()) {
          Files.createDirectories(target);
        } else {
          Files.createDirectories(target.getParent());
          Files.copy(input, target);
        }
        input.closeEntry();
      }
    }
  }

  private static Path sqlServerScript(Path runtimeDir) {
    String name = isWindows() ? "adb-sql-server.bat" : "adb-sql-server";
    return runtimeDir.resolve("bin").resolve(name);
  }

  private static Path clusterPlanScript(Path runtimeDir) {
    String name = isWindows() ? "adb-cluster-plan.bat" : "adb-cluster-plan";
    return runtimeDir.resolve("bin").resolve(name);
  }

  private static Path clusterPreflightScript(Path runtimeDir) {
    String name = isWindows() ? "adb-cluster-preflight.bat"
        : "adb-cluster-preflight";
    return runtimeDir.resolve("bin").resolve(name);
  }

  private Path writePreflightConfig(Path runtimeDir) throws IOException {
    Path config = tempDir.resolve("preflight-cluster.properties");
    Files.write(config, java.util.Arrays.asList(
        "adb.security.tls.enabled=true",
        "adb.security.auth.enabled=true",
        "adb.cluster.runtimeDir=" + path(runtimeDir),
        "adb.cluster.group=11111111-1111-1111-1111-111111111111",
        "adb.cluster.nodes=n1,n2,n3",
        "adb.cluster.sql.port=9123",
        "adb.cluster.sql.baseDir=" + path(tempDir.resolve("sql")),
        "adb.cluster.sql.ready=" + path(tempDir.resolve("run/sql.ready")),
        "adb.cluster.sql.stop=" + path(tempDir.resolve("run/sql.stop")),
        "adb.cluster.catalog.path="
            + path(tempDir.resolve("run/adb-catalog.properties")),
        "adb.cluster.node.n1.host=127.0.0.1",
        "adb.cluster.node.n1.port=19001",
        "adb.cluster.node.n1.dataDir=" + path(tempDir.resolve("n1")),
        "adb.cluster.node.n1.role=DATA_NODE",
        "adb.cluster.node.n2.host=127.0.0.1",
        "adb.cluster.node.n2.port=19002",
        "adb.cluster.node.n2.dataDir=" + path(tempDir.resolve("n2")),
        "adb.cluster.node.n2.role=DATA_NODE",
        "adb.cluster.node.n3.host=127.0.0.1",
        "adb.cluster.node.n3.port=19003",
        "adb.cluster.node.n3.dataDir=" + path(tempDir.resolve("n3")),
        "adb.cluster.node.n3.role=WITNESS_NODE",
        "adb.catalog.tso.readTs=20000",
        "adb.catalog.table.TEST.id=1",
        "adb.catalog.table.TEST.epoch=0"), StandardCharsets.UTF_8);
    return config;
  }

  private ProcessResult runScript(Path script, String... args)
      throws Exception {
    List<String> command = new ArrayList<>();
    if (isWindows()) {
      command.add("cmd.exe");
      command.add("/c");
    }
    command.add(script.toAbsolutePath().toString());
    Collections.addAll(command, args);
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(script.getParent().toFile());
    builder.redirectErrorStream(true);
    Process process = builder.start();
    String output;
    try (InputStream input = process.getInputStream()) {
      output = readAll(input);
    }
    int exitCode = process.waitFor();
    return new ProcessResult(exitCode, output);
  }

  private static String readAll(InputStream input) throws IOException {
    StringBuilder builder = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(input,
        StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        builder.append(line).append(System.lineSeparator());
      }
    }
    return builder.toString();
  }

  private static String path(Path path) {
    return path.toAbsolutePath().toString().replace('\\', '/');
  }

  private static void waitForReady(RuntimeProcessHandle handle, Path ready)
      throws Exception {
    long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
    while (!Files.exists(ready)) {
      if (!handle.process.isAlive()) {
        fail("runtime SQL server process exited before ready"
            + System.lineSeparator() + readLog(handle));
      }
      if (System.currentTimeMillis() > deadline) {
        fail("Timed out waiting for runtime SQL server process"
            + System.lineSeparator() + readLog(handle));
      }
      Thread.sleep(200L);
    }
  }

  private static Thread startLogReader(InputStream input,
      StringBuilder logText) {
    Thread thread = new Thread(() -> readProcessLog(input, logText),
        "adb-runtime-sql-server-log-reader");
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

  private static Exception withProcessLog(Exception cause,
      RuntimeProcessHandle handle) {
    AssertionError error = new AssertionError(cause.getMessage()
        + System.lineSeparator() + readLog(handle), cause);
    return new Exception(error);
  }

  private static String readLog(RuntimeProcessHandle handle) {
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

  private static void stopProcess(RuntimeProcessHandle handle, Path stop) {
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
        destroyProcessTree(handle.process);
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

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase().contains("win");
  }

  private static void destroyProcessTree(Process process) {
    if (isWindows()) {
      Long pid = processId(process);
      if (pid != null) {
        try {
          Process killer = new ProcessBuilder("taskkill", "/PID",
              String.valueOf(pid), "/T", "/F").start();
          killer.waitFor(10, TimeUnit.SECONDS);
          return;
        } catch (IOException | InterruptedException e) {
          if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
          }
        }
      }
    }
    process.destroy();
  }

  private static Long processId(Process process) {
    try {
      Object value = Process.class.getMethod("pid").invoke(process);
      return value instanceof Number ? ((Number) value).longValue() : null;
    } catch (ReflectiveOperationException | SecurityException e) {
      return null;
    }
  }

  private static int findFreePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static final class RuntimeProcessHandle {
    private final Process process;
    private final StringBuilder logText;
    private final Thread logReader;

    private RuntimeProcessHandle(Process process, StringBuilder logText,
        Thread logReader) {
      this.process = process;
      this.logText = logText;
      this.logReader = logReader;
    }
  }

  private static final class ProcessResult {
    private final int exitCode;
    private final String output;

    private ProcessResult(int exitCode, String output) {
      this.exitCode = exitCode;
      this.output = output;
    }
  }
}
