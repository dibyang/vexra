package net.xdob.vexra.adb;

import net.xdob.vexra.cluster.ops.RollingUpgradePlan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ADB 滚动升级计划命令行入口。
 *
 * <p>该命令只生成可审计的升级顺序和回滚提示，不连接集群、不停止进程，也不修改任何
 * 节点状态。真实部署系统可以把输出作为执行前 runbook 和人工复核材料。</p>
 */
public final class AdbUpgradePlanMain {
  public static final String MAIN_CLASS =
      "net.xdob.vexra.adb.AdbUpgradePlanMain";

  private AdbUpgradePlanMain() {
  }

  /**
   * 生成滚动升级计划。
   *
   * @param args `--targetVersion v --nodes n1,n2,w1`，可选
   *             `--upgraded n1`
   */
  public static void main(String[] args) {
    UpgradePlanCommand command = UpgradePlanCommand.parse(args);
    System.out.print(command.render());
  }

  private static final class UpgradePlanCommand {
    private final RollingUpgradePlan plan;

    private UpgradePlanCommand(RollingUpgradePlan plan) {
      this.plan = plan;
    }

    private static UpgradePlanCommand parse(String[] args) {
      Map<String, String> values = parseArgs(args);
      String targetVersion = require(values, "targetVersion");
      List<String> nodes = splitCsv(require(values, "nodes"));
      List<String> upgraded = values.containsKey("upgraded")
          ? splitCsv(values.get("upgraded")) : null;
      return new UpgradePlanCommand(new RollingUpgradePlan(targetVersion,
          nodes, upgraded));
    }

    private String render() {
      StringBuilder builder = new StringBuilder();
      builder.append("PASS\n");
      builder.append("targetVersion=").append(plan.getTargetVersion())
          .append('\n');
      builder.append("nodes=").append(String.join(",", plan.getNodeIds()))
          .append('\n');
      builder.append("upgraded=").append(String.join(",",
          plan.getUpgradedNodeIds())).append('\n');
      builder.append("complete=").append(plan.isComplete()).append('\n');
      builder.append("nextNode=").append(plan.nextNode()).append('\n');
      builder.append("[steps]\n");
      RollingUpgradePlan current = plan;
      int step = 1;
      while (!current.isComplete()) {
        String node = current.nextNode();
        builder.append(step).append(". upgrade ").append(node)
            .append(" to ").append(current.getTargetVersion())
            .append("; verify health; rollback command: rollback ")
            .append(node).append(" to previous version\n");
        current = current.markUpgraded(node);
        step++;
      }
      return builder.toString();
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

    private static List<String> splitCsv(String csv) {
      List<String> result = new ArrayList<>();
      for (String item : Arrays.asList(csv.split(","))) {
        String trimmed = item.trim();
        if (!trimmed.isEmpty()) {
          result.add(trimmed);
        }
      }
      if (result.isEmpty()) {
        throw new IllegalArgumentException("csv value is empty");
      }
      return result;
    }
  }
}
