package net.xdob.vexra.adb.db;

import java.io.File;
import java.sql.SQLException;
import java.util.Properties;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.adb.ldb.LdbStore;
import org.h2.value.ValueVarchar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TxnManager 生产范围 guard 测试。
 *
 * <p>测试覆盖没有 region commit coordinator 的本地提交路径：旧路径默认不安装 guard，
 * 显式生产配置安装后，commit 必须先经过单 region 事务能力校验。</p>
 */
class AdbTxnManagerProductionGuardTest {
  @TempDir
  File tempDir;

  /**
   * 验证坏的生产配置会在本地 durable commit 前拒绝事务。
   *
   * @throws Exception store 或事务写入失败时抛出
   */
  @Test
  void shouldRejectLocalCommitWhenProductionGuardIsRejected()
      throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "reject")
        .getAbsolutePath())) {
      TxnManager manager = new TxnManager(store);
      manager.setTxnRegionGuard(AdbCrossRegionTxnGuard.fromProductionGuard(
          rejectedClusterGuard()));
      RowKey key = rowKey(1);
      Transaction2 txn = manager.beginTransaction();
      manager.put(txn, key, rowValue("rejected"));
      long beforeCommit = manager.lastCommitTs();

      SQLException error = assertThrows(SQLException.class,
          () -> manager.commit(txn));

      assertTrue(error.getMessage().contains(
          "mvp cluster requires TLS, auth and least privilege"),
          error.getMessage());
      assertEquals(TxnState.PENDING, txn.getState());
      assertEquals(beforeCommit, manager.lastCommitTs());
    }
  }

  /**
   * 验证安全 2 data + witness 配置允许本地单 region 提交。
   *
   * @throws Exception store 或事务写入失败时抛出
   */
  @Test
  void shouldAllowLocalCommitWithSecureProductionGuard() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "allow")
        .getAbsolutePath())) {
      TxnManager manager = new TxnManager(store);
      manager.setTxnRegionGuard(AdbCrossRegionTxnGuard.fromProductionGuard(
          secureClusterGuard()));
      RowKey key = rowKey(2);
      Transaction2 txn = manager.beginTransaction();
      manager.put(txn, key, rowValue("committed"));

      manager.commit(txn);

      assertEquals(TxnState.COMMITTED, txn.getState());
      RowValue visible = manager.getVisible(manager.beginTransaction(), key);
      assertNotNull(visible);
      assertEquals("committed", RowCodec.decode(visible.payload).getString());
    }
  }

  private static AdbProductionGuard rejectedClusterGuard() {
    Properties properties = secureClusterProperties();
    properties.setProperty(AdbProductionGuard.AUTH_KEY, "false");
    return AdbProductionGuard.fromProperties(properties);
  }

  private static AdbProductionGuard secureClusterGuard() {
    return AdbProductionGuard.fromProperties(secureClusterProperties());
  }

  private static Properties secureClusterProperties() {
    Properties properties = new Properties();
    properties.setProperty(AdbProductionGuard.MODE_KEY, "mvp-cluster");
    properties.setProperty(AdbProductionGuard.TOPOLOGY_KEY, "2data1witness");
    properties.setProperty(AdbProductionGuard.TLS_KEY, "true");
    properties.setProperty(AdbProductionGuard.AUTH_KEY, "true");
    properties.setProperty(AdbProductionGuard.LEAST_PRIVILEGE_KEY, "true");
    return properties;
  }

  private static RowKey rowKey(long rowId) {
    return RowKey.of(TabId.of(1, 0L), rowId);
  }

  private static RowValue rowValue(String value) {
    RowValue rowValue = new RowValue();
    rowValue.payload = RowCodec.encode(ValueVarchar.get(value));
    return rowValue;
  }
}
