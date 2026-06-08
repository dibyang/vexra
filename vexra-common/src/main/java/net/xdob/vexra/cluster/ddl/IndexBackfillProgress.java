package net.xdob.vexra.cluster.ddl;

import java.util.Arrays;

/**
 * 索引回填进度。
 *
 * <p>进度使用最后完成的 key 和完成行数描述，便于任务失败后从断点继续。</p>
 */
public final class IndexBackfillProgress {
  private static final byte[] EMPTY = new byte[0];

  private final byte[] lastCompletedKey;
  private final long completedRows;

  /**
   * 创建索引回填进度。
   *
   * @param lastCompletedKey 最后完成的 key，空数组表示尚未开始
   * @param completedRows 已完成行数
   */
  public IndexBackfillProgress(byte[] lastCompletedKey, long completedRows) {
    if (completedRows < 0) {
      throw new IllegalArgumentException("completedRows is negative");
    }
    this.lastCompletedKey = lastCompletedKey == null || lastCompletedKey.length == 0
        ? EMPTY : Arrays.copyOf(lastCompletedKey, lastCompletedKey.length);
    this.completedRows = completedRows;
  }

  public byte[] getLastCompletedKey() {
    return lastCompletedKey.length == 0 ? EMPTY
        : Arrays.copyOf(lastCompletedKey, lastCompletedKey.length);
  }

  public long getCompletedRows() {
    return completedRows;
  }
}
