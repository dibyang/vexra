package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;

import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * ADB 本地 region commit bridge client。
 *
 * <p>该实现把 region commit client 接口桥接到现有 {@link DbStore#commitAsync}，
 * 用于单机兼容、测试和真实 region Raft client 上线前的过渡路径。</p>
 */
public final class AdbLocalRegionCommitClient implements AdbRegionCommitClient {
  private final DbStore store;

  /**
   * 创建本地 region commit bridge client。
   *
   * @param store ADB 底层 store
   */
  public AdbLocalRegionCommitClient(DbStore store) {
    this.store = Objects.requireNonNull(store, "store == null");
  }

  /**
   * 通过现有 store commit 语义完成 region commit。
   *
   * @param request region commit 请求
   * @return 提交完成 future
   */
  @Override
  public CompletableFuture<Void> commitAsync(AdbRegionCommitRequest request) {
    try {
      return store.commitAsync(request.getTxnId(), request.getCommitTs(),
          request.getMetas());
    } catch (SQLException e) {
      CompletableFuture<Void> failed = new CompletableFuture<>();
      failed.completeExceptionally(e);
      return failed;
    }
  }

  /**
   * 通过现有 store rollback 语义回滚本地事务 intent。
   *
   * @param request region rollback 请求
   * @return 回滚完成 future
   */
  @Override
  public CompletableFuture<Void> rollbackAsync(AdbRegionCommitRequest request) {
    try {
      return store.rollbackAsync(request.getTxnId());
    } catch (SQLException e) {
      CompletableFuture<Void> failed = new CompletableFuture<>();
      failed.completeExceptionally(e);
      return failed;
    }
  }
}
