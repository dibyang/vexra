package net.xdob.vexra.adb.ldb;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.db.*;
import net.xdob.vexra.adb.db.CF;
import net.xdob.vexra.adb.key.TxnKeyType;
import net.xdob.vexra.adb.key.TxnLockKey;
import net.xdob.vexra.adb.key.TxnRefKey;
import net.xdob.vexra.adb.key.TxnRefPrefix;
import net.xdob.vexra.adb.key.VersionKey;
import net.xdob.vexra.ldb.*;
import net.xdob.vexra.ldb.impl.BloomFilterPolicy;
import net.xdob.vexra.ldb.impl.LDbImpl;
import net.xdob.vexra.ldb.impl.LdbWriteBatchImpl;
import net.xdob.vexra.ldb.util.Slices;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * RocksStore 的委托封装类。
 *
 * 说明：
 * 1. 采用组合而不是继承，避免再次打开/管理底层 RocksDB。
 * 2. 所有实例方法都直接委托给 delegate。
 * 3. static 方法（如 encodeLong/decodeLong）不属于实例，不需要委托。
 */
public class LdbStore implements DbStore {

  public static final String ADB = "adb";
  private static final String ASYNC_WRITE_COMBINING_ENABLED_PROPERTY =
      "vexra.adb.ldb.asyncWriteCombining.enabled";
  private static final String ASYNC_WRITE_COMBINING_MAX_DELAY_NANOS_PROPERTY =
      "vexra.adb.ldb.asyncWriteCombining.maxDelayNanos";
  private final Options options;
  private final File dbRootDir;
  private final File slotADir;
  private final File slotBDir;
  private final File activeFile;
  private final AdbLdbPlugin adbLdbPlugin;

  private String activeSlot;   // "A" or "B"
  private String activeDbPath; // slotA 或 slotB 的绝对路径
  private LDB db;
  private final LdbColumnFamily defCF;
  private final LdbColumnFamily metaCF;
  private final LdbColumnFamily txnCF;
  private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
  private final Lock readLock = lifecycleLock.readLock();
  private final Lock writeLock = lifecycleLock.writeLock();
  private final AtomicLong contentEpoch = new AtomicLong();
  // commitAsync 最好不要用 ForkJoinPool.commonPool，给它单独一个线程池更稳
  private final ExecutorService restoreAwareExecutor = Executors.newSingleThreadExecutor();

  public LdbStore(String path) throws IOException {
    this.dbRootDir = new File(Objects.requireNonNull(path, "path")).getAbsoluteFile();
    if (!dbRootDir.exists() && !dbRootDir.mkdirs()) {
      throw new IOException("Failed to create db root dir: " + dbRootDir);
    }
    if (!dbRootDir.isDirectory()) {
      throw new IOException("DB root is not a directory: " + dbRootDir);
    }

    this.slotADir = new File(dbRootDir, "slotA");
    this.slotBDir = new File(dbRootDir, "slotB");
    this.activeFile = new File(dbRootDir, "ACTIVE");

    this.activeSlot = loadActiveSlotOrDefault();   // "A" / "B"
    this.activeDbPath = getSlotDir(activeSlot).getAbsolutePath();

    adbLdbPlugin = new AdbLdbPlugin();
    options = new Options()
        .createIfMissing(true)
        .errorIfExists(false)
        .verifyChecksums(true)
        .paranoidChecks(true)
        .cacheSize(64 << 20)
        .maxOpenFiles(1000)
        .blockSize(4096)
        .blockRestartInterval(16)
        .filterPolicy(new BloomFilterPolicy(10))
        .compressionType(CompressionType.LZ4)
        .addPlugin(adbLdbPlugin);
    configureAsyncWriteCombining(options);

    defCF = adbLdbPlugin.getDefaultColumnFamily();
    metaCF = adbLdbPlugin.getMetaColumnFamily();
    txnCF = adbLdbPlugin.getTxnColumnFamily();

    ensureSlotDirExists(getSlotDir(activeSlot));
    persistActiveSlot(activeSlot); // 启动时顺手纠正/初始化 ACTIVE
    openDb();
  }

  private static void configureAsyncWriteCombining(Options options) {
    if (!Boolean.getBoolean(ASYNC_WRITE_COMBINING_ENABLED_PROPERTY)) {
      return;
    }
    options.asyncWriteCombiningEnabled(true);
    Long maxDelayNanos = Long.getLong(
        ASYNC_WRITE_COMBINING_MAX_DELAY_NANOS_PROPERTY);
    if (maxDelayNanos != null) {
      options.asyncWriteCombiningMaxDelayNanos(maxDelayNanos);
    }
  }

