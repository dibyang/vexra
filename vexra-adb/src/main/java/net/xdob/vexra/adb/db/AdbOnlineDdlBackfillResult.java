package net.xdob.vexra.adb.db;

import net.xdob.vexra.cluster.ddl.DdlJob;

import java.util.Objects;

/**
 * ADB Online DDL backfill 单轮执行结果。
 *
 * <p>该结果对象用于在调度器、测试和后续 admin/system table 之间传递本轮处理行数、
 * 最新 DDL job 快照和是否已经扫描完整张主表。</p>
 */
public final class AdbOnlineDdlBackfillResult {
  private final DdlJob job;
  private final long batchRows;
  private final boolean completed;

  AdbOnlineDdlBackfillResult(DdlJob job, long batchRows, boolean completed) {
    if (batchRows < 0) {
      throw new IllegalArgumentException("batchRows is negative");
    }
    this.job = Objects.requireNonNull(job, "job == null");
    this.batchRows = batchRows;
    this.completed = completed;
  }

  public DdlJob getJob() {
    return job;
  }

  public long getBatchRows() {
    return batchRows;
  }

  public boolean isCompleted() {
    return completed;
  }
}
