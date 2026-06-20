package net.xdob.vexra.adb.db;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * ADB 诊断日志自动发现器。
 *
 * <p>发现器只基于集群编排配置中的 runtime、SQL baseDir 和节点 dataDir 做浅层扫描，
 * 识别常见 `.log`、`.out`、`.err` 文件。它不会递归遍历任意目录，避免 doctor 在生产
 * 故障现场意外读取过多文件。</p>
 */
public final class AdbDiagnosticLogDiscoverer {
  private final int maxFiles;

  /**
   * 创建日志发现器。
   *
   * @param maxFiles 最多返回日志文件数，0 表示不返回
   */
  public AdbDiagnosticLogDiscoverer(int maxFiles) {
    if (maxFiles < 0) {
      throw new IllegalArgumentException("maxFiles is negative: " + maxFiles);
    }
    this.maxFiles = maxFiles;
  }

  /**
   * 从集群配置中发现日志文件。
   *
   * @param config 集群编排配置
   * @return 去重后的日志文件列表
   * @throws IOException 目录读取失败时抛出
   */
  public List<Path> discover(AdbClusterOrchestrationConfig config)
      throws IOException {
    Objects.requireNonNull(config, "config == null");
    Set<Path> logs = new LinkedHashSet<>();
    scanCandidate(Paths.get(config.getRuntimeDir(), "logs"), logs);
    scanCandidate(Paths.get(config.getSqlBaseDir()), logs);
    scanCandidate(Paths.get(config.getSqlBaseDir(), "logs"), logs);
    for (AdbDeploymentNodeSpec node : config.getNodes()) {
      scanCandidate(Paths.get(node.getDataDir()), logs);
      scanCandidate(Paths.get(node.getDataDir(), "logs"), logs);
    }
    return new ArrayList<>(logs);
  }

  private void scanCandidate(Path directory, Set<Path> logs)
      throws IOException {
    if (logs.size() >= maxFiles || !Files.isDirectory(directory)) {
      return;
    }
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
      for (Path path : stream) {
        if (logs.size() >= maxFiles) {
          return;
        }
        if (Files.isRegularFile(path) && isLogFile(path)) {
          logs.add(path.toAbsolutePath().normalize());
        }
      }
    }
  }

  private static boolean isLogFile(Path path) {
    String name = path.getFileName().toString().toLowerCase(
        java.util.Locale.ROOT);
    return name.endsWith(".log") || name.endsWith(".out")
        || name.endsWith(".err");
  }
}
