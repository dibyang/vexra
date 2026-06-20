package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 基于 {@link DbStore} 的 durable commit marker 持久化记录器。
 *
 * <p>该实现把 marker 写入 `CF.TXN` 的专用前缀下，不修改 ADB 业务数据格式。它用于
 * ADB-GA-02 的进程重启恢复雏形：commit 路径写入 marker，节点重启后通过
 * {@link #snapshot()} 扫描 in-doubt marker，再交给 {@link AdbCommitRecoveryScanner}
 * 生成恢复动作。</p>
 */
public final class AdbPersistentDurableCommitRecorder
    implements AdbDurableCommitRecorder {
  private static final int VALUE_VERSION = 1;
  private static final byte[] KEY_PREFIX =
      new byte[] {0x41, 0x44, 0x42, 0x5f, 0x43, 0x4d, 0x5f, 0x31, 0x00};

  private final DbStore store;

  /**
   * 创建持久化 durable commit 记录器。
   *
   * @param store ADB 底层 store
   */
  public AdbPersistentDurableCommitRecorder(DbStore store) {
    this.store = Objects.requireNonNull(store, "store == null");
  }

  @Override
  public AdbDurableCommitMarker prewritten(AdbRegionCommitRequest request)
      throws SQLException {
    Objects.requireNonNull(request, "request == null");
    AdbDurableCommitMarker marker = new AdbDurableCommitMarker(
        request.getTxnId(), "", request.getStartTs(), request.getCommitTs(),
        request.getRegionId(), AdbDurableCommitState.PREWRITTEN, "");
    return recordOrGet(marker);
  }

  @Override
  public AdbDurableCommitMarker raftCommitted(AdbDurableCommitMarker marker)
      throws SQLException {
    return advance(marker, AdbDurableCommitState.RAFT_COMMITTED, null);
  }

  @Override
  public AdbDurableCommitMarker storeCommitted(AdbDurableCommitMarker marker)
      throws SQLException {
    return advance(marker, AdbDurableCommitState.STORE_COMMITTED, null);
  }

  @Override
  public AdbDurableCommitMarker replied(AdbDurableCommitMarker marker)
      throws SQLException {
    return advance(marker, AdbDurableCommitState.REPLIED, null);
  }

  @Override
  public AdbDurableCommitMarker rolledBack(AdbDurableCommitMarker marker,
      Throwable error) throws SQLException {
    String message = error == null || error.getMessage() == null
        ? "" : error.getMessage();
    return advance(marker, AdbDurableCommitState.ROLLED_BACK, message);
  }

  /**
   * 扫描当前持久化 marker 快照。
   *
   * @return marker 快照，按底层 key 顺序返回
   * @throws SQLException 读取或解码失败
   */
  public Collection<AdbDurableCommitMarker> snapshot() throws SQLException {
    List<AdbDurableCommitMarker> markers = new ArrayList<>();
    byte[] end = KeyCodec.prefixEnd(KEY_PREFIX);
    try (VersionScanSource scan = store.openVersionScanSource(
        CF.TXN.getCfId(), ScanDirection.FORWARD)) {
      scan.seekToRangeStart(KEY_PREFIX, end);
      while (scan.isValid() && KeyCodec.startsWith(scan.key(), KEY_PREFIX)) {
        markers.add(decode(scan.value()));
        scan.advance();
      }
    } catch (RuntimeException e) {
      throw new SQLException("Failed to scan ADB durable commit markers", e);
    } catch (Exception e) {
      throw new SQLException("Failed to close ADB durable commit marker scan",
          e);
    }
    return Collections.unmodifiableList(markers);
  }

  private AdbDurableCommitMarker recordOrGet(AdbDurableCommitMarker marker)
      throws SQLException {
    byte[] key = key(marker);
    byte[] existingBytes = store.get(CF.TXN.getCfId(), key);
    if (existingBytes == null) {
      store.put(CF.TXN.getCfId(), key, encode(marker));
      return marker;
    }
    AdbDurableCommitMarker existing = decode(existingBytes);
    if (existing.getTxnId() != marker.getTxnId()
        || existing.getCommitTs() != marker.getCommitTs()
        || !existing.getRegionId().equals(marker.getRegionId())) {
      throw new SQLException("ADB duplicate durable commit marker conflict: "
          + AdbCommitIdempotencyStore.storageKey(marker));
    }
    return existing;
  }

  private void update(AdbDurableCommitMarker marker) throws SQLException {
    store.put(CF.TXN.getCfId(), key(marker), encode(marker));
  }

  private AdbDurableCommitMarker advance(AdbDurableCommitMarker marker,
      AdbDurableCommitState next, String error) throws SQLException {
    if (marker == null) {
      return null;
    }
    if (isAtOrAfter(marker.getState(), next)) {
      return marker;
    }
    AdbDurableCommitMarker advanced = error == null
        ? marker.transitionTo(next) : marker.transitionTo(next, error);
    update(advanced);
    return advanced;
  }

  private static byte[] key(AdbDurableCommitMarker marker) {
    byte[] suffix = AdbCommitIdempotencyStore.storageKey(marker)
        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] key = new byte[KEY_PREFIX.length + suffix.length];
    System.arraycopy(KEY_PREFIX, 0, key, 0, KEY_PREFIX.length);
    System.arraycopy(suffix, 0, key, KEY_PREFIX.length, suffix.length);
    return key;
  }

  private static byte[] encode(AdbDurableCommitMarker marker)
      throws SQLException {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bytes);
      out.writeInt(VALUE_VERSION);
      out.writeLong(marker.getTxnId());
      out.writeUTF(marker.getClientRequestId());
      out.writeLong(marker.getStartTs());
      out.writeLong(marker.getCommitTs());
      out.writeUTF(marker.getRegionId());
      out.writeUTF(marker.getState().name());
      out.writeUTF(marker.getLastError());
      out.flush();
      return bytes.toByteArray();
    } catch (IOException e) {
      throw new SQLException("Failed to encode ADB durable commit marker", e);
    }
  }

  private static AdbDurableCommitMarker decode(byte[] bytes)
      throws SQLException {
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
      int version = in.readInt();
      if (version != VALUE_VERSION) {
        throw new SQLException("Unsupported ADB durable commit marker version: "
            + version);
      }
      long txnId = in.readLong();
      String clientRequestId = in.readUTF();
      long startTs = in.readLong();
      long commitTs = in.readLong();
      String regionId = in.readUTF();
      AdbDurableCommitState state = AdbDurableCommitState.valueOf(
          in.readUTF());
      String lastError = in.readUTF();
      return new AdbDurableCommitMarker(txnId, clientRequestId, startTs,
          commitTs, regionId, state, lastError);
    } catch (IOException e) {
      throw new SQLException("Failed to decode ADB durable commit marker", e);
    } catch (RuntimeException e) {
      throw new SQLException("Invalid ADB durable commit marker", e);
    }
  }

  private static boolean isAtOrAfter(AdbDurableCommitState current,
      AdbDurableCommitState next) {
    if (current == next) {
      return true;
    }
    if (current == AdbDurableCommitState.ROLLED_BACK) {
      return true;
    }
    if (next == AdbDurableCommitState.ROLLED_BACK) {
      return false;
    }
    return rank(current) >= rank(next);
  }

  private static int rank(AdbDurableCommitState state) {
    switch (state) {
      case PREWRITTEN:
        return 1;
      case RAFT_COMMITTED:
        return 2;
      case STORE_COMMITTED:
        return 3;
      case REPLIED:
        return 4;
      case ROLLED_BACK:
      default:
        return 0;
    }
  }
}
