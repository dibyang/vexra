package net.xdob.vexra.cluster.ops;

/**
 * 备份恢复模式。
 */
public enum BackupRestoreMode {
  /** 全量备份或恢复。 */
  FULL,

  /** 增量备份或恢复。 */
  INCREMENTAL,

  /** 指定时间点恢复。 */
  POINT_IN_TIME
}
