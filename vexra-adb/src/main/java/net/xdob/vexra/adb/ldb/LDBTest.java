package net.xdob.vexra.adb.ldb;

import net.xdob.vexra.adb.db.CF;
import net.xdob.vexra.adb.db.RowValue;
import net.xdob.vexra.ldb.*;
import net.xdob.vexra.ldb.impl.LDbImpl;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;

public class LDBTest {

  public static void main(String[] args)  {
    try {
      Options options = new Options()
          .createIfMissing(true)
          .errorIfExists(false)
          .verifyChecksums(true)
          .paranoidChecks(true)
          .cacheSize(0)
          .maxOpenFiles(1000)
          .blockSize(4096)
          .blockRestartInterval(16)
          .compressionType(CompressionType.LZ4);
      for (CF cf : CF.allCfs()) {
        if(cf.equals(CF.DEFAULT)){
          options.addColumnFamily(LdbColumnFamily.DEFAULT);
        }else{
          options.addColumnFamily(new LdbColumnFamily() {
            @Override
            public int getId() {
              return cf.getCfId();
            }

            @Override
            public String getName() {
              return cf.name();
            }
          });
        }
      }
      LDB ldb = new LDbImpl(options, Paths.get("e:/test/ldb").toFile());
      LdbColumnFamily cf = ldb.getColumnFamily(CF.TXN.getCfId());
//      for (int i = 0; i < 10; i++) {
//        VersionRowKey versionRowKey = VersionRowKey.of(TabId.of(1, 1L), i, false, 3);
//        //ldb.addLong("count".getBytes(), 100);
//
//        RowValue rowValue = new RowValue();
//        rowValue.payload = ("hello world_"+i).getBytes();
//        rowValue.txnId = 5;
//        rowValue.commitTs = 6;
//        rowValue.rowKey = 7 + i;
//        byte[] value = RowValue.encodeValue(rowValue);
//        System.out.println("Arrays.toString(old) = " + Arrays.toString(value));
//        ldb.put(cf, versionRowKey.toBytes(), value);
//        byte[] bytes = ldb.get(cf, versionRowKey.toBytes());
//        System.out.println("Arrays.toString(bytes) = " + Arrays.toString(bytes));
//        RowValue rowValue1 = RowValue.decodeValue(bytes);
//        System.out.println(new String(rowValue1.payload));
//      }

      SnapshotCursor iterator = ldb.newSnapshotCursor(cf);
      while (iterator.isValid()) {
        //VersionKey versionKey = VersionRowKey.fromBytes();
        System.out.println("key=" + Arrays.toString(iterator.key()));
        byte[] value = iterator.value();
        RowValue rowValue = RowValue.decodeValue(value);
        System.out.println("rowValue = " + new String(rowValue.payload));
        iterator.next();
      }
      ldb.close();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
