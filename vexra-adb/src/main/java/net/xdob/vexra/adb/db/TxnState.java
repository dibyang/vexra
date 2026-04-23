package net.xdob.vexra.adb.db;

/**
 * TxnState
 *
 * @author yangzj
 * @version 1.0
 */
public enum TxnState {
  PENDING,
  COMMITTING,
  COMMITTED,
  ABORTED;
  TxnState() {
  }

  public byte getCode() {
    return (byte) ordinal();
  }

  public static TxnState fromCode(byte code) {
    return values()[code];
  }
}
