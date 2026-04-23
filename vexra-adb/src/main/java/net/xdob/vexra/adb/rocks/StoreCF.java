package net.xdob.vexra.adb.rocks;

import net.xdob.vexra.adb.db.CF;
import org.rocksdb.ColumnFamilyHandle;

public class StoreCF {
  public final ColumnFamilyHandle defCf;
  public final ColumnFamilyHandle txnCf;
  public final ColumnFamilyHandle metaCf;

  StoreCF(ColumnFamilyHandle defCf, ColumnFamilyHandle txnCf, ColumnFamilyHandle metaCf) {
    this.defCf = defCf;
    this.txnCf = txnCf;
    this.metaCf = metaCf;
  }

  public static StoreCF of(ColumnFamilyHandle defCf, ColumnFamilyHandle txnCf, ColumnFamilyHandle metaCf) {
    return new StoreCF(defCf, txnCf, metaCf);
  }

  public ColumnFamilyHandle getCFHandle(CF cf) {
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
