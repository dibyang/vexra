package net.xdob.vexra.cluster.region;

import net.xdob.vexra.ha.VirtualNodeMetadata;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Region 元数据。
 *
 * <p>Region 是 TiDB-like 路线中的分片单元。该对象把 regionId、key range、
 * epoch 和虚节点副本元数据绑定在一起，供 range router、系统表和后续 region Raft
 * 存储层使用。</p>
 */
public final class RegionMetadata {
  private final String regionId;
  private final KeyRange range;
  private final long epoch;
  private final VirtualNodeMetadata replicaMetadata;

  /**
   * 创建 Region 元数据。
   *
   * @param regionId region 标识
   * @param range key range
   * @param epoch 路由 epoch
   * @param replicaMetadata 副本元数据
   */
  public RegionMetadata(String regionId, KeyRange range, long epoch,
      VirtualNodeMetadata replicaMetadata) {
    this.regionId = normalize(regionId, "regionId");
    this.range = Objects.requireNonNull(range, "range == null");
    if (epoch < 0) {
      throw new IllegalArgumentException("epoch is negative: " + epoch);
    }
    this.epoch = epoch;
    this.replicaMetadata = Objects.requireNonNull(replicaMetadata,
        "replicaMetadata == null");
  }

  public String getRegionId() {
    return regionId;
  }

  public KeyRange getRange() {
    return range;
  }

  public long getEpoch() {
    return epoch;
  }

  public VirtualNodeMetadata getReplicaMetadata() {
    return replicaMetadata;
  }

  /**
   * 判断指定 key 是否属于该 region。
   *
   * @param key 待路由 key
   * @return 命中该 region 返回 true
   */
  public boolean contains(byte[] key) {
    return range.contains(key);
  }

  /**
   * 转换为系统表友好的字符串行。
   *
   * @return 字段名到字符串值的有序映射
   */
  public Map<String, String> toSystemTableRow() {
    Map<String, String> row = new LinkedHashMap<>();
    row.put("region_id", regionId);
    row.put("start_key_hex", toHex(range.getStartKey()));
    row.put("end_key_hex", toHex(range.getEndKey()));
    row.put("epoch", Long.toString(epoch));
    row.put("virtual_node_id", replicaMetadata.getVirtualNodeId());
    row.put("leader_id", replicaMetadata.getLeaderId());
    row.put("voter_count", Integer.toString(replicaMetadata.voterCount()));
    row.put("has_witness", Boolean.toString(replicaMetadata.hasWitness()));
    return row;
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }

  private static String toHex(byte[] bytes) {
    StringBuilder builder = new StringBuilder();
    for (byte b : bytes) {
      builder.append(String.format("%02x", b & 0xff));
    }
    return builder.toString();
  }
}
