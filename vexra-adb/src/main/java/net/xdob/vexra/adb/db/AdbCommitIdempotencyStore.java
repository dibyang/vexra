package net.xdob.vexra.adb.db;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ADB commit 幂等记录存储。
 *
 * <p>该实现是内存模型，用于 ADB-GA-02 的状态机和恢复测试。后续接入真实 store 时，
 * 需要保持相同语义：同一幂等键重复提交必须返回同一个 commitTs，不得生成新版本。</p>
 */
public final class AdbCommitIdempotencyStore {
  private final Map<String, AdbDurableCommitMarker> markers =
      new LinkedHashMap<>();

  /**
   * 记录新 marker，或返回已有幂等记录。
   *
   * @param marker durable commit marker
   * @return 已存在或刚写入的 marker
   * @throws SQLException 当同一幂等键对应不同事务或 commitTs 时抛出
   */
  public synchronized AdbDurableCommitMarker recordOrGet(
      AdbDurableCommitMarker marker) throws SQLException {
    Objects.requireNonNull(marker, "marker == null");
    String key = marker.idempotencyKey();
    AdbDurableCommitMarker existing = markers.get(key);
    if (existing == null) {
      markers.put(key, marker);
      return marker;
    }
    if (existing.getTxnId() != marker.getTxnId()
        || existing.getCommitTs() != marker.getCommitTs()
        || !existing.getRegionId().equals(marker.getRegionId())) {
      throw new SQLException("ADB duplicate commit idempotency key conflict: "
          + key);
    }
    return existing;
  }

  /**
   * 推进已有 marker 状态。
   *
   * @param marker 新状态 marker
   */
  public synchronized void update(AdbDurableCommitMarker marker) {
    Objects.requireNonNull(marker, "marker == null");
    markers.put(marker.idempotencyKey(), marker);
  }

  /**
   * 返回当前 marker 快照。
   *
   * @return marker 快照
   */
  public synchronized Collection<AdbDurableCommitMarker> snapshot() {
    return Collections.unmodifiableList(
        new java.util.ArrayList<>(markers.values()));
  }
}
