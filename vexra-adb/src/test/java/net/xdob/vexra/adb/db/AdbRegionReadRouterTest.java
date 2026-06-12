package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.key.IndexPrefix;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.RowPrefix;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.cluster.region.RegionRouter;
import net.xdob.vexra.ha.ReplicaRole;
import net.xdob.vexra.ha.VirtualNodeMetadata;
import net.xdob.vexra.ha.VirtualNodeReplica;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADB region 读路由回归测试。
 *
 * <p>测试验证点读、主表扫描和索引扫描会经过可选 region read router，且默认 no-op
 * 路径可用于单机模式回滚。</p>
 */
class AdbRegionReadRouterTest {
  /**
   * 验证 TxnManager 点读会触发 region-aware 点读路由。
   */
  @Test
  void shouldRoutePointReadThroughTxnManager() throws SQLException {
    RouteRecorder recorder = new RouteRecorder();
    TxnManager manager = manager(recorder, unboundedRegion());

    RowValue visible = manager.getVisible(txn(), rowKey());

    assertNull(visible);
    assertEquals(Collections.singletonList("POINT:r1"), recorder.events);
  }

  /**
   * 验证主表 range scan 创建 cursor 前会触发范围路由。
   */
  @Test
  void shouldRouteTableScanThroughTxnManager() {
    RouteRecorder recorder = new RouteRecorder();
    TxnManager manager = manager(recorder, unboundedRegion());

    try (TableScanCursor ignored = manager.entryIterator(txn(),
        RowPrefix.of(tabId()), null, null)) {
      assertEquals(Collections.singletonList("RANGE:r1"), recorder.events);
    }
  }

  /**
   * 验证二级索引 range scan 创建 cursor 前会触发范围路由。
   */
  @Test
  void shouldRouteIndexScanThroughTxnManager() {
    RouteRecorder recorder = new RouteRecorder();
    TxnManager manager = manager(recorder, unboundedRegion());

    try (IndexScanCursor ignored = manager.indexScanIterator(txn(),
        IndexPrefix.of(tabId(), 2), null, null)) {
      assertEquals(Collections.singletonList("RANGE:r1"), recorder.events);
    }
  }

  /**
   * 验证点读 key 不属于任何 region 时读操作失败。
   */
  @Test
  void shouldFailPointReadWhenNoRegionContainsKey() {
    TxnManager manager = manager(new RouteRecorder(),
        new RegionMetadata("r-z", new KeyRange(bytes("z"), bytes("zz")), 1,
            metadata("vn-z")));

    assertThrows(SQLException.class, () -> manager.getVisible(txn(), rowKey()));
  }

  /**
   * 验证 null read router 会恢复为 no-op router。
   */
  @Test
  void shouldResetNullReadRouterToNoop() throws SQLException {
    TxnManager manager = new TxnManager(new EmptyStore());

    manager.setRegionReadRouter(null);

    assertSame(AdbRegionReadRouter.NOOP, manager.getRegionReadRouter());
    assertDoesNotThrow(() -> manager.getRegionReadRouter()
        .routePointRead(txn(), rowKey()));
  }

  private static TxnManager manager(RouteRecorder recorder,
      RegionMetadata region) {
    TxnManager manager = new TxnManager(new EmptyStore());
    manager.setRegionReadRouter(new RegionAwareAdbReadRouter(
        new RegionRouter(Collections.singletonList(region)), recorder));
    return manager;
  }

  private static Transaction2 txn() {
    return new Transaction2(10, 9);
  }

  private static RowKey rowKey() {
    return RowKey.of(tabId(), 100L);
  }

  private static TabId tabId() {
    return TabId.of(1, 1L);
  }

  private static RegionMetadata unboundedRegion() {
    return new RegionMetadata("r1", new KeyRange(new byte[0], new byte[0]), 1,
        metadata("vn-r1"));
  }

  private static VirtualNodeMetadata metadata(String virtualNodeId) {
    return new VirtualNodeMetadata(virtualNodeId, 1, "node-a",
        Arrays.asList(
            new VirtualNodeReplica("node-a", ReplicaRole.DATA_VOTER),
            new VirtualNodeReplica("node-b", ReplicaRole.DATA_VOTER),
            new VirtualNodeReplica("witness-a", ReplicaRole.WITNESS_VOTER)),
        0, 0, 0);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static final class RouteRecorder
      implements RegionAwareAdbReadRouter.ReadRouteObserver {
    private final List<String> events = new ArrayList<>();

    @Override
    public void onRoute(Transaction2 txn,
        RegionAwareAdbReadRouter.ReadRouteKind kind,
        List<RegionMetadata> regions) {
      StringBuilder builder = new StringBuilder(kind.name()).append(':');
      for (int i = 0; i < regions.size(); i++) {
        if (i > 0) {
          builder.append(',');
        }
        builder.append(regions.get(i).getRegionId());
      }
      events.add(builder.toString());
    }
  }

  private static final class EmptyStore implements DbStore {
    @Override
    public byte[] get(byte[] key) {
      return null;
    }

    @Override
    public void put(byte[] key, byte[] value) {
    }

    @Override
    public long addLong(byte[] key, long operand) {
      return operand;
    }

    @Override
    public Optional<Long> getLong(byte[] key) {
      return Optional.empty();
    }

    @Override
    public void putLong(byte[] key, long value) {
    }

    @Override
    public void delete(byte[] key) {
    }

    @Override
    public void deleteRange(byte[] startKey, byte[] endKey) {
    }

    @Override
    public byte[] get(byte cfId, byte[] key) {
      return null;
    }

    @Override
    public void put(byte cfId, byte[] key, byte[] value) {
    }

    @Override
    public long addLong(byte cfId, byte[] key, long delta) {
      return delta;
    }

    @Override
    public Optional<Long> getLong(byte cfId, byte[] key) {
      return Optional.empty();
    }

    @Override
    public void putLong(byte cfId, byte[] key, long value) {
    }

    @Override
    public void delete(byte cfId, byte[] key) {
    }

    @Override
    public void deleteRange(byte cfId, byte[] startKey, byte[] endKey) {
    }

    @Override
    public void checkpoint(String targetDir) throws IOException {
    }

    @Override
    public void restore(String sourceDir) throws IOException {
    }

    @Override
    public void writeBatch(WriteBatchConsumer consumer) {
      throw new UnsupportedOperationException("writeBatch is not used");
    }

    @Override
    public void rollback(long txnId) {
    }

    @Override
    public CompletableFuture<Void> commitAsync(long txnId, long commitTs,
        List<Meta> metas) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public VersionScanSource openVersionScanSource(ScanDirection direction) {
      return new EmptyScanSource(direction);
    }

    @Override
    public VersionScanSource openVersionScanSource(byte cfId,
        ScanDirection direction) {
      return new EmptyScanSource(direction);
    }

    @Override
    public void close() throws IOException {
    }
  }

  private static final class EmptyScanSource implements VersionScanSource {
    private final ScanDirection direction;

    private EmptyScanSource(ScanDirection direction) {
      this.direction = direction;
    }

    @Override
    public ScanDirection direction() {
      return direction;
    }

    @Override
    public void seekToRangeStart(byte[] lowerInclusive,
        byte[] upperExclusive) {
    }

    @Override
    public boolean isValid() {
      return false;
    }

    @Override
    public byte[] key() {
      return null;
    }

    @Override
    public byte[] value() {
      return null;
    }

    @Override
    public void advance() {
    }

    @Override
    public void close() {
    }
  }
}
