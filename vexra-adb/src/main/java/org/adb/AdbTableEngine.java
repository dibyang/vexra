package org.adb;

import org.adb.api.TableEngine;
import org.adb.command.ddl.CreateTableData;
import org.adb.message.DbException;
import org.adb.table.Table;

/**
 * 旧 `org.adb` 表引擎兼容入口。
 *
 * <p>ADB 表引擎已经迁移到 h2db `TableEngineProvider` 插件入口。该类只保留编译兼容性，
 * 避免旧分叉路径继续创建基于 `org.adb.*` 类型体系的表对象。
 */
@Deprecated
public class AdbTableEngine implements TableEngine {

  /**
   * 拒绝通过旧 `org.adb` 表引擎创建 ADB 表。
   *
   * @param data 旧分叉建表数据
   * @return 不会返回
   */
  @Override
  public Table createTable(CreateTableData data) {
    throw DbException.getUnsupportedException(
        "ADB table engine has moved to h2db provider adb_table");
  }
}
