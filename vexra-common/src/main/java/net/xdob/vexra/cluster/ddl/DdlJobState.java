package net.xdob.vexra.cluster.ddl;

/**
 * Online DDL job 状态。
 */
public enum DdlJobState {
  /** DDL 已创建，尚未执行。 */
  PENDING,

  /** DDL 正在准备或修改 schema 元数据。 */
  RUNNING,

  /** DDL 正在回填数据，例如 add index backfill。 */
  BACKFILLING,

  /** DDL 已对外可见。 */
  PUBLIC,

  /** DDL 正在回滚。 */
  ROLLBACK,

  /** DDL 失败且需要人工处理或重试。 */
  FAILED
}
