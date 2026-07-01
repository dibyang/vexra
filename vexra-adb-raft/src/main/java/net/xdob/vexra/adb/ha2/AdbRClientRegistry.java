package net.xdob.vexra.adb.ha2;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ADB Raft client 注册表。
 *
 * <p>该对象保存 replica/leader id 到 {@link RClient} 的映射。它只负责查找，
 * 不拥有 client 生命周期；调用方仍然负责关闭真实的 Raft client。这样 resolver、
 * 控制面和部署层可以独立演进。</p>
 */
public final class AdbRClientRegistry {
  private final Map<String, RClient> clients = new LinkedHashMap<>();

  /**
   * 注册或替换指定 replica 的 client。
   *
   * @param replicaId 副本或 leader 标识
   * @param client ADB Raft client
   */
  public synchronized void register(String replicaId, RClient client) {
    clients.put(normalize(replicaId, "replicaId"),
        Objects.requireNonNull(client, "client == null"));
  }

  /**
   * 移除指定 replica 的 client。
   *
   * @param replicaId 副本或 leader 标识
   */
  public synchronized void unregister(String replicaId) {
    clients.remove(normalize(replicaId, "replicaId"));
  }

  /**
   * 查找指定 replica 的 client。
   *
   * @param replicaId 副本或 leader 标识
   * @return 找到时返回 client，否则返回空
   */
  public synchronized Optional<RClient> get(String replicaId) {
    return Optional.ofNullable(clients.get(normalize(replicaId,
        "replicaId")));
  }

  /**
   * 返回当前注册表快照，便于测试和诊断。
   *
   * @return replicaId 到 client 的不可变快照
   */
  public synchronized Map<String, RClient> snapshot() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(clients));
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }
}
