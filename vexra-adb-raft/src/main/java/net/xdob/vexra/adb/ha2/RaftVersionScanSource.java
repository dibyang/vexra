package net.xdob.vexra.adb.ha2;


import com.google.protobuf.ByteString;
import net.xdob.vexra.adb.db.CF;
import net.xdob.vexra.adb.db.ScanDirection;
import net.xdob.vexra.adb.db.VersionScanSource;
import net.xdob.vexra.proto.adb.*;


import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 基于 Raft ReadRequest/ReadResponse 的版本扫描源实现。
 *
 * 语义：
 * 1. seekToRangeStart(lowerInclusive, upperExclusive) 会发起首次分页扫描
 * 2. 当前游标始终指向当前页的 index 位置
 * 3. advance() 自动在页内移动；页读完且 hasMore=true 时自动拉下一页
 * 4. close() 仅释放本地状态，不关闭外部传入的 RaftClient
 */
public class RaftVersionScanSource implements VersionScanSource {

  /**
   * 每次从 raft 侧拉取多少条。
   * 可以后续改成构造参数，或者按你的读放大情况调整。
   */
  private static final int PAGE_SIZE = 256;

  private final RClient client;
  private final String dbName;
  private final ScanDirection direction;

  private final CF cf;

  /** 当前扫描范围下界（包含） */
  private byte[] lowerInclusive;

  /** 当前扫描范围上界（排他） */
  private byte[] upperExclusive;

  /** 当前页数据 */
  private List<KvPair> entries = Collections.emptyList();

  /** 当前页游标位置 */
  private int index = -1;

  /** 服务端是否还有更多数据 */
  private boolean hasMore = false;

  /** 下一页续扫 key */
  private byte[] resumeKey;

  /** 是否已经 close */
  private boolean closed = false;

  public RaftVersionScanSource(String dbName, RClient client, CF cf, ScanDirection direction) {
    this.client = Objects.requireNonNull(client, "client");
    this.dbName = Objects.requireNonNull(dbName, "dbName");
    this.cf = Objects.requireNonNull(cf, "cf");
    this.direction = Objects.requireNonNull(direction, "direction");
  }

  @Override
  public ScanDirection direction() {
    return direction;
  }

  @Override
  public void seekToRangeStart(byte[] lowerInclusive, byte[] upperExclusive) {
    ensureOpen();

    this.lowerInclusive = copy(lowerInclusive);
    this.upperExclusive = copy(upperExclusive);
    this.resumeKey = null;

    loadFirstPage();
  }

  @Override
  public boolean isValid() {
    ensureOpen();
    return index >= 0 && index < entries.size();
  }

  @Override
  public byte[] key() {
    ensureOpen();
    if (!isValid()) {
      return null;
    }
    return entries.get(index).getKey().toByteArray();
  }

  @Override
  public byte[] value() {
    ensureOpen();
    if (!isValid()) {
      return null;
    }
    return entries.get(index).getValue().toByteArray();
  }

  @Override
  public void advance() {
    ensureOpen();

    if (!isValid()) {
      return;
    }

    index++;

    if (index < entries.size()) {
      return;
    }

    if (!hasMore) {
      // 当前页已经读完，且服务端没有更多了
      entries = Collections.emptyList();
      index = -1;
      return;
    }

    loadNextPage();
  }

  @Override
  public void close() {
    closed = true;
    lowerInclusive = null;
    upperExclusive = null;
    resumeKey = null;
    entries = Collections.emptyList();
    index = -1;
    hasMore = false;
  }

  // ==========================
  // internal
  // ==========================

  private void loadFirstPage() {
    Scan scan = buildScanRequest(lowerInclusive, upperExclusive, null, PAGE_SIZE, direction);

    ReadRequest request = ReadRequest.newBuilder()
        .setDbName(dbName)
        .setScan(scan)
        .build();

    ScanResult result = sendScan(request);
    applyScanResult(result);
  }

  private void loadNextPage() {
    Scan scan = buildScanRequest(lowerInclusive, upperExclusive, resumeKey, PAGE_SIZE, direction);

    ReadRequest request = ReadRequest.newBuilder()
        .setDbName(dbName)
        .setScan(scan)
        .build();

    ScanResult result = sendScan(request);
    applyScanResult(result);
  }

  private void applyScanResult(ScanResult result) {
    this.entries = result.getEntriesList();
    this.hasMore = result.getHasMore();
    this.resumeKey = result.getResumeKey().isEmpty() ? null : result.getResumeKey().toByteArray();
    this.index = entries.isEmpty() ? -1 : 0;
  }

  private Scan buildScanRequest(
      byte[] startKey,
      byte[] endKey,
      byte[] resumeKey,
      int limit,
      ScanDirection direction) {

    Scan.Builder builder = Scan.newBuilder()
        .setCf(ColumnFamily.forNumber(cf.getCfId()))
        .setLimit(limit)
        .setDirection(toProtoDirection(direction));

    if (startKey != null && startKey.length > 0) {
      builder.setStartKey(ByteString.copyFrom(startKey));
    }
    if (endKey != null && endKey.length > 0) {
      builder.setEndKey(ByteString.copyFrom(endKey));
    }
    if (resumeKey != null && resumeKey.length > 0) {
      builder.setResumeKey(ByteString.copyFrom(resumeKey));
    }

    return builder.build();
  }

  private ScanResult sendScan(ReadRequest request) {
    try {
      ReadResponse response = client.sendReadRequest(request);
      if (!response.hasScanResult()) {
        throw new IllegalStateException("Unexpected read response, scanResult expected but got: " + response.getRespCase());
      }

      return response.getScanResult();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to execute raft scan", e);
    }
  }


  private Direction toProtoDirection(ScanDirection direction) {
    switch (direction) {
      case FORWARD:
        return Direction.DIR_FORWARD;
      case REVERSE:
        return Direction.DIR_REVERSE;
      default:
        return Direction.DIR_UNSPECIFIED;
    }
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("RaftVersionScanSource already closed");
    }
  }

  private static byte[] copy(byte[] bytes) {
    if (bytes == null) {
      return null;
    }
    byte[] r = new byte[bytes.length];
    System.arraycopy(bytes, 0, r, 0, bytes.length);
    return r;
  }
}
