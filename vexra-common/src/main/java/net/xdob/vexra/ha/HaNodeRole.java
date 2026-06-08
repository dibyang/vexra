package net.xdob.vexra.ha;

import java.util.Locale;

/**
 * 当前节点在 HA 拓扑中的角色。
 *
 * <p>DATA 节点可以承载业务数据并在满足多数派时成为 leader；
 * WITNESS 节点只参与仲裁，不应承载 SQL、表数据、索引数据或扫描。</p>
 */
public enum HaNodeRole {
  /** 承载业务数据的节点。 */
  DATA,

  /** 只参与投票和仲裁的轻量节点。 */
  WITNESS;

  /**
   * 按不区分大小写的方式解析节点角色。
   *
   * @param value 配置字符串
   * @return 匹配的节点角色
   * @throws IllegalArgumentException 当配置为空或无法识别时抛出
   */
  public static HaNodeRole valueOfIgnoreCase(String value) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException("HA node role is empty");
    }
    return valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
  }
}
