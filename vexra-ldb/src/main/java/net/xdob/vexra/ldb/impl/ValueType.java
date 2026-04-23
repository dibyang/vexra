package net.xdob.vexra.ldb.impl;

public enum ValueType {
  DELETION(0x00),
  VALUE(0x01),
  DELETE_RANGE(0x02),
  ADD_LONG(0x03),
  ;

  public static ValueType getValueTypeByPersistentId(int persistentId) {
    switch (persistentId) {
      case 0:
        return DELETION;
      case 1:
        return VALUE;
      case 2:
        return DELETE_RANGE;
      case 3:
          return ADD_LONG;
      default:
        throw new IllegalArgumentException("Unknown persistentId " + persistentId);
    }
  }

  private final int persistentId;

  ValueType(int persistentId) {
    this.persistentId = persistentId;
  }

  public int getPersistentId() {
    return persistentId;
  }
}
