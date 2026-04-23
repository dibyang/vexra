package net.xdob.vexra.adb.rocks;

import net.xdob.vexra.adb.*;
import net.xdob.vexra.adb.db.*;
import net.xdob.vexra.adb.db.DelegateWriteBatch;
import net.xdob.vexra.adb.key.TxnKeyType;
import net.xdob.vexra.adb.key.TxnRefKey;
import net.xdob.vexra.adb.key.TxnRefPrefix;
import net.xdob.vexra.adb.key.VersionKey;
import org.rocksdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * RocksStore - 每个 database 一个实例
 * - table prefix
 * - write batch 事务
 * - snapshot / checkpoint
 */
public class RocksStore implements DbStore {
  static final Logger LOG = LoggerFactory.getLogger(RocksStore.class.getName());
  protected final RocksDB db;
  protected final DBOptions options;
  protected final String path;
  protected final BlockBasedTableConfig tableConfig;
  protected final ColumnFamilyOptions cfOptions;
  protected final BloomFilter bloomFilter;
  protected List<ColumnFamilyHandle> cfHandles = new ArrayList<>();
  protected final ColumnFamilyHandle defaultCFHandle;
  protected final ColumnFamilyHandle txnCFHandle;
  protected final ColumnFamilyHandle metaCFHandle;
  static {
    RocksDB.loadLibrary();
  }




