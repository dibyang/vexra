package net.xdob.vexra.adb;

import net.xdob.vexra.adb.db.AdbClusterOrchestrationConfig;
import net.xdob.vexra.adb.db.AdbClusterOrchestrationPlan;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * ADB 集群编排计划命令行入口。
 *
 * <p>该入口读取一份集群 properties 配置，输出 SQL server、region node 和共享 catalog
 * 的运行计划。它不直接启动进程，便于在真实服务管理器接入前先验证 runtime 发行包中的
 * 自动编排配置。</p>
 */
public final class AdbClusterPlanMain {
  public static final String MAIN_CLASS =
      "net.xdob.vexra.adb.AdbClusterPlanMain";

  private AdbClusterPlanMain() {
  }

  /**
   * 执行集群编排计划生成。
   *
   * @param args `--config path`，可选 `--writeCatalog true`
   * @throws Exception 配置解析或 catalog 写出失败时抛出
   */
  public static void main(String[] args) throws Exception {
    Map<String, String> values = parseArgs(args);
    AdbClusterOrchestrationPlan plan = AdbClusterOrchestrationConfig.load(
        Paths.get(require(values, "config"))).toPlan();
    if (Boolean.parseBoolean(values.get("writeCatalog"))) {
      plan.writeCatalog();
    }
    System.out.print(plan.render());
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

  private static String require(Map<String, String> values, String name) {
    String value = values.get(name);
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException("Missing argument: " + name);
    }
    return value.trim();
  }
}
