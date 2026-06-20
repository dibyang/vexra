package net.xdob.vexra.adb.db;

/**
 * ADB 生产能力枚举。
 *
 * <p>该枚举把第一版生产 MVP 支持范围与实验能力分离。调用方在进入 SQL、
 * 事务、运维或后台任务路径前，可以先通过 {@link AdbProductionGuard} 校验能力。</p>
 */
public enum AdbProductionCapability {
  /** 本地单机 SQL 能力。 */
  LOCAL_SQL(false),

  /** 显式集群模式下的分布式 SQL 读写能力。 */
  DISTRIBUTED_SQL(false),

  /** 单 region 事务能力。 */
  SINGLE_REGION_TRANSACTION(false),

  /** 基础在线 DDL 子集。 */
  BASIC_ONLINE_DDL(false),

  /** 全量备份恢复能力。 */
  BACKUP_RESTORE(false),

  /** 滚动升级计划和演练能力。 */
  ROLLING_UPGRADE(false),

  /** 跨 region 事务，第一版生产默认拒绝。 */
  CROSS_REGION_TRANSACTION(true),

  /** follower read，第一版生产默认拒绝。 */
  FOLLOWER_READ(true),

  /** 自动 split/merge，第一版生产默认拒绝。 */
  AUTO_SPLIT_MERGE(true),

  /** 复杂在线 DDL，第一版生产默认拒绝。 */
  COMPLEX_ONLINE_DDL(true);

  private final boolean experimentalOnly;

  AdbProductionCapability(boolean experimentalOnly) {
    this.experimentalOnly = experimentalOnly;
  }

  public boolean isExperimentalOnly() {
    return experimentalOnly;
  }
}
