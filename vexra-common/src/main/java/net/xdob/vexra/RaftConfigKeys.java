package net.xdob.vexra;

import net.xdob.vexra.conf.RaftProperties;
import net.xdob.vexra.datastream.SupportedDataStreamType;
import net.xdob.vexra.ha.HaConfig;
import net.xdob.vexra.ha.HaMode;
import net.xdob.vexra.ha.HaNodeRole;
import net.xdob.vexra.rpc.RpcType;
import net.xdob.vexra.rpc.SupportedRpcType;

import java.util.function.Consumer;

import static net.xdob.vexra.conf.ConfUtils.*;

/**
 * RaftConfig支持的工具类
 */
public interface RaftConfigKeys {
  String PREFIX = "raft";

  interface Rpc {
    String PREFIX = RaftConfigKeys.PREFIX + ".rpc";

    String TYPE_KEY = PREFIX + ".type";
    String TYPE_DEFAULT = SupportedRpcType.GRPC.name();

    static RpcType type(RaftProperties properties, Consumer<String> logger) {
      final String t = get(properties::get, TYPE_KEY, TYPE_DEFAULT, logger);
      return RpcType.valueOf(t);
    }

    static void setType(RaftProperties properties, RpcType type) {
      set(properties::set, TYPE_KEY, type.name());
    }
  }

  interface DataStream {
    String PREFIX = RaftConfigKeys.PREFIX + ".datastream";

    String TYPE_KEY = PREFIX + ".type";
    String TYPE_DEFAULT = SupportedDataStreamType.DISABLED.name();

    static SupportedDataStreamType type(RaftProperties properties, Consumer<String> logger) {
      final String t = get(properties::get, TYPE_KEY, TYPE_DEFAULT, logger);
      return SupportedDataStreamType.valueOfIgnoreCase(t);
    }

    static void setType(RaftProperties properties, SupportedDataStreamType type) {
      set(properties::set, TYPE_KEY, type.name());
    }

    String SKIP_SEND_FORWARD_KEY = PREFIX + ".skip.send-forward";
    boolean SKIP_SEND_FORWARD_DEFAULT = false;

    static boolean skipSendForward(RaftProperties properties, Consumer<String> logger) {
      return getBoolean(properties::getBoolean, SKIP_SEND_FORWARD_KEY, SKIP_SEND_FORWARD_DEFAULT, logger);
    }

    static void setSkipSendForward(RaftProperties properties, boolean skipSendForward) {
      setBoolean(properties::setBoolean, SKIP_SEND_FORWARD_KEY, skipSendForward);
    }
  }

  /**
   * HA 部署模式配置项。
   *
   * <p>该配置组只在配置层表达模式和安全约束，真正的 witness 投票、共享存储 fencing
   * 和多数派写入 gate 由后续阶段接入。</p>
   */
  interface Ha {
    String PREFIX = RaftConfigKeys.PREFIX + ".ha";

    String MODE_KEY = PREFIX + ".mode";
    String MODE_DEFAULT = HaMode.SINGLE.name();

    static HaMode mode(RaftProperties properties, Consumer<String> logger) {
      final String value = get(properties::get, MODE_KEY, MODE_DEFAULT, logger);
      return HaMode.valueOfIgnoreCase(value);
    }

    static void setMode(RaftProperties properties, HaMode mode) {
      set(properties::set, MODE_KEY, mode.name());
    }

    String NODE_ROLE_KEY = PREFIX + ".node.role";
    String NODE_ROLE_DEFAULT = HaNodeRole.DATA.name();

    static HaNodeRole nodeRole(RaftProperties properties, Consumer<String> logger) {
      final String value = get(properties::get, NODE_ROLE_KEY, NODE_ROLE_DEFAULT, logger);
      return HaNodeRole.valueOfIgnoreCase(value);
    }

    static void setNodeRole(RaftProperties properties, HaNodeRole nodeRole) {
      set(properties::set, NODE_ROLE_KEY, nodeRole.name());
    }

    String REPLICA_ID_KEY = PREFIX + ".replica.id";
    String REPLICA_ID_DEFAULT = "";

