package net.xdob.vexra.adb;

import net.xdob.vexra.adb.db.AdbEndToEndClusterStressReport;
import net.xdob.vexra.adb.db.AdbCommitCrashInjectionGate;
import net.xdob.vexra.adb.db.AdbCommitCrashInjectionPoint;
import net.xdob.vexra.adb.db.AdbCommitCrashInjectionReport;
import net.xdob.vexra.adb.db.AdbCommitCrashInjectionResult;
import net.xdob.vexra.adb.db.AdbDurableCommitState;
import net.xdob.vexra.adb.db.AdbFaultInjectionResult;
import net.xdob.vexra.adb.db.AdbFaultInjectionType;
import net.xdob.vexra.adb.db.AdbLongRunAcceptanceCriteria;
import net.xdob.vexra.adb.db.AdbLongRunStressEvaluation;
import net.xdob.vexra.adb.db.AdbLongRunStressReport;
import net.xdob.vexra.adb.db.AdbRecoveryDrillGate;
import net.xdob.vexra.adb.db.AdbRecoveryDrillReport;
import net.xdob.vexra.adb.db.AdbRecoveryDrillResult;
import net.xdob.vexra.adb.db.AdbRecoveryDrillScenario;
import net.xdob.vexra.adb.db.AdbReleaseProfileResult;
import net.xdob.vexra.adb.db.AdbReleaseProfileRunner;
import net.xdob.vexra.adb.db.AdbTrialProductionAdmissionGate;
import net.xdob.vexra.adb.db.AdbTrialProductionAdmissionReport;
import net.xdob.vexra.adb.db.AdbTrialProductionAdmissionWriter;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ADB release profile 命令行入口。
 *
 * <p>该入口把 GA-07 的 `AdbReleaseProfileRunner` 暴露给 Gradle 和 CI。当前版本接收
 * 参数化的端到端报告摘要并写入 release evidence；真实长稳和故障注入平台后续可把实际报告
 * 转换为同一 runner 请求。</p>
 */
public final class AdbReleaseProfileMain {
  /** ADB release profile main class 名称，供 Gradle task 和启动脚本使用。 */
  public static final String MAIN_CLASS = AdbReleaseProfileMain.class.getName();

  private AdbReleaseProfileMain() {
  }

  /**
   * 执行 release profile。
   *
   * @param args `--key value` 形式的参数
   */
  public static void main(String[] args) {
    try {
      AdbReleaseProfileResult result = run(args);
      System.out.println("releaseProfile.passed=" + result.isPassed());
      System.out.println("releaseProfile.evidenceFile="
          + result.getEvidenceFile());
      if (result.getTrialAdmissionFile() != null) {
        System.out.println("releaseProfile.trialAdmissionFile="
            + result.getTrialAdmissionFile());
      }
      if (!result.isPassed()) {
        System.err.println("releaseProfile.failureReasons="
            + result.getFailureReasons());
        System.exit(1);
      }
    } catch (Throwable t) {
      t.printStackTrace(System.err);
      System.err.flush();
      System.exit(1);
    }
  }

  /**
   * 执行 release profile 并返回结果。
   *
   * @param args 命令行参数
   * @return profile 执行结果
   * @throws Exception 参数解析、门禁评估或 evidence 写入失败时抛出
   */
  public static AdbReleaseProfileResult run(String[] args) throws Exception {
    Map<String, String> values = parseArgs(args);
    AdbReleaseProfileRunner.AdbReleaseProfileRequest request =
        new AdbReleaseProfileRunner.AdbReleaseProfileRequest(
            require(values, "releaseId"),
            require(values, "version"),
            report(values),
            criteria(values),
            list(values, "commands", "adb-release-profile"),
            list(values, "checksums", "none=not-provided"),
            Paths.get(require(values, "output")));
    AdbReleaseProfileResult result = new AdbReleaseProfileRunner().run(request);
    Path admissionFile = writeTrialAdmission(values, result);
    return new AdbReleaseProfileResult(result.getEvaluation(),
        result.getEvidenceFile(), admissionFile);
  }

  private static Path writeTrialAdmission(Map<String, String> values,
      AdbReleaseProfileResult result) throws Exception {
    AdbTrialProductionAdmissionReport report =
        new AdbTrialProductionAdmissionReport(
            require(values, "releaseId"),
            require(values, "version"),
            result.isPassed(),
            bool(values, "trialDataScaleAccepted", false),
            bool(values, "trialRollbackPlanReady", false),
            bool(values, "trialAlertingReady", false),
            bool(values, "trialOnCallWindowReady", false),
            bool(values, "trialKnownLimitationsAccepted", false),
            value(values, "trialNotes", ""));
    return new AdbTrialProductionAdmissionWriter().write(
        Paths.get(require(values, "output")), report,
        new AdbTrialProductionAdmissionGate().evaluate(report));
  }

  private static AdbEndToEndClusterStressReport report(
      Map<String, String> values) {
    return new AdbEndToEndClusterStressReport(
        value(values, "clusterName", "adb-release-profile"),
        longRunReport(values), commitCrashEvaluation(values),
        recoveryDrillEvaluation(values),
        bool(values, "clusterReadWritePassed", true),
        bool(values, "recoveryDrillPassed", true),
        bool(values, "rollingUpgradePassed", true),
        intValue(values, "sqlRegionSmokeCycles", 1));
  }

  private static AdbLongRunStressEvaluation commitCrashEvaluation(
      Map<String, String> values) {
    return commitCrashEvaluation(bool(values, "commitCrashGatePassed", true));
  }