  private File getSlotDir(String slot) {
    if ("A".equals(slot)) {
      return slotADir;
    }
    if ("B".equals(slot)) {
      return slotBDir;
    }
    throw new IllegalArgumentException("Unknown slot: " + slot);
  }

  private String otherSlot(String slot) {
    if ("A".equals(slot)) {
      return "B";
    }
    if ("B".equals(slot)) {
      return "A";
    }
    throw new IllegalArgumentException("Unknown slot: " + slot);
  }

  private void ensureSlotDirExists(File dir) throws IOException {
    if (!dir.exists() && !dir.mkdirs()) {
      throw new IOException("Failed to create slot dir: " + dir);
    }
    if (!dir.isDirectory()) {
      throw new IOException("Slot is not a directory: " + dir);
    }
  }

  private String loadActiveSlotOrDefault() throws IOException {
    if (!activeFile.exists()) {
      return "A";
    }

    List<String> lines = java.nio.file.Files.readAllLines(
        activeFile.toPath(),
        StandardCharsets.UTF_8);

    if (lines.isEmpty()) {
      return "A";
    }

    String value = lines.get(0).trim();
    if ("A".equals(value) || "B".equals(value)) {
      return value;
    }

    return "A";
  }

  private void persistActiveSlot(String slot) throws IOException {
    if (!"A".equals(slot) && !"B".equals(slot)) {
      throw new IOException("Invalid active slot: " + slot);
    }

    File tmp = new File(activeFile.getAbsolutePath() + ".tmp");
    java.nio.file.Files.write(
        tmp.toPath(),
        Collections.singletonList(slot),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE
    );

    movePath(tmp, activeFile);
  }

  private LDB openDbAt(String path) throws IOException {
    return new LDbImpl(options, Paths.get(path).toFile());
  }

  private void openDb() throws IOException {
    this.db = openDbAt(activeDbPath);
  }


  @Override
  public byte[] get(byte[] key) throws SQLException {
    return get(CF.DEFAULT.getCfId(), key);
  }

  @Override
  public void put(byte[] key, byte[] value) throws SQLException {
    put(CF.DEFAULT.getCfId(), key, value);
  }


  @Override
  public long addLong(byte[] key, long operand) throws SQLException {
    return addLong(CF.DEFAULT.getCfId(), key, operand);
  }


  @Override
  public Optional<Long> getLong(byte[] key) throws SQLException {
    return getLong(CF.DEFAULT.getCfId(), key);
  }

  @Override
  public void putLong(byte[] key, long value) throws SQLException {
    put(CF.DEFAULT.getCfId(), key, encodeLong(value));
  }

  @Override
  public void delete(byte[] key) throws SQLException {
    delete(CF.DEFAULT.getCfId(), key);
  }

  @Override
  public void deleteRange(byte[] startKey, byte[] endKey) throws SQLException {
    deleteRange(CF.DEFAULT.getCfId(), startKey, endKey);
  }


  @Override
  public byte[] get(byte cfId, byte[] key) throws SQLException {
    return withDbRead(db -> db.get(db.getColumnFamily(cfId), key));
  }

  @Override
  public void put(byte cfId, byte[] key, byte[] value) throws SQLException {
    withDbReadVoid(db -> db.put(db.getColumnFamily(cfId), key, value));
  }

  @Override
  public long addLong(byte cfId, byte[] key, long delta) throws SQLException {
    return withDbRead(db -> db.addLong(db.getColumnFamily(cfId), key, delta));
  }

  @Override
  public void delete(byte cfId, byte[] key) throws SQLException {
    withDbReadVoid(db -> db.delete(db.getColumnFamily(cfId), key));
  }

  @Override
  public Optional<Long> getLong(byte cfId, byte[] key) throws SQLException {
    byte[] bytes = get(cfId, key);
    return Slices.decodeLong(bytes);
  }

  @Override
  public void putLong(byte cfId, byte[] key, long value) throws SQLException {
    put(cfId, key, Slices.encodeLong(value).getBytes());
  }

  @Override
  public void deleteRange(byte cfId, byte[] startKey, byte[] endKey) throws SQLException {
    throw new UnsupportedOperationException();
  }


