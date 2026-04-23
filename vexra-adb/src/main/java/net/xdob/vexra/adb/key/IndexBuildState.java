package net.xdob.vexra.adb.key;

public enum IndexBuildState {
  BUILDING(0),
  READY(1);

  private final byte code;

  IndexBuildState(int code) {
    this.code = (byte) code;
  }

  public byte getCode() {
    return code;
  }

  public static IndexBuildState getByCode(byte code) {
    for (IndexBuildState s : values()) {
      if (s.code == code) {
        return s;
      }
    }
    throw new IllegalArgumentException("Unknown IndexBuildState code: " + code);
  }
}
