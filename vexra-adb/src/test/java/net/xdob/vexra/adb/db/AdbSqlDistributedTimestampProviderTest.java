package net.xdob.vexra.adb.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL 分布式临时时间戳 provider 测试。
 */
class AdbSqlDistributedTimestampProviderTest {
  /**
   * 验证固定 readTs 模式下写入时间戳位于 readTs 之前且保持单调。
   */
  @Test
  void shouldAllocateTimestampsBeforeFixedReadTimestamp() {
    AdbSqlDistributedTimestampProvider provider =
        new AdbSqlDistributedTimestampProvider(20000L);

    long startTs = provider.nextStartTimestamp();
    long commitTs = provider.nextCommitTimestamp();

    assertTrue(startTs < commitTs);
    assertTrue(commitTs < 20000L);
  }
}
