package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.key.RowPrefix;
import net.xdob.vexra.adb.key.TabId;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedSeekRegressionTest {

  @Test
  void tableScanCursorShouldUseClosedBoundWhenMaxRowIdExists() {
    RowPrefix prefix = RowPrefix.of(TabId.of(1, 1L));
    RecordingScanSource scan = new RecordingScanSource(ScanDirection.FORWARD);

    new TableScanCursor(new Transaction2(1L, 1L), scan, null, prefix, 10L,
        20L);

    assertTrue(scan.closedSeek);
    assertFalse(scan.nullToNullSeek);
    assertArrayEquals(TxnManager.buildRowSeekKey(prefix, 10L),
        scan.lowerInclusive);
    assertNotNull(scan.upperInclusive);
  }

  @Test
  void tableScanCursorShouldUseTablePrefixBoundWhenMaxRowIdIsMissing() {
    RowPrefix prefix = RowPrefix.of(TabId.of(1, 1L));
    RecordingScanSource scan = new RecordingScanSource(ScanDirection.FORWARD);

    new TableScanCursor(new Transaction2(1L, 1L), scan, null, prefix, 10L,
        null);

    assertFalse(scan.closedSeek);
    assertFalse(scan.nullToNullSeek);
    assertArrayEquals(TxnManager.buildRowSeekKey(prefix, 10L),
        scan.lowerInclusive);
    assertArrayEquals(KeyCodec.prefixEnd(prefix.toBytes()),
        scan.upperExclusive);
  }

  @Test
  void visibleIndexResolverShouldSeekByLogicalPrefix() {
    byte[] logicalPrefix = new byte[] {1, 2, 3};
    RecordingScanSource scan = new RecordingScanSource(ScanDirection.FORWARD);
    DefaultVisibleIndexResolver resolver =
        new DefaultVisibleIndexResolver(storeReturning(scan));

    resolver.getVisibleIndex(new Transaction2(1L, 1L), logicalPrefix);

    assertFalse(scan.closedSeek);
    assertFalse(scan.nullToNullSeek);
    assertArrayEquals(logicalPrefix, scan.lowerInclusive);
    assertArrayEquals(KeyCodec.prefixEnd(logicalPrefix), scan.upperExclusive);
  }

  private static DbStore storeReturning(RecordingScanSource scan) {
    return (DbStore) Proxy.newProxyInstance(DbStore.class.getClassLoader(),
        new Class<?>[] {DbStore.class}, (proxy, method, args) -> {
          if ("openVersionScanSource".equals(method.getName())) {
            return scan;
          }
          if ("close".equals(method.getName())) {
            return null;
          }
          throw new UnsupportedOperationException(method.toString());
        });
  }

  private static final class RecordingScanSource implements VersionScanSource {
    private final ScanDirection direction;
    private byte[] lowerInclusive;
    private byte[] upperExclusive;
    private byte[] upperInclusive;
    private boolean closedSeek;
    private boolean nullToNullSeek;

    private RecordingScanSource(ScanDirection direction) {
      this.direction = direction;
    }

    @Override
    public ScanDirection direction() {
      return direction;
    }

    @Override
    public void seekToRangeStart(byte[] lowerInclusive,
        byte[] upperExclusive) {
      this.lowerInclusive = lowerInclusive;
      this.upperExclusive = upperExclusive;
      this.nullToNullSeek = lowerInclusive == null && upperExclusive == null;
    }

    @Override
    public void seekToRangeClosed(byte[] lowerInclusive,
        byte[] upperInclusive) {
      this.closedSeek = true;
      this.lowerInclusive = lowerInclusive;
      this.upperInclusive = upperInclusive;
      this.nullToNullSeek = lowerInclusive == null && upperInclusive == null;
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
