package net.xdob.vexra.adb.ldb;

import net.xdob.vexra.adb.db.CF;
import net.xdob.vexra.adb.db.Meta;
import net.xdob.vexra.adb.db.RowValue;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.adb.key.TxnKeyType;
import net.xdob.vexra.adb.key.TxnRefKey;
import net.xdob.vexra.adb.key.VersionKey;
import net.xdob.vexra.adb.key.VersionRowKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADB 基于 LDB 存储的可靠性回归测试。
 *
 * 这些用例聚焦本地 LdbStore 的 checkpoint/restore、双槽切换、事务临时版本清理
 * 和元数据持久化，不启动网络、Raft 或远程 RPC。
 */
class LdbStoreReliabilityTest {
  @TempDir
  File tempDir;

  /**
   * 验证从 checkpoint restore 后会切换到备份数据，并且 ACTIVE 槽位在重新打开后仍然有效。
   */
  @Test
  void shouldRestoreCheckpointAndKeepActiveSlotAfterReopen() throws Exception {
    File storeDir = new File(tempDir, "store");
    File checkpointDir = new File(tempDir, "checkpoint");

    try (LdbStore store = new LdbStore(storeDir.getAbsolutePath())) {
      store.put(bytes("k1"), bytes("checkpoint-value"));
      store.checkpoint(checkpointDir.getAbsolutePath());
      store.put(bytes("k1"), bytes("source-new-value"));
      assertArrayEquals(bytes("source-new-value"), store.get(bytes("k1")));

      store.restore(checkpointDir.getAbsolutePath());
      assertArrayEquals(bytes("checkpoint-value"), store.get(bytes("k1")));
    }

    try (LdbStore reopened = new LdbStore(storeDir.getAbsolutePath())) {
      assertArrayEquals(bytes("checkpoint-value"), reopened.get(bytes("k1")));
    }
  }

  /**
   * 验证 restore 源目录无效时不会切换 ACTIVE，也不会破坏当前可用数据。
   */
  @Test
  void shouldKeepCurrentStoreWhenRestoreSourceIsInvalid() throws Exception {
    File storeDir = new File(tempDir, "invalid-restore-store");
    File invalidCheckpointDir = new File(tempDir, "invalid-checkpoint");
    assertTrue(invalidCheckpointDir.mkdirs());
    Files.write(new File(invalidCheckpointDir, "marker").toPath(),
        Collections.singletonList("not a checkpoint"), UTF_8);

    try (LdbStore store = new LdbStore(storeDir.getAbsolutePath())) {
      store.put(bytes("live"), bytes("value"));

      IOException error = assertThrows(IOException.class,
          () -> store.restore(invalidCheckpointDir.getAbsolutePath()));

      assertTrue(error.getMessage().contains("missing CURRENT")
          || error.getMessage().contains("Dual-slot restore failed"));
      assertArrayEquals(bytes("value"), store.get(bytes("live")));
    }

    try (LdbStore reopened = new LdbStore(storeDir.getAbsolutePath())) {
      assertArrayEquals(bytes("value"), reopened.get(bytes("live")));
    }
  }

  /**
   * 验证 rollback 会删除事务引用和未提交临时版本，避免恢复后重复处理同一事务。
   */
  @Test
  void shouldRemoveTemporaryVersionAndTxnRefOnRollback() throws Exception {
    File storeDir = new File(tempDir, "rollback-store");
    long txnId = 101L;
    VersionRowKey uncommittedKey = VersionRowKey.of(TabId.of(1, 1L), 10L, false, txnId);
    TxnRefKey txnRefKey = TxnRefKey.of(txnId, TxnKeyType.WRITE_REF, CF.DEFAULT.getCfId(), uncommittedKey);

    try (LdbStore store = new LdbStore(storeDir.getAbsolutePath())) {
      store.put(uncommittedKey.toBytes(), rowValue(txnId, 0L, "pending"));
      store.put(CF.TXN.getCfId(), txnRefKey.toBytes(), bytes("1"));

      store.rollback(txnId);

      assertNull(store.get(uncommittedKey.toBytes()));
      assertNull(store.get(CF.TXN.getCfId(), txnRefKey.toBytes()));
    }
  }

  /**
   * 验证 commitAsync 会把未提交版本转为提交版本，清理事务引用并写入 meta。
   */
  @Test
  void shouldCommitTemporaryVersionAndMetaAtomically() throws Exception {
    File storeDir = new File(tempDir, "commit-store");
    long txnId = 202L;
    long commitTs = 303L;
    VersionRowKey uncommittedKey = VersionRowKey.of(TabId.of(2, 1L), 20L, false, txnId);
    VersionKey committedKey = VersionKey.of(uncommittedKey, true, commitTs);
    TxnRefKey txnRefKey = TxnRefKey.of(txnId, TxnKeyType.WRITE_REF, CF.DEFAULT.getCfId(), uncommittedKey);

    try (LdbStore store = new LdbStore(storeDir.getAbsolutePath())) {
      store.put(uncommittedKey.toBytes(), rowValue(txnId, 0L, "pending"));
      store.put(CF.TXN.getCfId(), txnRefKey.toBytes(), bytes("1"));

      store.commitAsync(txnId, commitTs,
          Collections.singletonList(Meta.of(bytes("schema-version"), bytes("v1"))))
          .get(5, TimeUnit.SECONDS);

      assertNull(store.get(uncommittedKey.toBytes()));
      assertNull(store.get(CF.TXN.getCfId(), txnRefKey.toBytes()));
      RowValue committed = RowValue.decodeValue(store.get(committedKey.toBytes()));
      assertNotNull(committed);
      assertEquals(txnId, committed.txnId);
      assertEquals(commitTs, committed.commitTs);
      assertArrayEquals(bytes("pending"), committed.payload);
      assertArrayEquals(bytes("v1"), store.get(CF.META.getCfId(), bytes("schema-version")));
    }
  }

  /**
   * 验证 close 后拒绝继续读写，避免调用方误用已关闭的底层 LDB。
   */
  @Test
  void shouldRejectOperationsAfterClose() throws Exception {
    File storeDir = new File(tempDir, "closed-store");
    LdbStore store = new LdbStore(storeDir.getAbsolutePath());
    store.put(bytes("k"), bytes("v"));
    store.close();

    assertThrows(Exception.class, () -> store.get(bytes("k")));
    assertThrows(Exception.class, () -> store.put(bytes("k2"), bytes("v2")));
  }

  /**
   * 生成 ADB 行值，测试只关心事务时间戳和 payload 是否被正确保留。
   */
  private static byte[] rowValue(long txnId, long commitTs, String payload) {
    RowValue value = new RowValue();
    value.txnId = txnId;
    value.commitTs = commitTs;
    value.deleted = false;
    value.payload = bytes(payload);
    return RowValue.encodeValue(value);
  }

  /**
   * 将字符串转成 UTF-8 字节，保证测试数据编码稳定。
   */
  private static byte[] bytes(String value) {
    return value.getBytes(UTF_8);
  }
}
