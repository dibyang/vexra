package net.xdob.vexra.adb.db;


import org.adb.engine.SessionLocal;
import org.adb.index.Index;
import org.adb.index.IndexType;
import org.adb.result.Row;
import org.adb.table.IndexColumn;
import org.adb.table.Table;

import java.util.List;

/**
 * An index that stores the data in an MVStore.
 */
public abstract class AdbIndex<K,V> extends Index {

  protected AdbIndex(Table newTable, int id, String name, IndexColumn[] newIndexColumns, int uniqueColumnCount,
                     IndexType newIndexType) {
    super(newTable, id, name, newIndexColumns, uniqueColumnCount, newIndexType);
  }

  /**
   * Add the rows to a temporary storage (not to the index yet). The rows are
   * sorted by the index columns. This is to more quickly build the index.
   *
   * @param rows the rows
   */
  public abstract void addRowsToBuffer(SessionLocal session, List<Row> rows);

  /**
   * Add all the index data from the buffers to the index. The index will
   * typically use merge sort to add the data more quickly in sorted order.
   *
   */
  public abstract void addBufferedRows(SessionLocal session);


  public abstract TxnMap2 getTxnMap(SessionLocal session);

}