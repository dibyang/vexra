package net.xdob.vexra.adb.db;


import org.h2.engine.SessionLocal;
import org.h2.index.Index;
import org.h2.index.IndexType;
import org.h2.result.Row;
import org.h2.table.IndexColumn;
import org.h2.table.Table;

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