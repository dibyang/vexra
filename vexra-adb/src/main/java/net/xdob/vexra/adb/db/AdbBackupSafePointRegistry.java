package net.xdob.vexra.adb.db;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ADB 备份 safe point 注册表。
 *
 * <p>第一版实现为进程内注册表，用于把正在执行的备份快照时间戳提供给
 * {@link AdbGlobalSafePointAdvancer}。真实生产部署后续可以替换为控制面持久化
 * 版本，但推进器只依赖快照接口，调用边界保持稳定。</p>
 */
public final class AdbBackupSafePointRegistry {
  private final Map<String, AdbBackupSafePoint> records =
      new LinkedHashMap<>();

  /**
   * 注册或更新一个备份保护点。
   *
   * @param backupId 备份任务标识
   * @param safePoint 备份读取时间戳，GC safe point 不得达到或越过该值
   * @return 注册后的备份保护记录
   */
  public synchronized AdbBackupSafePoint register(String backupId,
      long safePoint) {
    AdbBackupSafePoint record = new AdbBackupSafePoint(backupId, safePoint);
    records.put(record.getBackupId(), record);
    return record;
  }

  /**
   * 释放备份保护点。
   *
   * @param backupId 备份任务标识
   * @return 被释放的记录；不存在时返回 null
   */
  public synchronized AdbBackupSafePoint release(String backupId) {
    if (backupId == null || backupId.trim().isEmpty()) {
      throw new IllegalArgumentException("backupId is empty");
    }
    return records.remove(backupId.trim());
  }

  /**
   * 返回当前所有备份保护记录快照。
   *
   * @return 不可变记录列表
   */
  public synchronized List<AdbBackupSafePoint> snapshot() {
    return Collections.unmodifiableList(new ArrayList<>(records.values()));
  }

  /**
   * 返回当前所有备份 safe point 数值快照。
   *
   * @return 不可变 safe point 集合
   */
  public synchronized Collection<Long> safePointSnapshot() {
    List<Long> safePoints = new ArrayList<>();
    for (AdbBackupSafePoint record : records.values()) {
      safePoints.add(record.getSafePoint());
    }
    return Collections.unmodifiableList(safePoints);
  }
}