  @Override
  public void writeBatch(WriteBatchConsumer consumer) throws SQLException {
    withDbSession(currentDb -> {
      LdbCF ldbCF = LdbCF.of(defCF, txnCF, metaCF);

      try (LdbWriteBatchImpl batch = new LdbWriteBatchImpl();
           DelegateLdbWriteBatch delegate = new DelegateLdbWriteBatch(batch, this, ldbCF)) {

        WriteOptions options = new WriteOptions();
        AdbWriteBatch adbWriteBatch = AdbWriteBatch.direct(this, delegate);

        consumer.accept(adbWriteBatch);
        currentDb.write(batch, options);
      } catch (AdbWriteBatch.DirectWriteBatchException e) {
        throw e.getCause();
      }
    });
  }

  @Override
  public void rollback(long txnId) throws SQLException {
    withDbSession(currentDb -> {
      LdbCF ldbCF = LdbCF.of(defCF, txnCF, metaCF);
      LdbColumnFamily txnCfHandle = ldbCF.getCFHandle(CF.TXN);

      try (LdbWriteBatchImpl batch = new LdbWriteBatchImpl()) {
        WriteOptions options = new WriteOptions();

        List<TxnRefKey> keys = getTxnIndexListLocked(currentDb, txnId);
        for (TxnRefKey key : keys) {
          batch.delete(txnCfHandle, key.toBytes());
          batch.delete(key.getKey().toBytes());
        }
        for (TxnLockKey key : getTxnLockKeyListLocked(currentDb, txnId)) {
          batch.delete(txnCfHandle, key.toBytes());
        }

        currentDb.write(batch, options);
      }
    });
  }

//  public List<TxnRefKey> getTxnIndexList(long txnId) throws SQLException {
//    return withDbSession((DbSessionCallable<List<TxnRefKey>>) currentDb -> getTxnIndexListLocked(currentDb, txnId));
//  }

