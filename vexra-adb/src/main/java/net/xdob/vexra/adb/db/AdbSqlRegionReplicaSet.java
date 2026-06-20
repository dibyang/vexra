package net.xdob.vexra.adb.db;

import net.xdob.vexra.ha.ReplicaRole;
import net.xdob.vexra.ha.VirtualNodeReplica;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SQL 分布式 runtime 使用的 region 副本视图。
 *
 * <p>SQL runtime 必须使用 `adb.distributed.raft.peers` 中的真实 peer id 构造
 * region 元数据，避免 commit 请求携带不存在的 leaderId。</p>
 */
final class AdbSqlRegionReplicaSet {
  private final List<String> peerIds;
  private final List<VirtualNodeReplica> replicas;

  private AdbSqlRegionReplicaSet(List<String> peerIds,
      List<VirtualNodeReplica> replicas) {
    this.peerIds = Collections.unmodifiableList(peerIds);
    this.replicas = Collections.unmodifiableList(replicas);
  }

  /**
   * 从 `node@host:port` 形式的 peer 配置解析副本视图。
   *
   * @param peersConfig Raft peer 配置
   * @return SQL region 副本视图
   */
  static AdbSqlRegionReplicaSet parse(String peersConfig) {
    List<String> ids = parsePeerIds(peersConfig);
    return fromPeerIds(ids);
  }

  /**
   * 创建本地 distributed plan 使用的默认副本视图。
   *
   * @return 默认副本视图
   */
  static AdbSqlRegionReplicaSet localDefault() {
    List<String> ids = new ArrayList<>();
    ids.add("sql-node-a");
    ids.add("sql-node-b");
    ids.add("sql-witness");
    return fromPeerIds(ids);
  }

  private static AdbSqlRegionReplicaSet fromPeerIds(List<String> ids) {
    List<VirtualNodeReplica> replicas = new ArrayList<>();
    for (int i = 0; i < ids.size(); i++) {
      ReplicaRole role = i < 2 ? ReplicaRole.DATA_VOTER
          : ReplicaRole.WITNESS_VOTER;
      replicas.add(new VirtualNodeReplica(ids.get(i), role));
    }
    return new AdbSqlRegionReplicaSet(ids, replicas);
  }

  /**
   * 返回指定 region 序号对应的 leader id。
   *
   * @param regionIndex 0-based region 序号
   * @return leader id
   */
  String leaderId(int regionIndex) {
    int index = Math.min(Math.max(regionIndex, 0), peerIds.size() - 1);
    return peerIds.get(index);
  }

  /**
   * 返回不可变 replica 列表。
   *
   * @return replica 列表
   */
  List<VirtualNodeReplica> replicas() {
    return replicas;
  }

  private static List<String> parsePeerIds(String peersConfig) {
    if (peersConfig == null || peersConfig.trim().isEmpty()) {
      throw new IllegalArgumentException("raft peers config is empty");
    }
    String[] parts = peersConfig.split(",");
    List<String> ids = new ArrayList<>();
    for (String part : parts) {
      String text = part.trim();
      if (text.isEmpty()) {
        continue;
      }
      int at = text.indexOf('@');
      String id = at < 0 ? text : text.substring(0, at);
      if (id.trim().isEmpty()) {
        throw new IllegalArgumentException("raft peer id is empty: " + part);
      }
      ids.add(id.trim());
    }
    if (ids.isEmpty()) {
      throw new IllegalArgumentException("raft peers config has no peer id");
    }
    return ids;
  }
}
