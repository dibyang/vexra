package net.xdob.vexra.adb.db;

import net.xdob.vexra.ha.ReplicaRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQL region replica 解析测试。
 */
class AdbSqlRegionReplicaSetTest {

  /**
   * 验证 SQL runtime 使用真实 Raft peer id 生成 leader 和 replica。
   */
  @Test
  void shouldParseReplicaIdsFromRaftPeers() {
    AdbSqlRegionReplicaSet replicas = AdbSqlRegionReplicaSet.parse(
        "n1@127.0.0.1:19001,n2@127.0.0.1:19002,n3@127.0.0.1:19003");

    assertEquals("n1", replicas.leaderId(0));
    assertEquals("n2", replicas.leaderId(1));
    assertEquals("n3", replicas.leaderId(99));
    assertEquals(3, replicas.replicas().size());
    assertEquals("n1", replicas.replicas().get(0).getReplicaId());
    assertEquals(ReplicaRole.DATA_VOTER,
        replicas.replicas().get(0).getRole());
    assertEquals("n2", replicas.replicas().get(1).getReplicaId());
    assertEquals(ReplicaRole.DATA_VOTER,
        replicas.replicas().get(1).getRole());
    assertEquals("n3", replicas.replicas().get(2).getReplicaId());
    assertEquals(ReplicaRole.WITNESS_VOTER,
        replicas.replicas().get(2).getRole());
  }

  /**
   * 验证空 peer 配置会被拒绝。
   */
  @Test
  void shouldRejectEmptyPeers() {
    assertThrows(IllegalArgumentException.class,
        () -> AdbSqlRegionReplicaSet.parse(" , "));
  }

  /**
   * 验证本地 distributed plan 保留旧的诊断副本名称。
   */
  @Test
  void shouldProvideLocalDefaultReplicaIds() {
    AdbSqlRegionReplicaSet replicas = AdbSqlRegionReplicaSet.localDefault();

    assertEquals("sql-node-a", replicas.leaderId(0));
    assertEquals("sql-node-b", replicas.leaderId(1));
    assertEquals("sql-witness", replicas.leaderId(2));
  }
}
