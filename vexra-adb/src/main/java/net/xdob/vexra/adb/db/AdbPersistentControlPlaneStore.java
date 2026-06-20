package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.ha.ReplicaRole;
import net.xdob.vexra.ha.VirtualNodeMetadata;
import net.xdob.vexra.ha.VirtualNodeReplica;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于本地 `DbStore` 的 ADB 轻量控制面持久化 store。
 *
 * <p>该实现是 GA-03 的第一批最小闭环：把节点心跳、region 快照、route epoch
 * 和 TSO 写入 `CF.META` 的 `adb.cp.*` 命名空间。它不改变业务表和事务数据格式，
 * 也不提供跨进程复制；后续 PD-like 服务可以复用这里的记录模型并替换复制边界。</p>
 */
public final class AdbPersistentControlPlaneStore
    implements AdbRouteSnapshotPublisher {
  private static final byte[] NODES_KEY = key("adb.cp.nodes.v1");
  private static final byte[] REGIONS_KEY = key("adb.cp.regions.v1");
  private static final byte[] ROUTE_EPOCH_KEY = key("adb.cp.routeEpoch.v1");
  private static final byte[] TSO_KEY = key("adb.cp.tso.v1");
  private static final int NODES_MAGIC = 0x4143504e;
  private static final int REGIONS_MAGIC = 0x41435052;
  private static final int VERSION = 1;

  private final DbStore store;
  private final long initialTimestamp;

  /**
   * 创建持久化控制面 store。
   *
   * @param store 底层 ADB store
   * @param initialTimestamp 首次 TSO 分配前允许写入的最小持久化时间戳
   */
  public AdbPersistentControlPlaneStore(DbStore store,
      long initialTimestamp) {
    this.store = Objects.requireNonNull(store, "store == null");
    if (initialTimestamp < 0) {
      throw new IllegalArgumentException("initialTimestamp is negative: "
          + initialTimestamp);
    }
    this.initialTimestamp = initialTimestamp;
  }

  /**
   * 持久化节点心跳，并将节点状态更新为 UP。
   *
   * @param heartbeat 节点心跳
   * @throws SQLException 底层读取、写入或解码失败时抛出
   */
  public synchronized void heartbeat(AdbNodeHeartbeat heartbeat)
      throws SQLException {
    AdbControlPlaneNodeRecord record = Objects.requireNonNull(heartbeat,
        "heartbeat == null").toUpRecord();
    persistNodeRecord(record);
  }

  /**
   * 写入或替换控制面节点记录。
   *
   * @param record 节点记录
   * @throws SQLException 底层读取、写入或解码失败时抛出
   */
  public synchronized void persistNodeRecord(
      AdbControlPlaneNodeRecord record) throws SQLException {
    Objects.requireNonNull(record, "record == null");
    List<AdbControlPlaneNodeRecord> nodes = new ArrayList<>(listNodes());
    int index = findNode(nodes, record.getNodeId());
    if (index >= 0) {
      nodes.set(index, record);
    } else {
      nodes.add(record);
    }
    persistNodes(nodes);
  }

  /**
   * 读取指定节点记录。
   *
   * @param nodeId 节点唯一标识
   * @return 找到时返回节点记录，否则返回空
   * @throws SQLException 底层读取或解码失败时抛出
   */
  public synchronized Optional<AdbControlPlaneNodeRecord> getNode(
      String nodeId) throws SQLException {
    String normalized = normalize(nodeId, "nodeId");
    for (AdbControlPlaneNodeRecord record : listNodes()) {
      if (record.getNodeId().equals(normalized)) {
        return Optional.of(record);
      }
    }
    return Optional.empty();
  }

  /**
   * 读取全部控制面节点记录。
   *
   * @return 不可变节点记录列表
   * @throws SQLException 底层读取或解码失败时抛出
   */
  public synchronized List<AdbControlPlaneNodeRecord> listNodes()
      throws SQLException {
    byte[] value = store.get(CF.META.getCfId(), NODES_KEY);
    if (value == null) {
      return Collections.emptyList();
    }
    return Collections.unmodifiableList(decodeNodes(value));
  }

  /**
   * 发布新的 region 快照并推进 route epoch。
   *
   * @param newRegions 新 region 元数据集合
   * @return 发布后的 route epoch
   */
  @Override
  public synchronized long publishRegions(
      Collection<RegionMetadata> newRegions) {
    try {
      List<RegionMetadata> copy = copyRegions(newRegions);
      long nextEpoch = readRouteEpoch() + 1;
      store.put(CF.META.getCfId(), REGIONS_KEY, encodeRegions(copy));
      store.putLong(CF.META.getCfId(), ROUTE_EPOCH_KEY, nextEpoch);
      return nextEpoch;
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to publish control-plane "
          + "regions", e);
    }
  }

  /**
   * 读取当前持久化路由快照。
   *
   * @return 当前 route epoch 与 region 元数据快照
   */
  @Override
  public synchronized AdbControlPlaneSnapshot getSnapshot() {
    try {
      List<RegionMetadata> regions = readRegions();
      return new AdbControlPlaneSnapshot(readRouteEpoch(), regions);
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to read control-plane "
          + "snapshot", e);
    }
  }

  /**
   * 分配全局单调时间戳。
   *
   * @return 新分配的时间戳
   */
  @Override
  public synchronized long nextTimestamp() {
    try {
      ensureTimestampInitialized();
      return store.addLong(CF.META.getCfId(), TSO_KEY, 1);
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to allocate control-plane "
          + "timestamp", e);
    }
  }

  private void ensureTimestampInitialized() throws SQLException {
    Optional<Long> current = store.getLong(CF.META.getCfId(), TSO_KEY);
    if (!current.isPresent() || current.get() < initialTimestamp) {
      store.putLong(CF.META.getCfId(), TSO_KEY, initialTimestamp);
    }
  }

  private long readRouteEpoch() throws SQLException {
    Optional<Long> value = store.getLong(CF.META.getCfId(), ROUTE_EPOCH_KEY);
    return value.orElse(0L);
  }

  private List<RegionMetadata> readRegions() throws SQLException {
    byte[] value = store.get(CF.META.getCfId(), REGIONS_KEY);
    if (value == null) {
      throw new SQLException("Control-plane regions are not initialized");
    }
    return decodeRegions(value);
  }

  private void persistNodes(List<AdbControlPlaneNodeRecord> nodes)
      throws SQLException {
    store.put(CF.META.getCfId(), NODES_KEY, encodeNodes(nodes));
  }

  private static int findNode(List<AdbControlPlaneNodeRecord> nodes,
      String nodeId) {
    for (int i = 0; i < nodes.size(); i++) {
      if (nodes.get(i).getNodeId().equals(nodeId)) {
        return i;
      }
    }
    return -1;
  }

  private static byte[] encodeNodes(List<AdbControlPlaneNodeRecord> nodes)
      throws SQLException {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bytes);
      out.writeInt(NODES_MAGIC);
      out.writeInt(VERSION);
      out.writeInt(nodes.size());
      for (AdbControlPlaneNodeRecord node : nodes) {
        writeString(out, node.getNodeId());
        writeString(out, node.getRole().name());
        writeString(out, node.getHost());
        out.writeInt(node.getPort());
        writeString(out, node.getStatus().name());
        out.writeLong(node.getLastHeartbeatMillis());
        out.writeLong(node.getCommitIndex());
        out.writeLong(node.getAppliedIndex());
        writeString(out, node.getFailureDomain());
      }
      return bytes.toByteArray();
    } catch (IOException e) {
      throw new SQLException("Failed to encode control-plane nodes", e);
    }
  }

  private static List<AdbControlPlaneNodeRecord> decodeNodes(byte[] value)
      throws SQLException {
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(value));
      requireHeader(in, NODES_MAGIC, "nodes");
      int size = in.readInt();
      if (size < 0) {
        throw new SQLException("Invalid control-plane node count: " + size);
      }
      List<AdbControlPlaneNodeRecord> nodes = new ArrayList<>(size);
      for (int i = 0; i < size; i++) {
        nodes.add(new AdbControlPlaneNodeRecord(readString(in),
            AdbDeploymentNodeRole.valueOf(readString(in)), readString(in),
            in.readInt(), AdbControlPlaneNodeStatus.valueOf(readString(in)),
            in.readLong(), in.readLong(), in.readLong(), readString(in)));
      }
      return nodes;
    } catch (IOException | RuntimeException e) {
      throw new SQLException("Failed to decode control-plane nodes", e);
    }
  }

  private static byte[] encodeRegions(List<RegionMetadata> regions)
      throws SQLException {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bytes);
      out.writeInt(REGIONS_MAGIC);
      out.writeInt(VERSION);
      out.writeInt(regions.size());
      for (RegionMetadata region : regions) {
        writeString(out, region.getRegionId());
        writeBytes(out, region.getRange().getStartKey());
        writeBytes(out, region.getRange().getEndKey());
        out.writeLong(region.getEpoch());
        VirtualNodeMetadata vnode = region.getReplicaMetadata();
        writeString(out, vnode.getVirtualNodeId());
        out.writeLong(vnode.getEpoch());
        writeString(out, vnode.getLeaderId());
        out.writeLong(vnode.getCommitIndex());
        out.writeLong(vnode.getTerm());
        out.writeLong(vnode.getLeaseUntilMillis());
        out.writeInt(vnode.getReplicas().size());
        for (VirtualNodeReplica replica : vnode.getReplicas()) {
          writeString(out, replica.getReplicaId());
          writeString(out, replica.getRole().name());
        }
      }
      return bytes.toByteArray();
    } catch (IOException e) {
      throw new SQLException("Failed to encode control-plane regions", e);
    }
  }

  private static List<RegionMetadata> decodeRegions(byte[] value)
      throws SQLException {
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(value));
      requireHeader(in, REGIONS_MAGIC, "regions");
      int size = in.readInt();
      if (size <= 0) {
        throw new SQLException("Invalid control-plane region count: " + size);
      }
      List<RegionMetadata> regions = new ArrayList<>(size);
      for (int i = 0; i < size; i++) {
        String regionId = readString(in);
        KeyRange range = new KeyRange(readBytes(in), readBytes(in));
        long regionEpoch = in.readLong();
        String virtualNodeId = readString(in);
        long virtualNodeEpoch = in.readLong();
        String leaderId = readString(in);
        long commitIndex = in.readLong();
        long term = in.readLong();
        long leaseUntilMillis = in.readLong();
        int replicaCount = in.readInt();
        if (replicaCount <= 0) {
          throw new SQLException("Invalid replica count: " + replicaCount);
        }
        List<VirtualNodeReplica> replicas = new ArrayList<>(replicaCount);
        for (int j = 0; j < replicaCount; j++) {
          replicas.add(new VirtualNodeReplica(readString(in),
              ReplicaRole.valueOf(readString(in))));
        }
        regions.add(new RegionMetadata(regionId, range, regionEpoch,
            new VirtualNodeMetadata(virtualNodeId, virtualNodeEpoch,
                leaderId, replicas, commitIndex, term, leaseUntilMillis)));
      }
      return regions;
    } catch (IOException | RuntimeException e) {
      throw new SQLException("Failed to decode control-plane regions", e);
    }
  }

  private static void requireHeader(DataInputStream in, int magic,
      String recordName) throws IOException, SQLException {
    int actualMagic = in.readInt();
    int actualVersion = in.readInt();
    if (actualMagic != magic || actualVersion != VERSION) {
      throw new SQLException("Invalid control-plane " + recordName
          + " header");
    }
  }

  private static List<RegionMetadata> copyRegions(
      Collection<RegionMetadata> regions) {
    Objects.requireNonNull(regions, "regions == null");
    if (regions.isEmpty()) {
      throw new IllegalArgumentException("regions is empty");
    }
    return new ArrayList<>(regions);
  }

  private static void writeString(DataOutputStream out, String value)
      throws IOException {
    writeBytes(out, value.getBytes(StandardCharsets.UTF_8));
  }

  private static String readString(DataInputStream in) throws IOException {
    return new String(readBytes(in), StandardCharsets.UTF_8);
  }

  private static void writeBytes(DataOutputStream out, byte[] value)
      throws IOException {
    out.writeInt(value.length);
    out.write(value);
  }

  private static byte[] readBytes(DataInputStream in) throws IOException {
    int size = in.readInt();
    if (size < 0) {
      throw new IOException("negative bytes length: " + size);
    }
    byte[] value = new byte[size];
    in.readFully(value);
    return value;
  }

  private static byte[] key(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }
}
