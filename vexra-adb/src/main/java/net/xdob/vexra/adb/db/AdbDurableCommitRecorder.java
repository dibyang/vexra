package net.xdob.vexra.adb.db;

import java.sql.SQLException;

/**
 * ADB durable commit 记录器。
 *
 * <p>该接口位于 region commit coordinator 和真实 marker store 之间，用来把
 * PREWRITE、region commit、store commit 和 reply 前后的状态变化写入可替换存储。
 * 默认实现必须是无副作用的 no-op，以便保持现有单机和测试路径兼容；生产实现需要把
 * marker 写入 LDB/Rocks 等持久化介质。</p>
 */
public interface AdbDurableCommitRecorder {

  /**
   * 返回不记录任何状态的兼容实现。
   *
   * @return no-op durable commit 记录器
   */
  static AdbDurableCommitRecorder noop() {
    return NoopDurableCommitRecorder.INSTANCE;
  }

  /**
   * 记录 region 已完成 prewrite，事务进入可恢复的 in-doubt 状态。
   *
   * @param request region commit 请求
   * @return 新建或已有的 durable commit marker
   * @throws SQLException marker 写入失败或幂等冲突
   */
  AdbDurableCommitMarker prewritten(AdbRegionCommitRequest request)
      throws SQLException;

  /**
   * 记录 region commit 已经通过复制提交，后续恢复只能前滚。
   *
   * @param marker 当前 marker
   * @return 推进后的 marker
   * @throws SQLException marker 写入失败
   */
  AdbDurableCommitMarker raftCommitted(AdbDurableCommitMarker marker)
      throws SQLException;

  /**
   * 记录 store committed version 已经持久化。
   *
   * @param marker 当前 marker
   * @return 推进后的 marker
   * @throws SQLException marker 写入失败
   */
  AdbDurableCommitMarker storeCommitted(AdbDurableCommitMarker marker)
      throws SQLException;

  /**
   * 记录 commit 成功结果即将或已经返回给调用方。
   *
   * @param marker 当前 marker
   * @return 推进后的 marker
   * @throws SQLException marker 写入失败
   */
  AdbDurableCommitMarker replied(AdbDurableCommitMarker marker)
      throws SQLException;

  /**
   * 记录 prewrite 后、复制提交前的安全回滚结果。
   *
   * @param marker 当前 marker
   * @param error 回滚原因
   * @return 推进后的 marker
   * @throws SQLException marker 写入失败
   */
  AdbDurableCommitMarker rolledBack(AdbDurableCommitMarker marker,
      Throwable error) throws SQLException;

  /**
   * 无副作用 durable commit 记录器。
   */
  final class NoopDurableCommitRecorder implements AdbDurableCommitRecorder {
    private static final NoopDurableCommitRecorder INSTANCE =
        new NoopDurableCommitRecorder();

    private NoopDurableCommitRecorder() {
    }

    @Override
    public AdbDurableCommitMarker prewritten(AdbRegionCommitRequest request) {
      return null;
    }

    @Override
    public AdbDurableCommitMarker raftCommitted(
        AdbDurableCommitMarker marker) {
      return marker;
    }

    @Override
    public AdbDurableCommitMarker storeCommitted(
        AdbDurableCommitMarker marker) {
      return marker;
    }

    @Override
    public AdbDurableCommitMarker replied(AdbDurableCommitMarker marker) {
      return marker;
    }

    @Override
    public AdbDurableCommitMarker rolledBack(AdbDurableCommitMarker marker,
        Throwable error) {
      return marker;
    }
  }
}