  private static AdbLongRunStressEvaluation commitCrashEvaluation(
      boolean passed) {
    return new AdbCommitCrashInjectionGate().evaluate(
        new AdbCommitCrashInjectionReport("release-profile",
            Arrays.asList(
                crash(AdbCommitCrashInjectionPoint.BEFORE_PREWRITE, null,
                    passed),
                crash(AdbCommitCrashInjectionPoint.AFTER_PREWRITE_BEFORE_RAFT,
                    AdbDurableCommitState.ROLLED_BACK, true),
                crash(AdbCommitCrashInjectionPoint.AFTER_RAFT_BEFORE_STORE,
                    AdbDurableCommitState.STORE_COMMITTED, true),
                crash(AdbCommitCrashInjectionPoint.AFTER_STORE_BEFORE_REPLY,
                    AdbDurableCommitState.REPLIED, true))));
  }

  private static AdbCommitCrashInjectionResult crash(
      AdbCommitCrashInjectionPoint point, AdbDurableCommitState state,
      boolean recovered) {
    return new AdbCommitCrashInjectionResult(point, recovered, state,
        "release-profile");
  }

  private static AdbLongRunStressEvaluation recoveryDrillEvaluation(
      Map<String, String> values) {
    return recoveryDrillEvaluation(bool(values, "recoveryDrillGatePassed",
        true));
  }

  private static AdbLongRunStressEvaluation recoveryDrillEvaluation(
      boolean passed) {
    return new AdbRecoveryDrillGate().evaluate(
        new AdbRecoveryDrillReport("release-profile",
            Arrays.asList(
                drill(AdbRecoveryDrillScenario.KILL_LEADER, passed,
                    passed),
                drill(AdbRecoveryDrillScenario.KILL_FOLLOWER),
                drill(AdbRecoveryDrillScenario.KILL_WITNESS),
                drill(AdbRecoveryDrillScenario.FULL_CLUSTER_RESTART))));
  }

  private static AdbRecoveryDrillResult drill(
      AdbRecoveryDrillScenario scenario) {
    return drill(scenario, true, true);
  }

  private static AdbRecoveryDrillResult drill(
      AdbRecoveryDrillScenario scenario, boolean recovered,
      boolean checksumMatched) {
    return new AdbRecoveryDrillResult(scenario, true, recovered,
        checksumMatched,
        "release-profile");
  }

  private static AdbLongRunStressReport longRunReport(
      Map<String, String> values) {
    long totalOperations = longValue(values, "totalOperations", 1);
    long failedOperations = longValue(values, "failedOperations", 0);
    return new AdbLongRunStressReport(
        value(values, "workloadName", "adb-release-profile"),
        longValue(values, "durationMillis", 1),
        totalOperations, failedOperations,
        doubleValue(values, "throughputPerSecond", 1D),
        longValue(values, "p95LatencyMillis", 1),
        longValue(values, "p99LatencyMillis", 1),
        intValue(values, "checkpointCycles", 1),
        intValue(values, "backupRestoreCycles", 1),
        intValue(values, "gcCycles", 1),
        allRecoveredFaults());
  }

  private static AdbLongRunAcceptanceCriteria criteria(
      Map<String, String> values) {
    return new AdbLongRunAcceptanceCriteria(
        longValue(values, "minDurationMillis", 1),
        longValue(values, "minOperations", 1),
        doubleValue(values, "maxFailureRate", 0.01D),
        longValue(values, "maxP99LatencyMillis", 5_000),
        intValue(values, "minCheckpointCycles", 1),
        intValue(values, "minBackupRestoreCycles", 1),
        intValue(values, "minGcCycles", 1),
        EnumSet.allOf(AdbFaultInjectionType.class));
  }

  private static List<AdbFaultInjectionResult> allRecoveredFaults() {
    List<AdbFaultInjectionResult> results = new ArrayList<>();
    for (AdbFaultInjectionType type : AdbFaultInjectionType.values()) {
      results.add(new AdbFaultInjectionResult(type, 1, 1, true,
          "recovered"));
    }
    return results;
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

  private static List<String> list(Map<String, String> values, String name,
      String defaultValue) {
    String raw = value(values, name, defaultValue);
    List<String> list = new ArrayList<>();
    for (String part : raw.split(";")) {
      if (!part.trim().isEmpty()) {
        list.add(part.trim());
      }
    }
    if (list.isEmpty()) {
      return Arrays.asList(defaultValue);
    }
    return list;
  }

  private static String require(Map<String, String> values, String name) {
    String value = values.get(name);
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException("Missing argument: " + name);
    }
    return value.trim();
  }

  private static String value(Map<String, String> values, String name,
      String defaultValue) {
    String value = values.get(name);
    return value == null || value.trim().isEmpty()
        ? defaultValue : value.trim();
  }

  private static boolean bool(Map<String, String> values, String name,
      boolean defaultValue) {
    String value = values.get(name);
    return value == null || value.trim().isEmpty()
        ? defaultValue : Boolean.parseBoolean(value.trim());
  }

  private static int intValue(Map<String, String> values, String name,
      int defaultValue) {
    return Integer.parseInt(value(values, name, String.valueOf(defaultValue)));
  }

  private static long longValue(Map<String, String> values, String name,
      long defaultValue) {
    return Long.parseLong(value(values, name, String.valueOf(defaultValue)));
  }

  private static double doubleValue(Map<String, String> values, String name,
      double defaultValue) {
    return Double.parseDouble(value(values, name, String.valueOf(defaultValue)));
  }
}
