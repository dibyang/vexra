package net.xdob.vexra.adb.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB SQL 分布式 scan 配置测试。
 *
 * <p>该测试覆盖 table-engine `WITH` 参数到运行时配置的解析边界，尤其是远端 Raft scan
 * 需要显式声明 group 和 peers，避免只开启分布式 SQL 就误连远端集群。</p>
 */
class AdbSqlDistributedScanConfigTest {
  @TempDir
  Path tempDir;

  /**
   * 验证默认配置保持本地 scan，不改变旧表行为。
   */
  @Test
  void shouldDefaultToLocalScanClient() {
    AdbSqlDistributedScanConfig config =
        AdbSqlDistributedScanConfig.fromTableEngineParams(
            Collections.singletonList("adb.distributed.sql=true"));

    assertTrue(config.isEnabled());
    assertEquals("local", config.getScanClient());
    assertFalse(config.isRaftScanClient());
    assertEquals("local", config.getWriteClient());
    assertFalse(config.isRaftWriteClient());
    assertEquals(5000L, config.getTimeoutMillis());
    assertEquals(5000L, config.getWriteTimeoutMillis());
  }

  /**
   * 验证远端 Raft scan 参数会被完整解析。
   */
  @Test
  void shouldParseRaftScanClientParameters() {
    AdbSqlDistributedScanConfig config =
        AdbSqlDistributedScanConfig.fromTableEngineParams(Arrays.asList(
            "adb.distributed.sql=true",
            "adb.distributed.split.row=100",
            "adb.distributed.table.id=7",
            "adb.distributed.table.epoch=2",
            "adb.distributed.scan.timeoutMillis=30000",
            "adb.distributed.scan.readTs=20000",
            "adb.distributed.scan.client=raft",
            "adb.distributed.write.client=raft",
            "adb.distributed.write.timeoutMillis=31000",
            "adb.distributed.raft.group=group-1",
            "adb.distributed.raft.peers=n1@127.0.0.1:9001,n2@127.0.0.1:9002",
            "adb.distributed.raft.dbName=adb-test"));

    assertTrue(config.isRaftScanClient());
    assertTrue(config.isRaftWriteClient());
    assertEquals(Long.valueOf(100L), config.getSplitRowId());
    assertEquals(Integer.valueOf(7), config.getTableId());
    assertEquals(Long.valueOf(2L), config.getTableEpoch());
    assertEquals(30000L, config.getTimeoutMillis());
    assertEquals(31000L, config.getWriteTimeoutMillis());
    assertEquals(Long.valueOf(20000L), config.getReadTimestamp());
    assertEquals("group-1", config.getRaftGroup());
    assertEquals("n1@127.0.0.1:9001,n2@127.0.0.1:9002",
        config.getRaftPeers());
    assertEquals("adb-test", config.getRaftDbName());
  }

  /**
   * 验证共享 catalog 可以补齐 table id、epoch、Raft 目标和读时间戳。
   */
  @Test
  void shouldResolveSharedCatalogParameters() throws Exception {
    Path catalog = tempDir.resolve("adb-catalog.properties");
    Files.write(catalog, Arrays.asList(
        "adb.catalog.raft.group=group-1",
        "adb.catalog.raft.peers=n1@127.0.0.1:9001,n2@127.0.0.1:9002",
        "adb.catalog.raft.dbName=adb-test",
        "adb.catalog.tso.current=19000",
        "adb.catalog.tso.readDelay=1000",
        "adb.catalog.table.TEST.id=7",
        "adb.catalog.table.TEST.epoch=2"), StandardCharsets.UTF_8);

    AdbSqlDistributedScanConfig config =
        AdbSqlDistributedScanConfig.fromTableEngineParams(Arrays.asList(
            "adb.distributed.sql=true",
            "adb.distributed.scan.client=raft",
            "adb.distributed.write.client=raft",
            "adb.distributed.catalog.path="
                + catalog.toAbsolutePath().toString().replace('\\', '/')),
            "TEST");

    assertTrue(config.isRaftScanClient());
    assertTrue(config.isRaftWriteClient());
    assertEquals(Integer.valueOf(7), config.getTableId());
    assertEquals(Long.valueOf(2L), config.getTableEpoch());
    assertEquals(Long.valueOf(20000L), config.getReadTimestamp());
    assertEquals("group-1", config.getRaftGroup());
    assertEquals("n1@127.0.0.1:9001,n2@127.0.0.1:9002",
        config.getRaftPeers());
    assertEquals("adb-test", config.getRaftDbName());
  }

  /**
   * 验证远端模式缺少 Raft group 时会失败。
   */
  @Test
  void shouldRequireRaftGroupWhenRaftScanIsEnabled() {
    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> AdbSqlDistributedScanConfig.fromTableEngineParams(Arrays.asList(
            "adb.distributed.sql=true",
            "adb.distributed.scan.client=raft",
            "adb.distributed.raft.peers=n1@127.0.0.1:9001")));

    assertTrue(error.getMessage().contains("adb.distributed.raft.group"));
  }

  /**
   * 验证远端写入模式缺少 Raft group 时也会失败，避免只开启写入却没有目标集群。
   */
  @Test
  void shouldRequireRaftGroupWhenRaftWriteIsEnabled() {
    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> AdbSqlDistributedScanConfig.fromTableEngineParams(Arrays.asList(
            "adb.distributed.sql=true",
            "adb.distributed.write.client=raft",
            "adb.distributed.raft.peers=n1@127.0.0.1:9001")));

    assertTrue(error.getMessage().contains("adb.distributed.raft.group"));
  }

  /**
   * 验证未知 scan client 会失败，避免配置拼写错误静默降级。
   */
  @Test
  void shouldRejectUnknownScanClient() {
    assertThrows(IllegalArgumentException.class,
        () -> AdbSqlDistributedScanConfig.fromTableEngineParams(Arrays.asList(
            "adb.distributed.sql=true",
            "adb.distributed.scan.client=unknown")));
  }

  /**
   * 验证未知 write client 会失败，避免配置拼写错误时静默降级为本地写。
   */
  @Test
  void shouldRejectUnknownWriteClient() {
    assertThrows(IllegalArgumentException.class,
        () -> AdbSqlDistributedScanConfig.fromTableEngineParams(Arrays.asList(
            "adb.distributed.sql=true",
            "adb.distributed.write.client=unknown")));
  }
}
