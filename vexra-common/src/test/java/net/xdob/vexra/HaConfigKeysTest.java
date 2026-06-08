package net.xdob.vexra;

import net.xdob.vexra.conf.RaftProperties;
import net.xdob.vexra.ha.HaConfig;
import net.xdob.vexra.ha.HaMode;
import net.xdob.vexra.ha.HaNodeRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HA 配置模型和安全拓扑约束回归测试。
 *
 * <p>这些测试只覆盖配置层行为，不启动 Raft、网络或存储。目标是在后续接入 witness
 * 投票前，先确保纯两数据节点自动写入不会被配置层误放行。</p>
 */
class HaConfigKeysTest {
  /**
   * 验证 HA 配置默认保持单节点兼容模式，并且不会默认开启共享存储或多数派写入要求。
   */
  @Test
  void shouldUseSingleModeDefaults() {
    RaftProperties properties = new RaftProperties();

    HaConfig config = RaftConfigKeys.Ha.validate(properties, ignored -> { });

    assertEquals(HaMode.SINGLE, config.getMode());
    assertEquals(HaNodeRole.DATA, config.getNodeRole());
    assertFalse(config.isSharedStorageEnabled());
    assertFalse(config.isQuorumWriteRequired());
  }

  /**
   * 验证 witness 模式在配置完整且拓扑包含 witness 时可以通过校验。
   */
  @Test
  void shouldAcceptWitnessModeWithQuorumAndWitness() {
    RaftProperties properties = witnessDataNodeProperties();

    HaConfig config = RaftConfigKeys.Ha.validateTopology(
        properties, ignored -> { }, 2, 1, true);

    assertEquals(HaMode.WITNESS, config.getMode());
    assertEquals("node-a", config.getReplicaId());
    assertEquals("127.0.0.1:9876", config.getWitnessAddress());
  }

  /**
   * 验证 witness 模式必须显式要求多数派写入，避免误退化为单节点确认。
   */
  @Test
  void shouldRejectWitnessModeWithoutQuorumWrite() {
    RaftProperties properties = witnessDataNodeProperties();
    RaftConfigKeys.Ha.setQuorumWriteRequired(properties, false);

    assertThrows(IllegalArgumentException.class,
        () -> RaftConfigKeys.Ha.validate(properties, ignored -> { }));
  }

  /**
   * 验证纯两数据节点在自动故障切换场景下必须被拒绝，防止网络分区时 split-brain。
   */
  @Test
  void shouldRejectPureTwoDataNodeAutomaticWrites() {
    RaftProperties properties = new RaftProperties();

    assertThrows(IllegalArgumentException.class,
        () -> RaftConfigKeys.Ha.validateTopology(
            properties, ignored -> { }, 2, 0, true));
  }

  /**
   * 验证共享存储模式必须通过专用模式和显式开关同时启用。
   */
  @Test
  void shouldRequireExplicitSharedStorageMode() {
    RaftProperties properties = new RaftProperties();
    RaftConfigKeys.Ha.setSharedStorageEnabled(properties, true);

    assertThrows(IllegalArgumentException.class,
        () -> RaftConfigKeys.Ha.validate(properties, ignored -> { }));

    RaftConfigKeys.Ha.setMode(properties, HaMode.SHARED_STORAGE);
    assertDoesNotThrow(() -> RaftConfigKeys.Ha.validateTopology(
        properties, ignored -> { }, 2, 0, true));
  }

  /**
   * 验证 HA 模式和角色解析兼容大小写及连字符写法，便于配置文件人工维护。
   */
  @Test
  void shouldParseModeAndRoleIgnoringCaseAndHyphen() {
    RaftProperties properties = new RaftProperties();
    properties.set(RaftConfigKeys.Ha.MODE_KEY, "shared-storage");
    properties.set(RaftConfigKeys.Ha.NODE_ROLE_KEY, "witness");
    RaftConfigKeys.Ha.setSharedStorageEnabled(properties, true);

    HaConfig config = RaftConfigKeys.Ha.validate(properties, ignored -> { });

    assertEquals(HaMode.SHARED_STORAGE, config.getMode());
    assertEquals(HaNodeRole.WITNESS, config.getNodeRole());
  }

  private static RaftProperties witnessDataNodeProperties() {
    RaftProperties properties = new RaftProperties();
    RaftConfigKeys.Ha.setMode(properties, HaMode.WITNESS);
    RaftConfigKeys.Ha.setNodeRole(properties, HaNodeRole.DATA);
    RaftConfigKeys.Ha.setReplicaId(properties, "node-a");
    RaftConfigKeys.Ha.setWitnessAddress(properties, "127.0.0.1:9876");
    RaftConfigKeys.Ha.setQuorumWriteRequired(properties, true);
    return properties;
  }
}
