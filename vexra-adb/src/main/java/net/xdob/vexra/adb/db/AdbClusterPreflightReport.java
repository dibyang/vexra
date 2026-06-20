package net.xdob.vexra.adb.db;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * ADB 集群预检报告。
 *
 * <p>报告只记录可审计的检查结果，不启动进程也不修改配置。命令行入口根据
 * {@link #isPassed()} 决定退出码，部署系统可以直接解析文本输出。</p>
 */
public final class AdbClusterPreflightReport {
  private final List<String> passedChecks;
  private final List<String> failedChecks;

  /**
   * 创建预检报告。
   *
   * @param passedChecks 通过的检查项
   * @param failedChecks 失败的检查项
   */
  public AdbClusterPreflightReport(List<String> passedChecks,
      List<String> failedChecks) {
    this.passedChecks = immutableCopy(passedChecks, "passedChecks");
    this.failedChecks = immutableCopy(failedChecks, "failedChecks");
  }

  public List<String> getPassedChecks() {
    return passedChecks;
  }

  public List<String> getFailedChecks() {
    return failedChecks;
  }

  /**
   * 判断预检是否全部通过。
   *
   * @return 没有失败项时返回 true
   */
  public boolean isPassed() {
    return failedChecks.isEmpty();
  }

  /**
   * 渲染为命令行文本。
   *
   * @return PASS/FAIL 加逐项结果
   */
  public String render() {
    StringBuilder builder = new StringBuilder();
    builder.append(isPassed() ? "PASS" : "FAIL").append('\n');
    for (String check : passedChecks) {
      builder.append("OK ").append(check).append('\n');
    }
    for (String check : failedChecks) {
      builder.append("FAIL ").append(check).append('\n');
    }
    return builder.toString();
  }

  private static List<String> immutableCopy(List<String> source,
      String fieldName) {
    Objects.requireNonNull(source, fieldName + " == null");
    return Collections.unmodifiableList(new ArrayList<>(source));
  }
}
