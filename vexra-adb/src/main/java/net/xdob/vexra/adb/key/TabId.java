package net.xdob.vexra.adb.key;

import java.util.Objects;

public class TabId {
  public final int id;
  public final long epoch;

  public TabId(int id, long epoch) {
    this.id = id;
    this.epoch = epoch;
  }

  public static TabId of(int tableId, long epoch){
    return new TabId(tableId, epoch);
  }

  @Override
  public String toString() {
    return "(" + id + "," + epoch + ')';
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    TabId tId = (TabId) o;
    return id == tId.id && epoch == tId.epoch;
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, epoch);
  }
}
