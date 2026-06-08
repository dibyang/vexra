package net.xdob.vexra.cluster.ddl;

import java.util.Objects;

/**
 * Online DDL job 状态机。
 *
 * <p>状态机限制 DDL 只能按可恢复顺序推进：PENDING -> RUNNING -> BACKFILLING
 * -> PUBLIC，运行中或回填中可以进入 ROLLBACK，回滚后可进入 FAILED。</p>
 */
public final class DdlJobStateMachine {
  /**
   * 将 DDL job 推进到目标状态。
   *
   * @param job 当前 job
   * @param nextState 目标状态
   * @return 更新后的 job
   */
  public DdlJob transition(DdlJob job, DdlJobState nextState) {
    Objects.requireNonNull(job, "job == null");
    Objects.requireNonNull(nextState, "nextState == null");
    if (!canTransition(job.getState(), nextState)) {
      throw new IllegalStateException(
          "Illegal DDL transition from " + job.getState() + " to " + nextState);
    }
    SchemaVersion version = job.getSchemaVersion();
    if (nextState == DdlJobState.RUNNING || nextState == DdlJobState.PUBLIC) {
      version = version.next();
    }
    return job.withState(nextState, version);
  }

  /**
   * 判断状态跳转是否合法。
   *
   * @param current 当前状态
   * @param next 目标状态
   * @return 合法返回 true
   */
  public boolean canTransition(DdlJobState current, DdlJobState next) {
    if (current == DdlJobState.PENDING) {
      return next == DdlJobState.RUNNING || next == DdlJobState.FAILED;
    }
    if (current == DdlJobState.RUNNING) {
      return next == DdlJobState.BACKFILLING
          || next == DdlJobState.PUBLIC
          || next == DdlJobState.ROLLBACK
          || next == DdlJobState.FAILED;
    }
    if (current == DdlJobState.BACKFILLING) {
      return next == DdlJobState.PUBLIC
          || next == DdlJobState.ROLLBACK
          || next == DdlJobState.FAILED;
    }
    if (current == DdlJobState.ROLLBACK) {
      return next == DdlJobState.FAILED;
    }
    return false;
  }
}
