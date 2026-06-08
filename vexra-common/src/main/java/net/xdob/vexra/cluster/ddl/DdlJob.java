package net.xdob.vexra.cluster.ddl;

import java.util.Objects;

/**
 * Online DDL job。
 *
 * <p>该对象描述 DDL job 的状态、schema version 和可选 index backfill 进度。
 * 状态迁移返回新对象，便于恢复和审计。</p>
 */
public final class DdlJob {
  private final String jobId;
  private final String ddlType;
  private final DdlJobState state;
  private final SchemaVersion schemaVersion;
  private final IndexBackfillProgress backfillProgress;

  /**
   * 创建 DDL job。
   *
   * @param jobId job 标识
   * @param ddlType DDL 类型，例如 ADD_INDEX
   * @param state 当前状态
   * @param schemaVersion schema version
   * @param backfillProgress 回填进度
   */
  public DdlJob(String jobId, String ddlType, DdlJobState state,
      SchemaVersion schemaVersion, IndexBackfillProgress backfillProgress) {
    this.jobId = normalize(jobId, "jobId");
    this.ddlType = normalize(ddlType, "ddlType");
    this.state = Objects.requireNonNull(state, "state == null");
    this.schemaVersion = Objects.requireNonNull(schemaVersion,
        "schemaVersion == null");
    this.backfillProgress = backfillProgress == null
        ? new IndexBackfillProgress(null, 0) : backfillProgress;
  }

  /**
   * 创建 pending DDL job。
   *
   * @param jobId job 标识
   * @param ddlType DDL 类型
   * @param schemaVersion schema version
   * @return pending job
   */
  public static DdlJob pending(String jobId, String ddlType,
      SchemaVersion schemaVersion) {
    return new DdlJob(jobId, ddlType, DdlJobState.PENDING, schemaVersion,
        new IndexBackfillProgress(null, 0));
  }

  public String getJobId() {
    return jobId;
  }

  public String getDdlType() {
    return ddlType;
  }

  public DdlJobState getState() {
    return state;
  }

  public SchemaVersion getSchemaVersion() {
    return schemaVersion;
  }

  public IndexBackfillProgress getBackfillProgress() {
    return backfillProgress;
  }

  /**
   * 更新 job 状态和 schema version。
   *
   * @param nextState 新状态
   * @param nextSchemaVersion 新 schema version
   * @return 新 DDL job
   */
  public DdlJob withState(DdlJobState nextState,
      SchemaVersion nextSchemaVersion) {
    return new DdlJob(jobId, ddlType, nextState, nextSchemaVersion,
        backfillProgress);
  }

  /**
   * 更新回填进度。
   *
   * @param progress 新回填进度
   * @return 新 DDL job
   */
  public DdlJob withBackfillProgress(IndexBackfillProgress progress) {
    return new DdlJob(jobId, ddlType, state, schemaVersion, progress);
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }
}
