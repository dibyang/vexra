package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.ldb.LdbStore;
import net.xdob.vexra.adb.rocks.RocksStore;
import org.h2.api.ErrorCode;
import org.h2.message.DbException;
import org.h2.store.fs.FileUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;


public class DbStoreEngine {
  private static final Map<String, DbStore> stores = new HashMap<>();


  public static DbStore getOrCreate(DbStoreType type, String databaseName, Properties properties) {
    String dbPath = FileUtils.toRealPath(databaseName);
    if(DbStoreType.HA2 ==  type) {
      throw DbException.getUnsupportedException(
          "HA2 store requires the vexra-adb-raft module");
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
            new AdbStartupRecoveryService(store).recoverOnce();
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
