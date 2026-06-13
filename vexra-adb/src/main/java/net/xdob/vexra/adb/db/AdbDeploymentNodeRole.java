package net.xdob.vexra.adb.db;

/**
 * ADB 部署节点角色。
 *
 * <p>角色只描述部署层职责，不替代 region 内的 replica role。生产部署可以用
 * DATA_NODE 承载业务数据副本，用 WITNESS_NODE 作为轻量投票节点。</p>
 */
public enum AdbDeploymentNodeRole {
  /** 保存业务数据并承载 region 副本的节点。 */
  DATA_NODE,

  /** 只参与仲裁或投票的轻量 witness 节点。 */
  WITNESS_NODE
}
