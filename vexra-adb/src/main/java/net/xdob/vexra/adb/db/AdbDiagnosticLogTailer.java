package net.xdob.vexra.adb.db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ADB 诊断日志尾部采集器。
 *
 * <p>采集器只读取用户显式传入的日志文件，并限制每个文件的最大行数。日志内容不会
 * 经过配置脱敏，因此生产 runbook 应只传入已经允许进入诊断归档的 ADB 进程日志。</p>
 */
public final class AdbDiagnosticLogTailer {
  /**
   * 采集多个日志文件尾部。
   *
   * @param paths 日志文件路径
   * @param maxLines 每个文件最多采集的末尾行数
   * @return 以路径字符串为 key 的日志尾部
   * @throws IOException 读取日志失败时抛出
   */
  public Map<String, List<String>> tail(List<Path> paths, int maxLines)
      throws IOException {
    Objects.requireNonNull(paths, "paths == null");
    if (maxLines < 0) {
      throw new IllegalArgumentException("maxLines is negative: " + maxLines);
    }
    Map<String, List<String>> result = new LinkedHashMap<>();
    for (Path path : paths) {
      result.put(path.toAbsolutePath().toString(), tail(path, maxLines));
    }
    return result;
  }

  /**
   * 采集单个日志文件尾部。
   *
   * @param path 日志文件路径
   * @param maxLines 最多采集的末尾行数
   * @return 日志尾部；文件不存在时返回一行缺失说明
   * @throws IOException 文件存在但读取失败时抛出
   */
  public List<String> tail(Path path, int maxLines) throws IOException {
    Objects.requireNonNull(path, "path == null");
    if (maxLines < 0) {
      throw new IllegalArgumentException("maxLines is negative: " + maxLines);
    }
    if (!Files.exists(path)) {
      return java.util.Collections.singletonList("missing log file: "
          + path.toAbsolutePath());
    }
    if (!Files.isRegularFile(path)) {
      return java.util.Collections.singletonList("not a regular log file: "
          + path.toAbsolutePath());
    }
    Deque<String> tail = new ArrayDeque<>();
    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
      if (maxLines == 0) {
        continue;
      }
      if (tail.size() == maxLines) {
        tail.removeFirst();
      }
      tail.addLast(line);
    }
    return new ArrayList<>(tail);
  }
}