    static String replicaId(RaftProperties properties, Consumer<String> logger) {
      return get(properties::get, REPLICA_ID_KEY, REPLICA_ID_DEFAULT, logger);
    }

    static void setReplicaId(RaftProperties properties, String replicaId) {
      set(properties::set, REPLICA_ID_KEY, replicaId);
    }

    String WITNESS_ADDRESS_KEY = PREFIX + ".witness.address";
    String WITNESS_ADDRESS_DEFAULT = "";

    static String witnessAddress(RaftProperties properties, Consumer<String> logger) {
      return get(properties::get, WITNESS_ADDRESS_KEY, WITNESS_ADDRESS_DEFAULT, logger);
    }

    static void setWitnessAddress(RaftProperties properties, String witnessAddress) {
      set(properties::set, WITNESS_ADDRESS_KEY, witnessAddress);
    }

    String SHARED_STORAGE_ENABLED_KEY = PREFIX + ".shared-storage.enabled";
    boolean SHARED_STORAGE_ENABLED_DEFAULT = false;

    static boolean sharedStorageEnabled(RaftProperties properties, Consumer<String> logger) {
      return getBoolean(properties::getBoolean, SHARED_STORAGE_ENABLED_KEY,
          SHARED_STORAGE_ENABLED_DEFAULT, logger);
    }

    static void setSharedStorageEnabled(RaftProperties properties,
        boolean sharedStorageEnabled) {
      setBoolean(properties::setBoolean, SHARED_STORAGE_ENABLED_KEY,
          sharedStorageEnabled);
    }

    String QUORUM_WRITE_REQUIRED_KEY = PREFIX + ".quorum.write-required";
    boolean QUORUM_WRITE_REQUIRED_DEFAULT = false;

    static boolean quorumWriteRequired(RaftProperties properties,
        Consumer<String> logger) {
      return getBoolean(properties::getBoolean, QUORUM_WRITE_REQUIRED_KEY,
          QUORUM_WRITE_REQUIRED_DEFAULT, logger);
    }

    static void setQuorumWriteRequired(RaftProperties properties,
        boolean quorumWriteRequired) {
      setBoolean(properties::setBoolean, QUORUM_WRITE_REQUIRED_KEY,
          quorumWriteRequired);
    }

    /**
     * 读取当前 HA 配置快照。
     *
     * @param properties 配置属性
     * @param logger 默认值记录器
     * @return 不可变 HA 配置快照
     */
    static HaConfig config(RaftProperties properties, Consumer<String> logger) {
      return new HaConfig(mode(properties, logger), nodeRole(properties, logger),
          replicaId(properties, logger), witnessAddress(properties, logger),
          sharedStorageEnabled(properties, logger),
          quorumWriteRequired(properties, logger));
    }

    /**
     * 读取并校验当前 HA 配置。
     *
     * @param properties 配置属性
     * @param logger 默认值记录器
     * @return 校验通过的 HA 配置快照
     * @throws IllegalArgumentException 当配置组合不安全或不自洽时抛出
     */
    static HaConfig validate(RaftProperties properties, Consumer<String> logger) {
      final HaConfig config = config(properties, logger);
      config.validate();
      return config;
    }

    /**
     * 读取配置并校验集群拓扑是否允许自动写入接管。
     *
     * @param properties 配置属性
     * @param logger 默认值记录器
     * @param dataNodeCount 数据节点数量
     * @param witnessNodeCount witness 节点数量
     * @param automaticFailover 是否启用自动故障切换或自动写入接管
     * @return 校验通过的 HA 配置快照
     * @throws IllegalArgumentException 当拓扑可能产生 split-brain 时抛出
     */
    static HaConfig validateTopology(RaftProperties properties,
        Consumer<String> logger, int dataNodeCount, int witnessNodeCount,
        boolean automaticFailover) {
      final HaConfig config = config(properties, logger);
      config.validateTopology(dataNodeCount, witnessNodeCount, automaticFailover);
      return config;
    }
  }

  static void main(String[] args) {
    printAll(RaftConfigKeys.class);
  }
}
