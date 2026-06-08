package net.xdob.vexra.ha;

import java.util.Locale;

/**
 * 高可用部署模式。
 *
 * <p>该枚举只描述配置层允许的模式，不直接触发 Raft 选主或存储行为。
 * 后续 witness、共享存储和多数派写入 gate 都应以这里的模式作为显式开关，
 * 避免纯两数据节点被误配置成自动强一致写入。</p>
 */
public enum HaMode {
  /** 单节点或兼容部署模式。 */
  SINGLE,

  /** 两个数据节点加一个轻量 witness 的多数派部署模式。 */
  WITNESS,

  /** 依赖共享存储和外部 fencing 的兼容部署模式，必须显式开启。 */
  SHARED_STORAGE;

  /**
   * 按不区分大小写的方式解析部署模式。
   *
   * @param value 配置字符串
   * @return 匹配的部署模式
   * @throws IllegalArgumentException 当配置为空或无法识别时抛出
   */
  public static HaMode valueOfIgnoreCase(String value) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException("HA mode is empty");
    }
    return valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
  }
}
