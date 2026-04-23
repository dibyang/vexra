package net.xdob.vexra.adb.ldb;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.db.DbStoreEngine;
import net.xdob.vexra.adb.db.DbStoreType;

import java.io.IOException;

public class LdbStoreTest {
  public static void main(String[] args) throws IOException {
    DbStore store = DbStoreEngine.getOrCreate(DbStoreType.LDB, "/test/db/b_db2", null);
    store.checkpoint("/test/db/b_db3");
    store.close();
  }
}