  public RocksStore(String path) {
    this.path = path;

    this.options = new DBOptions()
        .setCreateIfMissing(true)
        .setCreateMissingColumnFamilies(true)
        .setDbWriteBufferSize(64 * 1024 * 1024);

    // 启用布隆过滤器
    bloomFilter = new BloomFilter(10, false);
    tableConfig = new BlockBasedTableConfig()
        .setFilterPolicy(bloomFilter)
        .setBlockSize(4 * 1024);

    //tableConfig.setBlockCache(new LRUCache(512 * 1024 * 1024)); // 512MB
    //options.set.setTableFormatConfig(tableConfig);

    cfOptions = new ColumnFamilyOptions()
        .setWriteBufferSize(128 * 1024 * 1024)     // 128MB 内存表
        .setCompressionType(CompressionType.LZ4_COMPRESSION)
        .setTableFormatConfig(tableConfig);
    cfOptions.setMergeOperatorName("uint64add");

    // 1. 定义列族描述和句柄
    List<ColumnFamilyDescriptor> cfDescriptors = new ArrayList<>();


    // 默认列族（必须包含）
    cfDescriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOptions));

    // 自定义列族
    cfDescriptors.add(new ColumnFamilyDescriptor("TxnCF".getBytes(), cfOptions));
    cfDescriptors.add(new ColumnFamilyDescriptor("MetaCF".getBytes(), cfOptions));

    File dir = new File(path);
    if (!dir.exists()) dir.mkdirs();

    try {
      this.db = RocksDB.open(options, path, cfDescriptors, cfHandles);
      this.defaultCFHandle = cfHandles.get(0);
      this.txnCFHandle = cfHandles.get(1);
      this.metaCFHandle = cfHandles.get(2);
    } catch (RocksDBException e) {
      throw new RuntimeException(e);
    }

  }

  @Override
  public byte[] get(byte[] key) throws SQLException {
    try {
      return db.get(key);
    } catch (RocksDBException e) {
      throw RocksDBUtil.convert(e);
    }
  }


  @Override
  public void put(byte[] key, byte[] value) throws SQLException {
    try {
      db.put(key, value);
    } catch (RocksDBException e) {
      throw RocksDBUtil.convert(e);
    }
  }

  void merge(byte[] key, byte[] operand) throws SQLException {
    try {
      db.merge(key, operand);
    } catch (RocksDBException e) {
      throw RocksDBUtil.convert(e);
    }
  }

  @Override
  public long addLong(byte[] key, long operand) throws SQLException {
    merge(key, encodeLong(operand));
    return getLong( key).orElseThrow(() -> new SQLException("getLong error"));
  }

  @Override
  public Optional<Long> getLong(byte[] key) throws SQLException {
    return decodeLong(get(key));
  }

  @Override
  public void putLong(byte[] key, long value) throws SQLException {
    put(key, encodeLong(value));
  }

  ColumnFamilyHandle getCF(byte refId) {
    CF cf = CF.of(refId);
    switch (cf) {
      case TXN:
        return txnCFHandle;
      case META:
        return metaCFHandle;
      default:
        return defaultCFHandle;
    }
  }

  @Override
  public byte[] get(byte cfId, byte[] key) throws SQLException {
    try {
      ColumnFamilyHandle cf = getCF(cfId);
      return db.get(cf, key);
    } catch (RocksDBException e) {
      throw RocksDBUtil.convert(e);
    }
  }

  @Override
  public void put(byte cfId, byte[] key, byte[] value) throws SQLException {
    try {
      ColumnFamilyHandle cf = getCF(cfId);
      db.put(cf, key, value);
    } catch (RocksDBException e) {
      throw RocksDBUtil.convert(e);
    }
  }

  @Override
  public long addLong(byte cfId, byte[] key, long delta) throws SQLException {
    try {
      ColumnFamilyHandle cf = getCF(cfId);
      db.merge(cf, key, encodeLong(delta));
      return getLong(cfId, key).orElseThrow(() -> new SQLException("getLong error"));
    } catch (RocksDBException e) {
      throw RocksDBUtil.convert(e);
    }
  }

  @Override
  public Optional<Long> getLong(byte cfId, byte[] key) throws SQLException {
    byte[] bytes = get(cfId, key);
    if(bytes!=null){
      return decodeLong(bytes);
    }
    return Optional.empty();
  }

  @Override
  public void putLong(byte cfId, byte[] key, long value) throws SQLException {
    try {
      ColumnFamilyHandle cf = getCF(cfId);
      db.put(cf, key, encodeLong( value));
    } catch (RocksDBException e) {
      throw RocksDBUtil.convert(e);
    }
  }

  @Override
  public void delete(byte[] key) throws SQLException {
    try {
      db.delete(key);
    } catch (RocksDBException e) {
      throw RocksDBUtil.convert(e);
    }
  }

  @Override
  public void deleteRange(byte[] startKey, byte[] endKey) throws SQLException {
    try {
      db.deleteRange(startKey, endKey);
    }catch (RocksDBException e){
      throw RocksDBUtil.convert(e);
    }
  }

  @Override
  public void delete(byte cfId, byte[] key) throws SQLException {
    try {
      ColumnFamilyHandle cf = getCF(cfId);
      db.delete(cf, key);
    } catch (RocksDBException e) {
      throw RocksDBUtil.convert(e);
    }
  }

  @Override
  public void deleteRange(byte cfId, byte[] startKey, byte[] endKey) throws SQLException {
    try {
      ColumnFamilyHandle cf = getCF(cfId);
      db.deleteRange(cf, startKey, endKey);
    } catch (RocksDBException e) {
      throw RocksDBUtil.convert(e);
    }
  }

  // =========================
  // Write batch / transaction
  // =========================


  public void writeBatch(WriteBatchConsumer consumer) throws SQLException {
    StoreCF storeCF = StoreCF.of(defaultCFHandle, txnCFHandle, metaCFHandle);
    try (WriteBatch batch = new WriteBatch();
         DelegateWriteBatch delegate = new DelegateRocksWriteBatch(batch,this, storeCF);
         WriteOptions options = new WriteOptions()) {
      AdbWriteBatch adbWriteBatch = new AdbWriteBatch(this);
      consumer.accept(adbWriteBatch);
      adbWriteBatch.writeTo(delegate);
      db.write(options, batch);
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
    }
  }

  @Override
  public void rollback(long txnId) throws SQLException {
    StoreCF storeCF = StoreCF.of(defaultCFHandle, txnCFHandle, metaCFHandle);
    ColumnFamilyHandle txnCF = storeCF.getCFHandle(CF.TXN);
    ColumnFamilyHandle metaCF = storeCF.getCFHandle(CF.META);
    try (WriteBatch batch = new WriteBatch();
         WriteOptions options = new WriteOptions()) {
      List<TxnRefKey> keys = getTxnIndexList(txnId);
      for (TxnRefKey key : keys) {
        //删除引用
        batch.delete(txnCF, key.toBytes());
        // 删除临时版本
        batch.delete(key.getKey().toBytes());
      }
      db.write(options, batch);
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
    }
  }

  private void commit(long txnId, long commitTs, List<Meta> metas){
    StoreCF storeCF = StoreCF.of(defaultCFHandle, txnCFHandle, metaCFHandle);
    ColumnFamilyHandle txnCF = storeCF.getCFHandle(CF.TXN);
    ColumnFamilyHandle metaCF = storeCF.getCFHandle(CF.META);
    try (WriteBatch batch = new WriteBatch();
         WriteOptions options = new WriteOptions()) {
      List<TxnRefKey> keys = getTxnIndexList(txnId);
      for (TxnRefKey key : keys) {
        byte[] value = this.get(key.getKey().toBytes());
        if (value == null) {
          continue; // 或抛异常，看你策略
        }
        RowValue rowValue = RowValue.decodeValue(value);
        //删除引用
        batch.delete(txnCF, key.toBytes());
        // 删除临时版本
        batch.delete(key.getKey().toBytes());
        //保存正式版本
        VersionKey versionKey = VersionKey.of(key.getKey(), true, commitTs);
        rowValue.commitTs = commitTs;
        batch.put(versionKey.toBytes(), RowValue.encodeValue(rowValue));
      }
      //保存元数据
      for (Meta entry : metas) {
        batch.put(metaCF, entry.getKey(), entry.getValue());
      }
      db.write(options, batch);
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
    }
  }

  @Override
  public CompletableFuture<Void> commitAsync(long txnId, long commitTs, List<Meta> metas) throws SQLException {
    return CompletableFuture.runAsync(() -> commit(txnId, commitTs, metas));
  }

  public List<TxnRefKey> getTxnIndexList(long txnId) throws SQLException {
    byte[] prefix = TxnRefPrefix.of(txnId, TxnKeyType.WRITE_REF).toBytes();
    byte[] end = KeyCodec.prefixEnd(prefix);

    List<TxnRefKey> keys = new ArrayList<>();

    try (VersionScanSource scan = openVersionScanSource(CF.TXN.getCfId(), ScanDirection.FORWARD)) {
      scan.seekToRangeStart(prefix, end);

      while (scan.isValid() && KeyCodec.startsWith(scan.key(), prefix)) {
        keys.add(TxnRefKey.fromBytes(scan.key()));
        scan.advance();
      }
      return keys;
    } catch (Exception e) {
      throw new SQLException("Failed to get txn index list, txnId=" + txnId, e);
    }
  }

  @Override
  public VersionScanSource openVersionScanSource(ScanDirection direction) {
    return new RocksVersionEntryCursor(db.newIterator(), direction);
  }

  @Override
  public VersionScanSource openVersionScanSource(byte cfId, ScanDirection direction) {
    ColumnFamilyHandle cf = getCF(cfId);
    return new RocksVersionEntryCursor(db.newIterator(cf), direction);
  }

  // =========================
  // Snapshot / backup
  // =========================

  @Override
  public void checkpoint(String targetDir) throws IOException {
    try (Checkpoint cp = Checkpoint.create(db)) {
      cp.createCheckpoint(targetDir);
    }catch (RocksDBException e){
      throw new RuntimeException(e);
    }
  }

  @Override
  public void restore(String sourceDir) throws IOException  {
    // 简单恢复：先关闭 db 再替换目录
    close();
    File target = new File(path);
    File source = new File(sourceDir);
    if (target.exists()) {
      deleteDir(target);
    }
    source.renameTo(target);
  }

  private void deleteDir(File dir) {
    if (dir.isDirectory()) {
      for (File f : dir.listFiles()) {
        deleteDir(f);
      }
    }
    dir.delete();
  }

  // =========================
  // Close
  // =========================

  @Override
  public void close() {

    for (ColumnFamilyHandle cfHandle : cfHandles) {
      cfHandle.close();
    }
    db.close();
    cfOptions.close();
    options.close();
    Close2.close(bloomFilter);
  }

}
