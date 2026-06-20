package net.xdob.vexra.adb;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.db.AdbProductionCapability;
import net.xdob.vexra.adb.db.AdbProductionGuard;
import net.xdob.vexra.adb.db.AdbProductionRequestContext;
import net.xdob.vexra.adb.db.DbStoreEngine;
import net.xdob.vexra.adb.db.DbStoreType;
import net.xdob.vexra.cluster.ops.BackupRestoreMode;
import net.xdob.vexra.cluster.ops.BackupRestorePlan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * ADB 本地 store 备份恢复命令执行器。
 *
 * <p>该入口复用 {@link DbStore#checkpoint(String)} 与
 * {@link DbStore#restore(String)}，只提供 GA-05 的本地 FULL backup/restore
 * 最小命令闭环。它不实现增量备份、PITR、对象存储上传或多 region 调度。</p>
 */
public final class AdbStoreBackupRestoreMain {
  private AdbStoreBackupRestoreMain() {
  }

  /**
   * 执行备份入口。
   *
   * @param args 命令行参数
   * @throws Exception 备份失败时抛出
   */
  public static void backup(String[] args) throws Exception {
    run("backup", args);
  }

  /**
   * 执行恢复入口。
   *
   * @param args 命令行参数
   * @throws Exception 恢复失败时抛出
   */
  public static void restore(String[] args) throws Exception {
    run("restore", args);
  }

  /**
   * 执行指定备份恢复操作。
   *
   * @param operation `backup` 或 `restore`
   * @param args 命令行参数
   * @throws Exception 操作失败时抛出
   */
  public static void run(String operation, String[] args) throws Exception {
    BackupRestoreCommand command = BackupRestoreCommand.parse(operation, args);
    command.execute();
    System.out.print(command.renderResult());
  }

  private static final class BackupRestoreCommand {
    private final String operation;
    private final DbStoreType storeType;
    private final String storeDir;
    private final BackupRestorePlan plan;
    private final boolean productionGuardEnabled;
    private final AdbProductionGuard productionGuard;

    private BackupRestoreCommand(String operation, DbStoreType storeType,
        String storeDir, BackupRestorePlan plan,
        boolean productionGuardEnabled,
        AdbProductionGuard productionGuard) {
      this.operation = operation;
      this.storeType = storeType;
      this.storeDir = storeDir;
      this.plan = plan;
      this.productionGuardEnabled = productionGuardEnabled;
      this.productionGuard = productionGuard;
    }

    private static BackupRestoreCommand parse(String operation, String[] args) {
      String normalizedOperation = normalizeOperation(operation);
      Map<String, String> values = parseArgs(args);
      DbStoreType storeType = DbStoreType.valueOf(values.getOrDefault("store",
          "LDB").trim().toUpperCase());
      if (storeType != DbStoreType.LDB && storeType != DbStoreType.ROCKSDB) {
        throw new IllegalArgumentException("unsupported backup store: "
            + storeType);
      }
      String storeDir = require(values, "storeDir");
      String location = require(values, "location");
      long checkpointTs = longValue(values.get("checkpointTs"), 0);
      BackupRestorePlan plan = new BackupRestorePlan(
          values.getOrDefault("planId", normalizedOperation + "-local"),
          BackupRestoreMode.FULL, Collections.singletonList("local"),
          location, checkpointTs);
      return new BackupRestoreCommand(normalizedOperation, storeType, storeDir,
          plan, AdbProductionCommandOptions.hasProductionProperties(values),
          AdbProductionCommandOptions.productionGuard(values));
    }

    private void execute() throws Exception {
      validateBeforeOpen();
      requireProductionCapability();
      DbStore store = DbStoreEngine.getOrCreate(storeType, storeDir,
          new Properties());
      try {
        if ("backup".equals(operation)) {
          store.checkpoint(plan.getLocation());
        } else {
          store.restore(plan.getLocation());
        }
      } finally {
        DbStoreEngine.close(storeDir);
      }
    }

    private void requireProductionCapability() throws java.sql.SQLException {
      if (!productionGuardEnabled) {
        return;
      }
      productionGuard.requireCapability(AdbProductionCapability.BACKUP_RESTORE,
          AdbProductionRequestContext.local(operation + " store"));
    }

    private void validateBeforeOpen() {
      Path storePath = Paths.get(storeDir);
      Path locationPath = Paths.get(plan.getLocation());
      if ("restore".equals(operation)
          && !Files.isDirectory(locationPath)) {
        throw new IllegalArgumentException("restore location is not directory: "
            + locationPath);
      }
      Path parent = "backup".equals(operation)
          ? locationPath.toAbsolutePath().getParent()
          : storePath.toAbsolutePath().getParent();
      if (parent != null && Files.exists(parent)
          && !Files.isDirectory(parent)) {
        throw new IllegalArgumentException("parent path is not directory: "
            + parent);
      }
    }

    private String renderResult() {
      return "PASS\n"
          + "operation=" + operation + '\n'
          + "store=" + storeType.name() + '\n'
          + "storeDir=" + storeDir + '\n'
          + "location=" + plan.getLocation() + '\n'
          + "mode=" + plan.getMode().name() + '\n';
    }

    private static String normalizeOperation(String operation) {
      if (operation == null) {
        throw new IllegalArgumentException("operation is required");
      }
      String normalized = operation.trim().toLowerCase();
      if (!"backup".equals(normalized) && !"restore".equals(normalized)) {
        throw new IllegalArgumentException("unsupported operation: "
            + operation);
      }
      return normalized;
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

    private static long longValue(String value, long defaultValue) {
      if (value == null || value.trim().isEmpty()) {
        return defaultValue;
      }
      return Long.parseLong(value.trim());
    }
  }
}
