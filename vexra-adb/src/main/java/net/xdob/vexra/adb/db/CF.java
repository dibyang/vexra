package net.xdob.vexra.adb.db;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public enum CF {
  UNSPECIFIED((byte)0),
  DEFAULT((byte)1),
  META((byte)2),
  TXN((byte)3);
  private final byte cfId;
  CF(byte cfId) {
    this.cfId = cfId;
  }
  public byte getCfId() {
    return cfId;
  }

  public static List<CF> allCfs() {
    return Arrays.stream(values())
        .filter(cf -> cf != UNSPECIFIED)
        .collect(Collectors.toList());
  }

  public static CF of(byte cfId) {
    for (CF cf : values()) {
      if (cf.cfId == cfId) {
        return cf;
      }
    }
    return UNSPECIFIED;
  }
  public static CF of(int cfId) {
    return of((byte)cfId);
  }
}
