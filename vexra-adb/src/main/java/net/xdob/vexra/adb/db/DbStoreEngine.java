package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.ha2.RaftStore;
import net.xdob.vexra.adb.ldb.LdbStore;
import net.xdob.vexra.adb.rocks.RocksStore;
import org.adb.api.ErrorCode;
import org.adb.message.DbException;
import org.adb.store.fs.FileUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;


public class DbStoreEngine {
  private static final Map<String, DbStore> stores = new HashMap<>();


  public static DbStore getOrCreate(DbStoreType type, String databaseName, Properties properties) {
    String dbPath = FileUtils.toRealPath(databaseName);
    if(DbStoreType.HA2 ==  type) {
      return new RaftStore(dbPath, properties);
    }
    DbStore store = stores.get(dbPath);
    if(store==null){
      synchronized (stores){
        store = stores.get(dbPath);
        if(store==null){
          try {
            switch (type) {
              case ROCKSDB:
                store = new RocksStore(dbPath);
                //throw new UnsupportedOperationException("RocksDB is not supported yet.");
                break;
              case LDB:
                store = new LdbStore(dbPath);
                break;
              default:
                throw new IllegalArgumentException("Unsupported store type: " + type);
            }
            stores.put(dbPath, store);
          } catch (Exception e) {
            throw DbException.get(ErrorCode.GENERAL_ERROR_1, e);
          }
        }
      }
    }
    return store;
  }


  public static void close(String databaseName) {
    String dbPath = FileUtils.toRealPath(databaseName);
    DbStore store = stores.remove(dbPath);
    if(store!=null){
      try {
        store.close();
      } catch (Exception e) {
        throw DbException.get(ErrorCode.GENERAL_ERROR_1, e);
      }
    }
  }
}
