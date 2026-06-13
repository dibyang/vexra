package net.xdob.vexra.adb.db;

import org.h2.command.query.AllColumnsForPlan;
import org.h2.engine.SessionLocal;
import org.h2.index.Cursor;
import org.h2.index.IndexType;
import org.h2.message.DbException;
import org.h2.result.Row;
import org.h2.result.RowFactory;
import org.h2.result.SearchRow;
import org.h2.result.SortOrder;
import org.h2.table.Column;
import org.h2.table.IndexColumn;
import org.h2.table.TableFilter;

import java.util.List;

/**
 * An index that delegates indexing to another index.
 */
public class AdbDelegateIndex extends AdbIndex<Long, SearchRow> {

  private final AdbPrimaryIndex mainIndex;

  public AdbDelegateIndex(AdbTable table, int id, String name, AdbPrimaryIndex mainIndex, IndexType indexType) {
    super(table, id, name, IndexColumn.wrap(new Column[]{table.getColumn(mainIndex.getMainIndexColumn())}),
        1, indexType);
    this.mainIndex = mainIndex;
    if (id < 0) {
      throw DbException.getInternalError(name);
    }
  }

  @Override
  public RowFactory getRowFactory() {
    return mainIndex.getRowFactory();
  }

  @Override
  public String getPlanSQL() {
    return super.getPlanSQL() + " " + mainIndex.getDistributedPlanMarker();
  }

  @Override
  public void addRowsToBuffer(SessionLocal session, List<Row> rows) {
    throw DbException.getInternalError();
  }

  @Override
  public void addBufferedRows(SessionLocal session) {
    throw DbException.getInternalError();
  }

  @Override
  public TxnMap2 getTxnMap(SessionLocal session) {
    return mainIndex.getTxnMap(session);
  }


  @Override
  public void add(SessionLocal session, Row row) {
    // nothing to do
  }

  @Override
  public Row getRow(SessionLocal session, long key) {
    return mainIndex.getRow(session, key);
  }

  @Override
  public boolean isRowIdIndex() {
    return true;
  }

  @Override
  public boolean canGetFirstOrLast() {
    return true;
  }

  @Override
  public void close(SessionLocal session) {
    // nothing to do
  }

  @Override
  public Cursor find(SessionLocal session, SearchRow first, SearchRow last) {
    return mainIndex.find(session, first, last);
  }

  @Override
  public Cursor findFirstOrLast(SessionLocal session, boolean first) {
    return mainIndex.findFirstOrLast(session, first);
  }

  @Override
  public int getColumnIndex(Column col) {
    if (col.getColumnId() == mainIndex.getMainIndexColumn()) {
      return 0;
    }
    return -1;
  }

  @Override
  public boolean isFirstColumn(Column column) {
    return getColumnIndex(column) == 0;
  }

  @Override
  public double getCost(SessionLocal session, int[] masks,
                        TableFilter[] filters, int filter, SortOrder sortOrder,
                        AllColumnsForPlan allColumnsSet) {
    return 10 * getCostRangeIndex(masks, mainIndex.getRowCountApproximation(session),
        filters, filter, sortOrder, true, allColumnsSet);
  }

  @Override
  public boolean needRebuild() {
    return false;
  }

  @Override
  public void remove(SessionLocal session, Row row) {
    // nothing to do
  }

  @Override
  public void update(SessionLocal session, Row oldRow, Row newRow) {
    // nothing to do
  }

  @Override
  public void remove(SessionLocal session) {
    mainIndex.setMainIndexColumn(SearchRow.ROWID_INDEX);
  }

  @Override
  public void truncate(SessionLocal session) {
    // nothing to do
  }

  @Override
  public long getRowCount(SessionLocal session) {
    return mainIndex.getRowCount(session);
  }

  @Override
  public long getRowCountApproximation(SessionLocal session) {
    return mainIndex.getRowCountApproximation(session);
  }

}
