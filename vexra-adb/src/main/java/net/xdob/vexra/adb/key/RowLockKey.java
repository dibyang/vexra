package net.xdob.vexra.adb.key;

import java.util.Objects;

public final class RowLockKey {
  private final TabId tId;
  private final long rowKey;

  public RowLockKey(TabId tId, long rowKey) {
    this.tId = tId;
    this.rowKey = rowKey;
  }


  public TabId getTabId() {
    return tId;
  }

  public long getRowKey() { return rowKey; }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    RowLockKey that = (RowLockKey) o;
    return rowKey == that.rowKey && Objects.equals(tId, that.tId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(tId, rowKey);
  }

  @Override
  public String toString() {
    return "RowLockKey{" +
        "tabKey=" + tId +
        ", rowKey=" + rowKey +
        '}';
  }
}