  private List<TxnRefKey> getTxnIndexListLocked(LDB currentDb, long txnId) throws SQLException {
    byte[] prefix = TxnRefPrefix.of(txnId, TxnKeyType.WRITE_REF).toBytes();
    byte[] end = KeyCodec.prefixEnd(prefix);

    List<TxnRefKey> keys = new ArrayList<>();

    try (VersionScanSource scan = new LdbVersionEntryCursor(
        currentDb.newSnapshotCursor(currentDb.getColumnFamily(CF.TXN.getCfId())),
        ScanDirection.FORWARD)) {

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

  private List<TxnLockKey> getTxnLockKeyListLocked(LDB currentDb, long txnId)
      throws SQLException {
    byte[] prefix = TxnRefPrefix.of(txnId, TxnKeyType.LOCK).toBytes();
    byte[] end = KeyCodec.prefixEnd(prefix);

    List<TxnLockKey> keys = new ArrayList<>();

    try (VersionScanSource scan = new LdbVersionEntryCursor(
        currentDb.newSnapshotCursor(currentDb.getColumnFamily(CF.TXN.getCfId())),
        ScanDirection.FORWARD)) {

      scan.seekToRangeStart(prefix, end);
      while (scan.isValid() && KeyCodec.startsWith(scan.key(), prefix)) {
        keys.add(TxnLockKey.fromBytes(scan.key()));
        scan.advance();
      }
      return keys;
    } catch (Exception e) {
      throw new SQLException("Failed to get txn lock list, txnId=" + txnId, e);
    }
  }

  @Override
  public CompletableFuture<Void> commitAsync(long txnId, long commitTs, List<Meta> metas) {
    return CompletableFuture.runAsync(() -> {
      try {
        commit(txnId, commitTs, metas);
      } catch (SQLException e) {
        throw new CompletionException(e);
      }
    }, restoreAwareExecutor);
  }

  private void commit(long txnId, long commitTs, List<Meta> metas) throws SQLException {
    withDbSession(currentDb -> {
      LdbCF ldbCF = LdbCF.of(defCF, txnCF, metaCF);
      LdbColumnFamily metaCfHandle = ldbCF.getCFHandle(CF.META);
      LdbColumnFamily txnCfHandle = ldbCF.getCFHandle(CF.TXN);

      try (LdbWriteBatchImpl batch = new LdbWriteBatchImpl()) {
        WriteOptions options = new WriteOptions();

        List<TxnRefKey> keys = getTxnIndexListLocked(currentDb, txnId);
        for (TxnRefKey key : keys) {
          byte[] value = currentDb.get(currentDb.getColumnFamily(key.getCfId()), key.getKey().toBytes());
          if (value == null) {
            continue;
          }

          RowValue rowValue = RowValue.decodeValue(value);

          // 删除引用
          batch.delete(txnCfHandle, key.toBytes());

          // 删除临时版本
          batch.delete(key.getKey().toBytes());

          // 保存正式版本
          VersionKey versionKey = VersionKey.of(key.getKey(), true, commitTs);
          rowValue.commitTs = commitTs;
          batch.put(versionKey.toBytes(), RowValue.encodeValue(rowValue));
        }
        for (TxnLockKey key : getTxnLockKeyListLocked(currentDb, txnId)) {
          batch.delete(txnCfHandle, key.toBytes());
        }

        if (metas != null) {
          for (Meta meta : metas) {
            batch.put(metaCfHandle, meta.getKey(), meta.getValue());
          }
        }

        currentDb.write(batch, options);
      }
      return null;
    });
  }

  @Override
  public VersionScanSource openVersionScanSource(ScanDirection direction) {
    readLock.lock();
    try {
      LDB current = db;
      if (current == null) {
        throw new IllegalStateException("Database is closed");
      }
      return new LdbVersionEntryCursor(current.newSnapshotCursor(), direction);
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public VersionScanSource openVersionScanSource(byte cfId, ScanDirection direction) {
    readLock.lock();
    try {
      LDB current = db;
      if (current == null) {
        throw new IllegalStateException("Database is closed");
      }
      return new LdbVersionEntryCursor(
          current.newSnapshotCursor(current.getColumnFamily(cfId)),
          direction);
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public VersionReadSession openVersionReadSession() {
    readLock.lock();
    try {
      LDB current = db;
      if (current == null) {
        throw new IllegalStateException("Database is closed");
      }
      return new LdbVersionReadSession(current.openReadSession());
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public VersionReadSession openVersionReadSession(byte cfId) {
    readLock.lock();
    try {
      LDB current = db;
      if (current == null) {
        throw new IllegalStateException("Database is closed");
      }
      return new LdbVersionReadSession(
          current.openReadSession(current.getColumnFamily(cfId)));
    } finally {
      readLock.unlock();
    }
  }

  /**
   * 返回 LDB 当前内容世代号。
   *
   * @return restore 成功切槽后递增的内容世代号
   */
  @Override
  public long contentEpoch() {
    return contentEpoch.get();
  }

  /**
   * 声明 LDB store 支持内容世代号。
   *
   * @return 始终返回 true
   */
  @Override
  public boolean supportsContentEpoch() {
    return true;
  }

  @Override
  public void checkpoint(String targetDir) throws IOException {
    readLock.lock();
    try {
      if (db == null) {
        throw new IOException("Database is closed");
      }
      db.checkpoint(targetDir);
    } finally {
      readLock.unlock();
    }
  }
  @Override
  public void restore(String sourceDir) throws IOException {
    File source = new File(Objects.requireNonNull(sourceDir, "sourceDir")).getAbsoluteFile();
    if (!source.exists() || !source.isDirectory()) {
      throw new IOException("Restore source does not exist or is not a directory: " + source);
    }

    String targetSlot;
    File targetDir;

    readLock.lock();
    try {
      targetSlot = otherSlot(activeSlot);
      targetDir = getSlotDir(targetSlot);
    } finally {
      readLock.unlock();
    }

    ensureSlotDirExists(targetDir);

    LDB stagedDb = null;
    LDB oldDb = null;
    boolean switched = false;

    try {
      // 1) 先把非活动槽位清空
      if (targetDir.exists()) {
        deleteDirectoryContentsStrict(targetDir);
      }

      // 2) checkpoint 恢复到非活动槽位
      copyDirectory(source, targetDir);
      validateCheckpointDirectory(targetDir);

      // 3) 先尝试打开非活动槽位上的 DB
      stagedDb = openDbAt(targetDir.getAbsolutePath());

      // 4) 短暂切换窗口
      writeLock.lock();
      try {
        oldDb = this.db;
        if (oldDb == null) {
          throw new IOException("Current database is closed");
        }

        this.db = stagedDb;
        this.activeSlot = targetSlot;
        this.activeDbPath = targetDir.getAbsolutePath();
        persistActiveSlot(targetSlot);

        stagedDb = null;
        switched = true;
      } finally {
        writeLock.unlock();
      }

      // 5) 切换完成后再关闭旧 DB
      closeDbQuietly(oldDb);
      contentEpoch.incrementAndGet();

      // 注意：旧槽位不删除，保留到下一次 restore 再覆盖
    } catch (Exception e) {
      if (!switched) {
        closeDbQuietly(stagedDb);
        // 目标槽位准备失败时，清空它，避免残留半成品
        try {
          if (targetDir.exists()) {
            deleteDirectoryContentsStrict(targetDir);
          }
        } catch (Exception ignored) {
        }
      }

      if (e instanceof IOException) {
        throw (IOException) e;
      }
      throw new IOException("Dual-slot restore failed from " + sourceDir, e);
    }
  }

  private void deleteDirectoryContentsStrict(File dir) throws IOException {
    if (!dir.exists()) {
      return;
    }
    if (!dir.isDirectory()) {
      throw new IOException("Not a directory: " + dir);
    }

    File[] children = dir.listFiles();
    if (children == null) {
      throw new IOException("Failed to list directory: " + dir);
    }

    for (File child : children) {
      deleteDirectoryStrict(child);
    }
  }

  private void validateCheckpointDirectory(File dir) throws IOException {
    if (!dir.exists() || !dir.isDirectory()) {
      throw new IOException("Checkpoint directory is invalid: " + dir);
    }

    File current = new File(dir, "CURRENT");
    if (!current.exists() || !current.isFile()) {
      throw new IOException("Checkpoint directory missing CURRENT file: " + dir);
    }
  }

  private void copyDirectory(File source, File target) throws IOException {
    if (source.isDirectory()) {
      if (!target.exists() && !target.mkdirs()) {
        throw new IOException("Failed to create directory: " + target);
      }

      File[] children = source.listFiles();
      if (children == null) {
        throw new IOException("Failed to list directory: " + source);
      }

      for (File child : children) {
        copyDirectory(child, new File(target, child.getName()));
      }
    } else {
      java.nio.file.Files.copy(
          source.toPath(),
          target.toPath(),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING,
          java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
      );
    }
  }

  private void movePath(File source, File target) throws IOException {
    try {
      java.nio.file.Files.move(
          source.toPath(),
          target.toPath(),
          java.nio.file.StandardCopyOption.ATOMIC_MOVE
      );
      return;
    } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
      // fallback
    }

    try {
      java.nio.file.Files.move(
          source.toPath(),
          target.toPath(),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING
      );
    } catch (IOException moveEx) {
      // 最后兜底：copy + delete
      copyDirectory(source, target);
      deleteDirectoryStrict(source);
    }
  }

  private void deleteDirectoryStrict(File file) throws IOException {
    if (!file.exists()) {
      return;
    }

    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children == null) {
        throw new IOException("Failed to list directory: " + file);
      }
      for (File child : children) {
        deleteDirectoryStrict(child);
      }
    }

    if (!file.delete()) {
      throw new IOException("Failed to delete: " + file);
    }
  }

  @Override
  public void close() {
    writeLock.lock();
    try {
      closeDbQuietly(db);
      db = null;
    } finally {
      writeLock.unlock();
    }
    restoreAwareExecutor.shutdownNow();
  }

  private void closeDbQuietly(LDB targetDb) {
    if (targetDb == null) {
      return;
    }
    try {
      targetDb.close();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private <T> T withDbRead(DbCallable<T> action) throws SQLException {
    readLock.lock();
    try {
      ensureOpen();
      return action.call(db);
    } catch (IOException e) {
      throw new SQLException(e);
    } finally {
      readLock.unlock();
    }
  }

  private void withDbReadVoid(DbConsumer action) throws SQLException {
    readLock.lock();
    try {
      ensureOpen();
      action.accept(db);
    } catch (IOException e) {
      throw new SQLException(e);
    } finally {
      readLock.unlock();
    }
  }

  private <T> T withDbSession(DbSessionCallable<T> action) throws SQLException {
    readLock.lock();
    try {
      LDB current = db;
      if (current == null) {
        throw new SQLException("Database is closed");
      }
      return action.call(current);
    } catch (SQLException e) {
      throw e;
    } catch (Exception e) {
      throw new SQLException(e);
    } finally {
      readLock.unlock();
    }
  }

  private void withDbSession(DbSessionRunnable action) throws SQLException {
    readLock.lock();
    try {
      LDB current = db;
      if (current == null) {
        throw new SQLException("Database is closed");
      }
      action.run(current);
    } catch (SQLException e) {
      throw e;
    } catch (Exception e) {
      throw new SQLException(e);
    } finally {
      readLock.unlock();
    }
  }

  private void ensureOpen() throws IOException {
    if (db == null) {
      throw new IOException("Database is closed");
    }
  }

  @FunctionalInterface
  private interface DbCallable<T> {
    T call(LDB db) throws SQLException, IOException;
  }

  @FunctionalInterface
  private interface DbConsumer {
    void accept(LDB db) throws SQLException, IOException;
  }

  @FunctionalInterface
  private interface DbSessionCallable<T> {
    T call(LDB db) throws Exception;
  }

  @FunctionalInterface
  private interface DbSessionRunnable {
    void run(LDB db) throws Exception;
  }
}
