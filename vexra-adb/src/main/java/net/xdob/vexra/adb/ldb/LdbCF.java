package net.xdob.vexra.adb.ldb;

import net.xdob.vexra.adb.db.CF;
import net.xdob.vexra.ldb.LdbColumnFamily;

public class LdbCF {
  public final LdbColumnFamily defCf;
  public final LdbColumnFamily txnCf;
  public final LdbColumnFamily metaCf;

  LdbCF(LdbColumnFamily defCf, LdbColumnFamily txnCf, LdbColumnFamily metaCf) {
    this.defCf = defCf;
    this.txnCf = txnCf;
    this.metaCf = metaCf;
  }

  public static LdbCF of(LdbColumnFamily defCf, LdbColumnFamily txnCf, LdbColumnFamily metaCf) {
    return new LdbCF(defCf, txnCf, metaCf);
  }

  public LdbColumnFamily getCFHandle(CF cf) {
    switch (cf){
      case TXN:
        return txnCf;
      case META:
        return metaCf;
      default:
        return defCf;
    }
  }
}
