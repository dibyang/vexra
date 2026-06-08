package net.xdob.vexra.cluster.ops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 滚动升级计划。
 *
 * <p>计划按节点列表顺序升级，每次返回下一个待升级节点。该对象不可变，
 * 标记节点完成会返回新计划。</p>
 */
public final class RollingUpgradePlan {
  private final String targetVersion;
  private final List<String> nodeIds;
  private final List<String> upgradedNodeIds;

  /**
   * 创建滚动升级计划。
   *
   * @param targetVersion 目标版本
   * @param nodeIds 节点顺序
   * @param upgradedNodeIds 已升级节点
   */
  public RollingUpgradePlan(String targetVersion, List<String> nodeIds,
      List<String> upgradedNodeIds) {
    this.targetVersion = normalize(targetVersion, "targetVersion");
    this.nodeIds = immutableIds(nodeIds, "nodeIds");
    this.upgradedNodeIds = upgradedNodeIds == null
        ? Collections.emptyList() : immutableIds(upgradedNodeIds,
        "upgradedNodeIds");
  }

  public String getTargetVersion() {
    return targetVersion;
  }

  public List<String> getNodeIds() {
    return nodeIds;
  }

  public List<String> getUpgradedNodeIds() {
    return upgradedNodeIds;
  }

  /**
   * 返回下一个待升级节点。
   *
   * @return 全部完成时返回空字符串
   */
  public String nextNode() {
    for (String nodeId : nodeIds) {
      if (!upgradedNodeIds.contains(nodeId)) {
        return nodeId;
      }
    }
    return "";
  }

  /**
   * 标记节点已升级。
   *
   * @param nodeId 已升级节点
   * @return 新滚动升级计划
   */
  public RollingUpgradePlan markUpgraded(String nodeId) {
    String normalized = normalize(nodeId, "nodeId");
    if (!nodeIds.contains(normalized)) {
      throw new IllegalArgumentException("unknown nodeId: " + normalized);
    }
    List<String> upgraded = new ArrayList<>(upgradedNodeIds);
    if (!upgraded.contains(normalized)) {
      upgraded.add(normalized);
    }
    return new RollingUpgradePlan(targetVersion, nodeIds, upgraded);
  }

  /**
   * 判断升级是否完成。
   *
   * @return 所有节点均已升级时返回 true
   */
  public boolean isComplete() {
    return upgradedNodeIds.containsAll(nodeIds);
  }

  private static List<String> immutableIds(List<String> values,
      String fieldName) {
    Objects.requireNonNull(values, fieldName + " == null");
    if (values.isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    List<String> copy = new ArrayList<>();
    for (String value : values) {
      copy.add(normalize(value, fieldName));
    }
    return Collections.unmodifiableList(copy);
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }
}
