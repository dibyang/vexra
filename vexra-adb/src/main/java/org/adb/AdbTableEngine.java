package org.adb;


import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.db.AdbTable;
import org.adb.api.TableEngine;
import org.adb.command.ddl.CreateTableData;
import org.adb.engine.Database;
import org.adb.table.Table;


public class AdbTableEngine implements TableEngine {

  @Override
  public Table createTable(CreateTableData data) {
    Database db = data.session.getDatabase();
//    if (data.tableName.startsWith("SYS")
//        || data.tableName.startsWith("INFORMATION_SCHEMA")) {
//      return new MVTable(data, db.getStore()); // 或默认 MVTable
//    }
    DbStore dbStore = db.getDbStore();
    return new AdbTable(data, db.getStore(), dbStore);
  }

}

