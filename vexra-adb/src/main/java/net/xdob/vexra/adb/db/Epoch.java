package net.xdob.vexra.adb.db;

public class Epoch {
  long epoch;
  boolean intent;

  public static Epoch of(long epoch) {
    Epoch e = new Epoch();
    e.epoch = epoch;
    return e;
  }
}
