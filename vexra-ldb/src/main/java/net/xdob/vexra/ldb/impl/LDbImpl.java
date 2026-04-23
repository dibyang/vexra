package net.xdob.vexra.ldb.impl;

import com.google.common.base.Throwables;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import net.xdob.vexra.ldb.*;
import net.xdob.vexra.ldb.impl.Filename.FileInfo;
import net.xdob.vexra.ldb.impl.Filename.FileType;
import net.xdob.vexra.ldb.impl.LdbWriteBatchImpl.Handler;
import net.xdob.vexra.ldb.impl.MemTable.MemTableIterator;
import net.xdob.vexra.ldb.table.BytewiseComparator;
import net.xdob.vexra.ldb.table.CustomUserComparator;
import net.xdob.vexra.ldb.table.TableBuilder;
import net.xdob.vexra.ldb.table.UserComparator;
import net.xdob.vexra.ldb.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.Thread.UncaughtExceptionHandler;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;
import static net.xdob.vexra.ldb.impl.DbConstants.*;
import static net.xdob.vexra.ldb.impl.SequenceNumber.MAX_SEQUENCE_NUMBER;
import static net.xdob.vexra.ldb.impl.ValueType.*;
import static net.xdob.vexra.ldb.util.SizeOf.SIZE_OF_INT;
import static net.xdob.vexra.ldb.util.SizeOf.SIZE_OF_LONG;
import static net.xdob.vexra.ldb.util.Slices.readLengthPrefixedBytes;
import static net.xdob.vexra.ldb.util.Slices.writeLengthPrefixedBytes;

@SuppressWarnings("AccessingNonPublicFieldOfAnotherObject")
public class LDbImpl implements LDB {
  static Logger LOG = LoggerFactory.getLogger(LDbImpl.class);
  private final Options options;
  private final File databaseDir;
  private final DbLock dbLock;

  private final AtomicBoolean shuttingDown = new AtomicBoolean();
  private final ReentrantLock mutex = new ReentrantLock();
  private final Condition backgroundCondition = mutex.newCondition();

  private final Set<Long> pendingOutputs = new HashSet<>();
  private final ConcurrentHashMap<Integer, ColumnFamilyState> cfs = new ConcurrentHashMap<>();

  private final InternalKeyComparator internalKeyComparator;

  private volatile Throwable backgroundException;
  private final ExecutorService compactionExecutor;
  private Future<?> backgroundCompaction;

  private final TableCache tableCache;
  private final VersionSet versions;
  private LogWriter log;

  private ManualCompaction manualCompaction;
  private long maxRecoveredSequence = 0;
  private long lastSequence;

  public LDbImpl(Options options, File databaseDir) throws IOException {
    requireNonNull(options, "options is null");
    requireNonNull(databaseDir, "databaseDir is null");
    this.options = options;
    this.databaseDir = databaseDir;

    DBComparator comparator = options.comparator();
    UserComparator userComparator = comparator != null
        ? new CustomUserComparator(comparator)
        : new BytewiseComparator();
    internalKeyComparator = new InternalKeyComparator(userComparator);

    ThreadFactory compactionThreadFactory = new ThreadFactoryBuilder()
        .setNameFormat("leveldb-compaction-%s")
        .setUncaughtExceptionHandler(new UncaughtExceptionHandler() {
          @Override
          public void uncaughtException(Thread t, Throwable e) {
            System.out.printf("%s%n", t);
            e.printStackTrace();
          }
        })
        .build();
    compactionExecutor = Executors.newSingleThreadExecutor(compactionThreadFactory);

    checkArgument(options.getColumnFamilies() != null && !options.getColumnFamilies().isEmpty(),
        "No column families configured");

    databaseDir.mkdirs();
    checkArgument(databaseDir.exists(),
        "Database directory '%s' does not exist and could not be created", databaseDir);
    checkArgument(databaseDir.isDirectory(),
        "Database directory '%s' is not a directory", databaseDir);

    mutex.lock();
    try {
      dbLock = new DbLock(new File(databaseDir, Filename.lockFileName()));

      for (LdbColumnFamily cf : options.getColumnFamilies()) {
        ColumnFamilyState cfState = new ColumnFamilyState(cf, databaseDir, options, internalKeyComparator);
        cfs.put(cf.getId(), cfState);
      }

      int tableCacheSize = options.maxOpenFiles() - 10;
      this.tableCache = new TableCache(
          databaseDir,
          tableCacheSize,
          new InternalUserComparator(internalKeyComparator),
          options.verifyChecksums(),
          options);

      this.versions = new VersionSet(databaseDir, tableCache, internalKeyComparator, options);
      this.versions.recover();

      long manifestRecoveredSequence = versions.getLastSequence();

      VersionEdit edit = new VersionEdit();
      long walRecoveredSequence = recoverLogs(edit);
      maxRecoveredSequence = Math.max(maxRecoveredSequence, walRecoveredSequence);

      lastSequence = Math.max(manifestRecoveredSequence, maxRecoveredSequence);
      versions.setLastSequence(lastSequence);

      // 再创建新 WAL，并把 logNumber + lastSequence 一起写进 MANIFEST
      long logFileNumber = versions.getNextFileNumber();
      this.log = Logs.createLogWriter(
          new File(databaseDir, Filename.logFileName(logFileNumber)),
          logFileNumber,  options);

      edit.setLogNumber(log.getFileNumber());
      versions.logAndApply(edit);

      deleteObsoleteFiles();
      maybeScheduleCompaction();
    } finally {
      mutex.unlock();
    }
  }

  private long recoverLogs(VersionEdit edit) throws IOException {
    long minLogNumber = versions.getLogNumber();
    long previousLogNumber = versions.getPrevLogNumber();
    List<File> filenames = Filename.listFiles(databaseDir);

    List<Long> logs = new ArrayList<>();
    for (File filename : filenames) {
      FileInfo fileInfo = Filename.parseFileName(filename);
      if (fileInfo != null
          && fileInfo.getFileType() == FileType.LOG
          && ((fileInfo.getFileNumber() >= minLogNumber)
          || (fileInfo.getFileNumber() == previousLogNumber))) {
        logs.add(fileInfo.getFileNumber());
      }
    }

    Collections.sort(logs);

    Map<Integer, MemTable> recoveringMemTables = new HashMap<>();
    for (LdbColumnFamily cf : options.getColumnFamilies()) {
      recoveringMemTables.put(cf.getId(), new MemTable(internalKeyComparator));
    }
    long maxSequence = 0;
    for (Long fileNumber : logs) {
      long seq = recoverLogFile(fileNumber, edit, recoveringMemTables);
      if (seq > maxSequence) {
        maxSequence = seq;
      }
    }
    return maxSequence;
  }

  private long recoverLogFile(long fileNumber, VersionEdit edit, Map<Integer, MemTable> recoveringMemTables) throws IOException {
    checkState(mutex.isHeldByCurrentThread());

    File file = new File(databaseDir, Filename.logFileName(fileNumber));
    try (FileInputStream fis = new FileInputStream(file);
         FileChannel channel = fis.getChannel()) {

      LogMonitor logMonitor = LogMonitors.logMonitor();
      LogReader logReader = new LogReader(channel, logMonitor, true, 0);

      long maxSequence = 0;

      for (Slice record = logReader.readRecord(); record != null; record = logReader.readRecord()) {
        SliceInput sliceInput = record.input();

        if (sliceInput.available() < 12) {
          logMonitor.corruption(sliceInput.available(), "log record too small");
          continue;
        }

        long sequenceBegin = sliceInput.readLong();
        int updateSize = sliceInput.readInt();

        LdbWriteBatchImpl writeBatch = readWriteBatch(sliceInput, updateSize);
        writeBatch.forEach(new RecoverIntoHandler(recoveringMemTables, versions , sequenceBegin));

        long recoveredLastSequence = sequenceBegin + updateSize - 1;
        if (recoveredLastSequence > maxSequence) {
          maxSequence = recoveredLastSequence;
        }

        for (Map.Entry<Integer, MemTable> e : recoveringMemTables.entrySet()) {
          if (e.getValue().approximateMemoryUsage() > options.writeBufferSize()) {
            writeLevel0Table(e.getKey(), e.getValue(), edit, null);
            recoveringMemTables.put(e.getKey(), new MemTable(internalKeyComparator));
          }
        }
      }

      for (Map.Entry<Integer, MemTable> e : recoveringMemTables.entrySet()) {
        if (!e.getValue().isEmpty()) {
          writeLevel0Table(e.getKey(), e.getValue(), edit, null);
        }
      }

      return maxSequence;
    }
  }

  @Override
  public void close() {
    if (shuttingDown.getAndSet(true)) {
      return;
    }

    compactionExecutor.shutdown();
    try {
      mutex.lock();
      try {
        while (backgroundCompaction != null) {
          backgroundCondition.awaitUninterruptibly();
        }
      } finally {
        mutex.unlock();
      }

      compactionExecutor.awaitTermination(1, TimeUnit.DAYS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      try {
        if (log != null) {
          log.close();
        }
      } catch (Exception ignored) {
      }

      try {
        versions.destroy();
      } catch (Exception ignored) {
      }

      try {
        tableCache.close();
      } catch (Exception ignored) {
      }

      for (ColumnFamilyState cfState : cfs.values()) {
        try {
          cfState.close();
        } catch (Exception ignored) {
        }
      }

      try {
        dbLock.release();
      } catch (Exception ignored) {
      }
    }
  }

  @Override
  public String getProperty(String name) {
    checkBackgroundException();
    return null;
  }

  private void deleteObsoleteFiles() {
    checkState(mutex.isHeldByCurrentThread());

    Set<Long> live = new HashSet<>(pendingOutputs);
    for (FileMetaData fileMetaData : versions.getLiveFiles()) {
      live.add(fileMetaData.getNumber());
    }

    Set<Long> referencedLogs = getReferencedLogNumbers();

    for (File file : Filename.listFiles(databaseDir)) {
      FileInfo fileInfo = Filename.parseFileName(file);
      if (fileInfo == null) {
        continue;
      }

      long number = fileInfo.getFileNumber();
      boolean keep = true;
      switch (fileInfo.getFileType()) {
        case LOG:
          keep = referencedLogs.contains(number);
          break;
        case DESCRIPTOR:
          keep = (number >= versions.getManifestFileNumber());
          break;
        case TABLE:
          keep = live.contains(number);
          break;
        case TEMP:
          keep = live.contains(number);
          break;
        case CURRENT:
        case DB_LOCK:
        case INFO_LOG:
          keep = true;
          break;
      }

      if (!keep) {
        if (fileInfo.getFileType() == FileType.TABLE) {
          tableCache.evict(number);
        }
        file.delete();
      }
    }
  }

  /**
   * 刷 MemTable
   */
  public void flushMemTable() {
    mutex.lock();
    try {
      LdbWriteBatchImpl empty = new LdbWriteBatchImpl();
      for (ColumnFamilyState state : cfs.values()) {
        empty.touch(state.getColumnFamily());
      }
      makeRoomForWrite(true, empty);
      for (ColumnFamilyState state : cfs.values()) {
        while (state.getImmutableMemTable() != null) {
          backgroundCondition.awaitUninterruptibly();
        }
      }
    } finally {
      mutex.unlock();
    }
  }

  public void flushMemTable(LdbColumnFamily cf) {
    mutex.lock();
    try {
      LdbWriteBatchImpl empty = new LdbWriteBatchImpl();
      empty.touch(cf);
      makeRoomForWrite(true, empty);

      ColumnFamilyState state = getColumnFamilyState(cf);
      while (state.getImmutableMemTable() != null) {
        backgroundCondition.awaitUninterruptibly();
      }
    } finally {
      mutex.unlock();
    }
  }

  /**
   * 手工 compact 先按 default CF 处理；你后面可以再扩一个带 cfId 的 API。
   */
  public void compactRange(int level, Slice start, Slice end) {
    checkArgument(level >= 0, "level is negative");
    checkArgument(level + 1 < NUM_LEVELS, "level is greater than or equal to %s", NUM_LEVELS);
    requireNonNull(start, "start is null");
    requireNonNull(end, "end is null");

    mutex.lock();
    try {
      while (manualCompaction != null) {
        backgroundCondition.awaitUninterruptibly();
      }
      manualCompaction = new ManualCompaction(LdbColumnFamily.DEFAULT.getId(), level, start, end);
      maybeScheduleCompaction();

      while (this.manualCompaction != null) {
        backgroundCondition.awaitUninterruptibly();
      }
    } finally {
      mutex.unlock();
    }
  }

  private void maybeScheduleCompaction() {
    checkState(mutex.isHeldByCurrentThread());

    if (backgroundCompaction != null) {
      return;
    }
    if (shuttingDown.get()) {
      return;
    }
    if (!hasCompactionWork()) {
      return;
    }

    backgroundCompaction = compactionExecutor.submit(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        try {
          backgroundCall();
        } catch (DatabaseShutdownException ignored) {
        } catch (Throwable e) {
          backgroundException = e;
        }
        return null;
      }
    });
  }

  private boolean hasCompactionWork() {
    if (manualCompaction != null) {
      return true;
    }
    for (ColumnFamilyState cfState : cfs.values()) {
      if (cfState.getImmutableMemTable() != null) {
        return true;
      }
    }
    return versions.needsCompaction();
  }

  public void checkBackgroundException() {
    Throwable e = backgroundException;
    if (e != null) {
      throw new BackgroundProcessingException(e);
    }
  }

  private void backgroundCall() throws IOException {
    mutex.lock();
    try {
      if (backgroundCompaction == null) {
        return;
      }

      try {
        if (!shuttingDown.get()) {
          backgroundCompaction();
        }
      } finally {
        backgroundCompaction = null;
      }
    } finally {
      try {
        maybeScheduleCompaction();
      } finally {
        try {
          backgroundCondition.signalAll();
        } finally {
          mutex.unlock();
        }
      }
    }
  }

  private void backgroundCompaction() throws IOException {
    checkState(mutex.isHeldByCurrentThread());

    for (ColumnFamilyState cfState : cfs.values()) {
      compactMemTableInternal(cfState);
    }

    Compaction compaction = null;
    if (manualCompaction != null) {
      compaction = versions.compactRange(
          manualCompaction.cfId,
          manualCompaction.level,
          new InternalKey(manualCompaction.begin, MAX_SEQUENCE_NUMBER, VALUE),
          new InternalKey(manualCompaction.end, 0, DELETION));
    } else {
      for (ColumnFamilyState cfState : cfs.values()) {
        compaction = versions.pickCompaction(cfState.getColumnFamily().getId());
        if (compaction != null) {
          break;
        }
      }
    }

    if (compaction == null) {
      if (manualCompaction != null) {
        manualCompaction = null;
      }
      return;
    }

    if (manualCompaction == null && compaction.isTrivialMove()) {
      checkState(compaction.getLevelInputs().size() == 1);
      int cfId = compaction.getCfId();
      FileMetaData fileMetaData = compaction.getLevelInputs().get(0);
      compaction.getEdit().deleteFile(cfId, compaction.getLevel(), fileMetaData.getNumber());
      compaction.getEdit().addFile(cfId, compaction.getLevel() + 1, fileMetaData);
      versions.logAndApply(compaction.getEdit());
    } else {
      CompactionState compactionState = new CompactionState(compaction);
      doCompactionWork(compactionState);
      cleanupCompaction(compactionState);
    }

    if (manualCompaction != null) {
      manualCompaction = null;
    }
  }

  private void cleanupCompaction(CompactionState compactionState) {
    checkState(mutex.isHeldByCurrentThread());

    if (compactionState.builder != null) {
      compactionState.builder.abandon();
    } else {
      checkArgument(compactionState.outfile == null);
    }

    for (FileMetaData output : compactionState.outputs) {
      pendingOutputs.remove(output.getNumber());
    }
  }

  private Map<Integer, ColumnFamilyState> getColumnFamilyStateMap() {
    return Collections.unmodifiableMap(cfs);
  }

  @Override
  public byte[] get(byte[] key) throws DBException {
    return get(key, new ReadOptions());
  }

  @Override
  public byte[] get(LdbColumnFamily cf, byte[] key) {
    return get(cf, key, new ReadOptions());
  }

  public byte[] get(LdbColumnFamily cf, byte[] key, ReadOptions options) throws DBException {
    checkBackgroundException();
    ColumnFamilyState state = getColumnFamilyState(cf);
    LookupKey lookupKey;

    mutex.lock();
    try {
      SnapshotImpl snapshot = getSnapshot(cf, options);
      lookupKey = new LookupKey(Slices.wrappedBuffer(key), snapshot.getLastSequence());

      LookupResult lookupResult = state.getMemTable().get(lookupKey);
      if (lookupResult != null) {
        Slice value = lookupResult.getValue();
        return value == null ? null : value.getBytes();
      }

      if (state.getImmutableMemTable() != null) {
        lookupResult = state.getImmutableMemTable().get(lookupKey);
        if (lookupResult != null) {
          Slice value = lookupResult.getValue();
          return value == null ? null : value.getBytes();
        }
      }
    } finally {
      mutex.unlock();
    }

    LookupResult lookupResult = versions.get(cf.getId(), lookupKey);

    mutex.lock();
    try {
      if (versions.needsCompaction()) {
        maybeScheduleCompaction();
      }
    } finally {
      mutex.unlock();
    }

    if (lookupResult != null) {
      Slice value = lookupResult.getValue();
      if (value != null) {
        return value.getBytes();
      }
    }
    return null;
  }

  @Override
  public byte[] get(byte[] key, ReadOptions options) throws DBException {
    return get(LdbColumnFamily.DEFAULT, key, options);
  }

  @Override
  public void put(byte[] key, byte[] value) throws DBException {
    put(key, value, new WriteOptions());
  }

  @Override
  public Snapshot put(byte[] key, byte[] value, WriteOptions options) throws DBException {
    return writeInternal((LdbWriteBatchImpl) new LdbWriteBatchImpl().put(key, value), options);
  }

  @Override
  public void delete(byte[] key) throws DBException {
    writeInternal((LdbWriteBatchImpl) new LdbWriteBatchImpl().delete(key), new WriteOptions());
  }

  @Override
  public long addLong(byte[] key, long delta) throws DBException {
    return addLong(LdbColumnFamily.DEFAULT, key, delta);
  }

  @Override
  public void put(LdbColumnFamily cf, byte[] key, byte[] value) throws DBException {
    put(cf, key, value, new WriteOptions());
  }

  @Override
  public void delete(LdbColumnFamily cf, byte[] key) throws DBException {
    delete(cf, key, new WriteOptions());
  }

  @Override
  public long addLong(LdbColumnFamily cf, byte[] key, long delta) throws DBException {
    addLong(cf, key, delta, new WriteOptions());
    byte[] bytes = get(cf, key);
    if (bytes == null) {
      throw new IllegalArgumentException("key not found");
    }
    return Slices.decodeLong(bytes).orElseThrow(() -> new IllegalArgumentException("key not found"));
  }

  @Override
  public Snapshot delete(byte[] key, WriteOptions options) throws DBException {
    return delete(LdbColumnFamily.DEFAULT, key, options);
  }

  @Override
  public Snapshot put(LdbColumnFamily cf, byte[] key, byte[] value, WriteOptions options) throws DBException {
    return writeInternal((LdbWriteBatchImpl) new LdbWriteBatchImpl().put(cf, key, value), options);
  }

  @Override
  public Snapshot delete(LdbColumnFamily cf, byte[] key, WriteOptions options) throws DBException {
    return writeInternal((LdbWriteBatchImpl) new LdbWriteBatchImpl().delete(cf, key), options);
  }

  @Override
  public Snapshot addLong(LdbColumnFamily cf, byte[] key, long delta, WriteOptions options) throws DBException {
    return writeInternal((LdbWriteBatchImpl) new LdbWriteBatchImpl().addLong(cf, key, delta), options);
  }

  @Override
  public void write(LdbWriteBatch updates) throws DBException {
    writeInternal((LdbWriteBatchImpl) updates, new WriteOptions());
  }

  @Override
  public Snapshot write(LdbWriteBatch updates, WriteOptions options) throws DBException {
    return writeInternal((LdbWriteBatchImpl) updates, options);
  }

  public Snapshot writeInternal(LdbWriteBatchImpl updates, WriteOptions options) throws DBException {
    checkBackgroundException();
    mutex.lock();
    try {
      long sequenceEnd;

      if (!updates.isEmpty()) {
        makeRoomForWrite(false, updates);

        long sequenceBegin = lastSequence + 1;
        sequenceEnd = sequenceBegin + updates.size() - 1;
        lastSequence = sequenceEnd;
        versions.setLastSequence(sequenceEnd);

        Slice record = writeWriteBatch(updates, sequenceBegin);
        appendToLog(record, options.sync());

        updates.forEach(new InsertIntoHandler(getColumnFamilyStateMap(), sequenceBegin, versions ));
      } else {
        sequenceEnd = lastSequence;
      }

      if (options.snapshot()) {
        return new SnapshotImpl(null, sequenceEnd);
      } else {
        return null;
      }
    } finally {
      mutex.unlock();
    }
  }

  private void appendToLog(Slice record, boolean sync) {
    try {
      log.addRecord(record, sync);
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public LdbWriteBatch createWriteBatch() {
    checkBackgroundException();
    return new LdbWriteBatchImpl();
  }


  public RawCursor newRawCursor() {
    return newRawCursor(LdbColumnFamily.DEFAULT);
  }

  RawCursor newRawCursor(LdbColumnFamily cf) {
    return new DbRawCursor(internalIterator(cf));
  }

  @Override
  public SnapshotCursor newSnapshotCursor() {
    return newSnapshotCursor(LdbColumnFamily.DEFAULT);
  }

  @Override
  public SnapshotCursor newSnapshotCursor(LdbColumnFamily cf) {
    checkBackgroundException();
    requireNonNull(cf, "cf is null");

    SnapshotImpl snapshot = getSnapshot(cf, new ReadOptions());
    return new DbSnapshotCursor(
        newRawCursor(cf),
        snapshot,
        internalKeyComparator
    );
  }

  DbIterator internalIterator(LdbColumnFamily cf) {
    checkBackgroundException();
    mutex.lock();
    try {
      ColumnFamilyState state = getColumnFamilyState(cf);

      MemTableIterator immutableIterator = null;
      if (state.getImmutableMemTable() != null) {
        immutableIterator = state.getImmutableMemTable().iterator();
      }

      Version current = versions.getCurrent();
      return new DbIterator(
          state.getMemTable().iterator(),
          immutableIterator,
          current,
          current.getLevel0Files(cf.getId()),
          current.getLevelIterators(cf.getId()),
          internalKeyComparator);
    } finally {
      mutex.unlock();
    }
  }

  @Override
  public Snapshot getSnapshot() {
    checkBackgroundException();
    return getSnapshot(LdbColumnFamily.DEFAULT, new ReadOptions());
  }

  @Override
  public Snapshot getSnapshot(LdbColumnFamily cf) {
    return getSnapshot(cf, new ReadOptions());
  }

  private SnapshotImpl getSnapshot(ReadOptions options) {
    return getSnapshot(LdbColumnFamily.DEFAULT, options);
  }

  private SnapshotImpl getSnapshot(LdbColumnFamily cf, ReadOptions options) {
    SnapshotImpl snapshot;
    if (options.snapshot() != null) {
      snapshot = (SnapshotImpl) options.snapshot();
    } else {
      snapshot = new SnapshotImpl(versions.getCurrent(), lastSequence);
      //snapshot.close();
    }
    return snapshot;
  }

  public int numberOfFilesInLevel(int level) {
    return numberOfFilesInLevel(LdbColumnFamily.DEFAULT, level);
  }

  @Override
  public int numberOfFilesInLevel(LdbColumnFamily cf, int level) {
    return versions.getCurrent().getFiles(cf.getId(), level).size();
  }

  private void makeRoomForWrite(boolean force, LdbWriteBatchImpl updates) {
    checkState(mutex.isHeldByCurrentThread());

    boolean allowDelay = !force;
    List<ColumnFamilyState> touched = getTouchedColumnFamilies(updates);

    while (true) {
      if (allowDelay && anyLevel0Slowdown(touched)) {
        try {
          mutex.unlock();
          Thread.sleep(1);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        } finally {
          mutex.lock();
        }
        allowDelay = false;
        continue;
      }

      boolean hasRoom = true;
      boolean shouldWait = false;
      List<ColumnFamilyState> needRotate = new ArrayList<>();

      for (ColumnFamilyState cfState : touched) {
        int cfId = cfState.getColumnFamily().getId();

        if (!force && cfState.getMemTable().approximateMemoryUsage() <= options.writeBufferSize()) {
          continue;
        }

        hasRoom = false;

        if (cfState.getImmutableMemTable() != null) {
          shouldWait = true;
          break;
        }

        if (versions.numberOfFilesInLevel(cfId, 0) >= L0_STOP_WRITES_TRIGGER) {
          shouldWait = true;
          break;
        }

        needRotate.add(cfState);
      }

      if (hasRoom) {
        break;
      }

      if (shouldWait) {
        backgroundCondition.awaitUninterruptibly();
        continue;
      }

      if (!needRotate.isEmpty()) {
        rotateMemTables(needRotate);
      }

      force = false;
      maybeScheduleCompaction();
    }
  }


  private void rotateMemTables(List<ColumnFamilyState> states) {
    checkState(mutex.isHeldByCurrentThread());
    checkArgument(states != null && !states.isEmpty(), "states is empty");

    long oldLogNumber = log.getFileNumber();

    // 1. 先关闭旧 WAL
    try {
      log.close();
    } catch (IOException e) {
      throw new RuntimeException("Unable to close log file " + databaseDir, e);
    }

    // 2. 一次性创建新 WAL
    long newLogNumber = versions.getNextFileNumber();
    try {
      log = Logs.createLogWriter(
          new File(databaseDir, Filename.logFileName(newLogNumber)),
          newLogNumber, options);
    } catch (IOException e) {
      throw new RuntimeException("Unable to open new log file in " + databaseDir, e);
    }

    // 3. 把新 logNumber 持久化到 MANIFEST
    VersionEdit edit = new VersionEdit();
    edit.setLogNumber(newLogNumber);
    edit.setPreviousLogNumber(oldLogNumber);
    try {
      versions.logAndApply(edit);
    } catch (IOException e) {
      throw new RuntimeException("Unable to persist new log number", e);
    }

    // 4. 所有需要 rotate 的 CF 共享这一个 oldLogNumber
    for (ColumnFamilyState cfState : states) {
      cfState.setImmutableLogNumber(oldLogNumber);
      cfState.setImmutableMemTable(cfState.getMemTable());
      cfState.setMemTable(new MemTable(internalKeyComparator));
    }
  }

  private List<ColumnFamilyState> getTouchedColumnFamilies(LdbWriteBatchImpl updates) {
    List<ColumnFamilyState> result = new ArrayList<>();
    for (LdbColumnFamily cf : updates.getColumnFamilies()) {
      result.add(getColumnFamilyState(cf));
    }
    return result;
  }

  private ColumnFamilyState getColumnFamilyState(LdbColumnFamily cf) {
    ColumnFamilyState familyState = cfs.get(cf.getId());
    if (familyState == null) {
      throw new IllegalArgumentException("Column family " + cf.getName() + " does not exist");
    }
    return familyState;
  }

  private boolean anyLevel0Slowdown(List<ColumnFamilyState> states) {
    for (ColumnFamilyState state : states) {
      if (versions.numberOfFilesInLevel(state.getColumnFamily().getId(), 0) > L0_SLOWDOWN_WRITES_TRIGGER) {
        return true;
      }
    }
    return false;
  }


  private void compactMemTableInternal(ColumnFamilyState cfState) throws IOException {
    checkState(mutex.isHeldByCurrentThread());

    if (cfState.getImmutableMemTable() == null) {
      return;
    }

    try {
      VersionEdit edit = new VersionEdit();
      int cfId = cfState.getColumnFamily().getId();
      Version base = versions.getCurrent();

      writeLevel0Table(cfId, cfState.getImmutableMemTable(), edit, base);

      if (shuttingDown.get()) {
        throw new DatabaseShutdownException("Database shutdown during memtable compaction");
      }

      edit.setPreviousLogNumber(0);
      edit.setLogNumber(log.getFileNumber());
      versions.logAndApply(edit);

      cfState.setImmutableMemTable(null);
      cfState.setImmutableLogNumber(0);

      deleteObsoleteFiles();
    } finally {
      backgroundCondition.signalAll();
    }
  }

  private Set<Long> getReferencedLogNumbers() {
    Set<Long> referenced = new HashSet<>();
    referenced.add(versions.getLogNumber()); // 当前 log 一定保留

    for (ColumnFamilyState cfState : cfs.values()) {
      long immutableLogNumber = cfState.getImmutableLogNumber();
      if (immutableLogNumber > 0) {
        referenced.add(immutableLogNumber);
      }
    }
    return referenced;
  }

  private void writeLevel0Table(int cfId, MemTable mem, VersionEdit edit, Version base)
      throws IOException {
    checkState(mutex.isHeldByCurrentThread());

    if (mem.isEmpty()) {
      return;
    }

    long fileNumber = versions.getNextFileNumber();
    pendingOutputs.add(fileNumber);
    mutex.unlock();

    FileMetaData meta;
    try {
      meta = buildTable(cfId, mem, fileNumber);
    } finally {
      mutex.lock();
    }

    pendingOutputs.remove(fileNumber);

    int level = 0;
    if (meta != null && meta.getFileSize() > 0) {
      Slice minUserKey = meta.getSmallest().getUserKey();
      Slice maxUserKey = meta.getLargest().getUserKey();
      if (base != null) {
        level = base.pickLevelForMemTableOutput(cfId, minUserKey, maxUserKey);
      }
      edit.addFile(cfId, level, meta);
    }

  }

  private FileMetaData buildTable(int cfId,
                                  SeekingIterable<InternalKey, Slice> data,
                                  long fileNumber) throws IOException {
    File file = new File(databaseDir, Filename.tableFileName(fileNumber));
    try {
      InternalKey smallest = null;
      InternalKey largest = null;

      FileChannel channel = new FileOutputStream(file).getChannel();
      try {
        TableBuilder tableBuilder = new TableBuilder(
            options,
            channel,
            new InternalUserComparator(internalKeyComparator));

        for (Entry<InternalKey, Slice> entry : data) {
          InternalKey key = entry.getKey();
          if (smallest == null) {
            smallest = key;
          }
          largest = key;
          tableBuilder.add(key.encode(), entry.getValue());
        }

        tableBuilder.finish();
      } finally {
        try {
          if (options.forceSstOnFlush()) {
            channel.force(true);
          }
        } finally {
          channel.close();
        }
      }

      if (smallest == null) {
        return null;
      }

      FileMetaData fileMetaData = new FileMetaData(cfId, fileNumber, file.length(), smallest, largest);
      tableCache.newIterator(fileMetaData);
      return fileMetaData;
    } catch (IOException e) {
      file.delete();
      throw e;
    }
  }

  private void doCompactionWork(CompactionState compactionState) throws IOException {
    checkState(mutex.isHeldByCurrentThread());
    checkArgument(
        versions.numberOfBytesInLevel(compactionState.getCompaction().getCfId(),
            compactionState.getCompaction().getLevel()) > 0);
    checkArgument(compactionState.builder == null);
    checkArgument(compactionState.outfile == null);

    compactionState.smallestSnapshot = lastSequence;

    mutex.unlock();
    try {
      MergingIterator iterator = versions.makeInputIterator(compactionState.compaction);

      Slice currentUserKey = null;
      boolean hasCurrentUserKey = false;
      long lastSequenceForKey = MAX_SEQUENCE_NUMBER;

      while (iterator.hasNext() && !shuttingDown.get()) {
        InternalKey key = iterator.peek().getKey();
        if (compactionState.compaction.shouldStopBefore(key) && compactionState.builder != null) {
          finishCompactionOutputFile(compactionState);
        }

        boolean drop = false;
        if (!hasCurrentUserKey ||
            internalKeyComparator.getUserComparator().compare(key.getUserKey(), currentUserKey) != 0) {
          currentUserKey = key.getUserKey();
          hasCurrentUserKey = true;
          lastSequenceForKey = MAX_SEQUENCE_NUMBER;
        }

        if (lastSequenceForKey <= compactionState.smallestSnapshot) {
          drop = true;
        } else if (key.getValueType() == DELETION
            && key.getSequenceNumber() <= compactionState.smallestSnapshot
            && compactionState.compaction.isBaseLevelForKey(key.getUserKey())) {
          drop = true;
        }

        lastSequenceForKey = key.getSequenceNumber();

        if (!drop) {
          if (compactionState.builder == null) {
            openCompactionOutputFile(compactionState);
          }
          if (compactionState.builder.getEntryCount() == 0) {
            compactionState.currentSmallest = key;
          }
          compactionState.currentLargest = key;
          compactionState.builder.add(key.encode(), iterator.peek().getValue());

          if (compactionState.builder.getFileSize()
              >= compactionState.compaction.getMaxOutputFileSize()) {
            finishCompactionOutputFile(compactionState);
          }
        }
        iterator.next();
      }

      if (shuttingDown.get()) {
        throw new DatabaseShutdownException("DB shutdown during compaction");
      }
      if (compactionState.builder != null) {
        finishCompactionOutputFile(compactionState);
      }
    } finally {
      mutex.lock();
    }

    installCompactionResults(compactionState);
  }

  private void openCompactionOutputFile(CompactionState compactionState) throws FileNotFoundException {
    requireNonNull(compactionState, "compactionState is null");
    checkArgument(compactionState.builder == null, "compactionState builder is not null");

    mutex.lock();
    try {
      long fileNumber = versions.getNextFileNumber();
      pendingOutputs.add(fileNumber);
      compactionState.currentFileNumber = fileNumber;
      compactionState.currentFileSize = 0;
      compactionState.currentSmallest = null;
      compactionState.currentLargest = null;

      File file = new File(databaseDir, Filename.tableFileName(fileNumber));
      compactionState.outfile = new FileOutputStream(file).getChannel();
      compactionState.builder = new TableBuilder(
          options,
          compactionState.outfile,
          new InternalUserComparator(internalKeyComparator));
    } finally {
      mutex.unlock();
    }
  }

  private void finishCompactionOutputFile(CompactionState compactionState) throws IOException {
    requireNonNull(compactionState, "compactionState is null");
    checkArgument(compactionState.outfile != null);
    checkArgument(compactionState.builder != null);

    long outputNumber = compactionState.currentFileNumber;
    checkArgument(outputNumber != 0);

    long currentEntries = compactionState.builder.getEntryCount();
    compactionState.builder.finish();

    long currentBytes = compactionState.builder.getFileSize();
    compactionState.currentFileSize = currentBytes;
    compactionState.totalBytes += currentBytes;

    FileMetaData currentFileMetaData = new FileMetaData(
        compactionState.compaction.getCfId(),
        compactionState.currentFileNumber,
        compactionState.currentFileSize,
        compactionState.currentSmallest,
        compactionState.currentLargest);
    compactionState.outputs.add(currentFileMetaData);

    compactionState.builder = null;

    if(options.forceSstOnFlush()){
      compactionState.outfile.force(true);
    }
    compactionState.outfile.close();
    compactionState.outfile = null;

    if (currentEntries > 0) {
      tableCache.newIterator(outputNumber);
    }
  }

  private void installCompactionResults(CompactionState compact) throws IOException {
    checkState(mutex.isHeldByCurrentThread());

    compact.compaction.addInputDeletions(compact.compaction.getEdit());
    int level = compact.compaction.getLevel();
    int cfId = compact.compaction.getCfId();

    for (FileMetaData output : compact.outputs) {
      compact.compaction.getEdit().addFile(cfId, level + 1, output);
      pendingOutputs.remove(output.getNumber());
    }

    try {
      versions.logAndApply(compact.compaction.getEdit());
      deleteObsoleteFiles();
    } catch (IOException e) {
      for (FileMetaData output : compact.outputs) {
        File file = new File(databaseDir, Filename.tableFileName(output.getNumber()));
        file.delete();
      }
      compact.outputs.clear();
    }
  }

  @Override
  public long[] getApproximateSizes(Range... ranges) {
    requireNonNull(ranges, "ranges is null");
    long[] sizes = new long[ranges.length];
    for (int i = 0; i < ranges.length; i++) {
      sizes[i] = getApproximateSizes(ranges[i]);
    }
    return sizes;
  }

  public long getApproximateSizes(Range range) {
    long total = 0;
    Version v = versions.getCurrent();
    for (LdbColumnFamily cf : options.getColumnFamilies()) {
      InternalKey startKey = new InternalKey(Slices.wrappedBuffer(range.start()), MAX_SEQUENCE_NUMBER, VALUE);
      InternalKey limitKey = new InternalKey(Slices.wrappedBuffer(range.limit()), MAX_SEQUENCE_NUMBER, VALUE);
      long startOffset = v.getApproximateOffsetOf(cf.getId(), startKey);
      long limitOffset = v.getApproximateOffsetOf(cf.getId(), limitKey);
      total += (limitOffset >= startOffset ? limitOffset - startOffset : 0);
    }
    return total;
  }

  private static class CompactionState {
    private final Compaction compaction;
    private final List<FileMetaData> outputs = new ArrayList<>();

    private long smallestSnapshot;

    private FileChannel outfile;
    private TableBuilder builder;

    private long currentFileNumber;
    private long currentFileSize;
    private InternalKey currentSmallest;
    private InternalKey currentLargest;

    private long totalBytes;

    private CompactionState(Compaction compaction) {
      this.compaction = compaction;
    }

    public Compaction getCompaction() {
      return compaction;
    }
  }

  private static class ManualCompaction {
    private final int cfId;
    private final int level;
    private final Slice begin;
    private final Slice end;

    private ManualCompaction(int cfId, int level, Slice begin, Slice end) {
      this.cfId = cfId;
      this.level = level;
      this.begin = begin;
      this.end = end;
    }
  }

  private LdbWriteBatchImpl readWriteBatch(SliceInput record, int updateSize) throws IOException {
    LdbWriteBatchImpl writeBatch = new LdbWriteBatchImpl();
    int entries = 0;
    while (record.isReadable()) {
      entries++;
      ValueType valueType = ValueType.getValueTypeByPersistentId(record.readByte());
      int cfId = record.readInt();
      LdbColumnFamily cf = getColumnFamily(cfId);
      if (valueType == VALUE) {
        Slice key = readLengthPrefixedBytes(record);
        Slice value = readLengthPrefixedBytes(record);
        writeBatch.put(cf, key, value);
      } else if (valueType == DELETION) {
        Slice key = readLengthPrefixedBytes(record);
        writeBatch.delete(cf, key);
      } else if (valueType == ADD_LONG) {
        Slice key = readLengthPrefixedBytes(record);
        Slice deltaSlice = readLengthPrefixedBytes(record);
        writeBatch.addLong(cf, key, deltaSlice);
      } else {
        throw new IllegalStateException("Unexpected value type " + valueType);
      }
    }

    if (entries != updateSize) {
      throw new IOException(String.format(
          "Expected %d entries in log record but found %s entries", updateSize, entries));
    }

    return writeBatch;
  }

  public LdbColumnFamily getColumnFamily(int cfId) {
    ColumnFamilyState familyState = cfs.get(cfId);
    if (familyState == null) {
      throw new IllegalArgumentException("Unknown column family id in log: " + cfId);
    }
    return familyState.getColumnFamily();
  }

  @Override
  public void checkpoint(String targetDir) throws DBException {
    requireNonNull(targetDir, "targetDir is null");

    File target = new File(targetDir);
    boolean suspended = false;

    try {
      // 1. 先把所有 memtable 刷下去；这里不能先 suspendCompactions，
      //    否则 flushMemTable() 可能永远等不到 immutable memtable 被 compact 完。
      flushMemTable();

      // 2. 再暂停 compaction，冻结文件集合
      suspendCompactions();
      suspended = true;

      // 3. 在锁内收集需要纳入 checkpoint 的文件集合
      final List<File> filesToCopy;
      mutex.lock();
      try {
        checkBackgroundException();

        // checkpoint 目录必须不存在，或是空目录，避免混入旧文件
        prepareEmptyDirectory(target);

        filesToCopy = collectCheckpointFilesLocked();
      } finally {
        mutex.unlock();
      }

      // 4. 实际复制文件
      for (File src : filesToCopy) {
        File dst = new File(target, src.getName());
        copyForCheckpoint(src, dst);
      }

      // 5. 最后再 fsync 一下目录，降低宕机时目录项丢失风险
      forceDirectory(target);

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DBException("Interrupted while suspending compactions for checkpoint", e);
    } catch (IOException e) {
      throw new DBException("Failed to create checkpoint at " + targetDir, e);
    } finally {
      if (suspended) {
        resumeCompactions();
      }
    }
  }

  private List<File> collectCheckpointFilesLocked() throws IOException {
    checkState(mutex.isHeldByCurrentThread());

    List<File> result = new ArrayList<>();

    // 1. CURRENT
    File current = new File(databaseDir, Filename.currentFileName());
    if (current.exists()) {
      result.add(current);
    }

    // 2. 当前 MANIFEST
    File manifest = new File(databaseDir,
        Filename.descriptorFileName(versions.getManifestFileNumber()));
    if (manifest.exists()) {
      result.add(manifest);
    }

    // 3. 所有 live SST/TABLE 文件
    Set<Long> live = new HashSet<>();
    for (FileMetaData meta : versions.getLiveFiles()) {
      live.add(meta.getNumber());
    }

    for (Long fileNumber : live) {
      File table = new File(databaseDir, Filename.tableFileName(fileNumber));
      if (table.exists()) {
        result.add(table);
      }
    }

    // 4. 保守起见，把当前仍被引用的 WAL 也带上
    //    虽然 flushMemTable() 之后通常不再需要旧 WAL，但带上更稳妥。
    for (Long logNumber : getReferencedLogNumbers()) {
      File logFile = new File(databaseDir, Filename.logFileName(logNumber));
      if (logFile.exists()) {
        result.add(logFile);
      }
    }

    // 5. 可选：INFO_LOG 一并带过去，便于排查问题
    for (File f : Filename.listFiles(databaseDir)) {
      FileInfo info = Filename.parseFileName(f);
      if (info != null && info.getFileType() == FileType.INFO_LOG) {
        result.add(f);
      }
    }

    return result;
  }

  private void prepareEmptyDirectory(File dir) throws IOException {
    if (dir.exists()) {
      if (!dir.isDirectory()) {
        throw new IOException("Checkpoint target exists but is not a directory: " + dir);
      }
      File[] children = dir.listFiles();
      if (children != null && children.length > 0) {
        throw new IOException("Checkpoint target directory is not empty: " + dir);
      }
    } else {
      if (!dir.mkdirs()) {
        throw new IOException("Unable to create checkpoint directory: " + dir);
      }
    }
  }

  private void copyForCheckpoint(File src, File dst) throws IOException {
    // SST/TABLE 尽量硬链接，速度快且接近 RocksDB checkpoint 的效果
    String name = src.getName().toLowerCase(Locale.ROOT);
    boolean maybeTableFile =
        name.endsWith(".sst") || name.endsWith(".ldb") || name.endsWith(".table");

    if (maybeTableFile) {
      try {
        java.nio.file.Files.createLink(dst.toPath(), src.toPath());
        return;
      } catch (UnsupportedOperationException
               | IOException
               | SecurityException e) {
        // 硬链接失败就回退到普通复制
      }
    }
    LOG.info("Unable to hard link {} to {}" ,src, dst);
    java.nio.file.Files.copy(
        src.toPath(),
        dst.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
  }

  private void forceDirectory(File dir) {
    // 有些平台/文件系统不支持对目录 force，失败就忽略
    try (FileChannel ch = FileChannel.open(
        dir.toPath(),
        java.nio.file.StandardOpenOption.READ)) {
      ch.force(true);
    } catch (Exception ignored) {
    }
  }

  private Slice writeWriteBatch(LdbWriteBatchImpl updates, long sequenceBegin) {
    Slice record = Slices.allocate(SIZE_OF_LONG + SIZE_OF_INT + updates.getApproximateSize());
    final SliceOutput sliceOutput = record.output();
    sliceOutput.writeLong(sequenceBegin);
    sliceOutput.writeInt(updates.size());
    updates.forEach(new Handler() {
      @Override
      public void put(LdbColumnFamily cf, Slice key, Slice value) {
        sliceOutput.writeByte(VALUE.getPersistentId());
        sliceOutput.writeInt(cf.getId());
        writeLengthPrefixedBytes(sliceOutput, key);
        writeLengthPrefixedBytes(sliceOutput, value);
      }

      @Override
      public void delete(LdbColumnFamily cf, Slice key) {
        sliceOutput.writeByte(DELETION.getPersistentId());
        sliceOutput.writeInt(cf.getId());
        writeLengthPrefixedBytes(sliceOutput, key);
      }

      @Override
      public void addLong(LdbColumnFamily cf, Slice key, Slice deltaSlice) {
        sliceOutput.writeByte(ADD_LONG.getPersistentId());
        sliceOutput.writeInt(cf.getId());
        writeLengthPrefixedBytes(sliceOutput, key);
        writeLengthPrefixedBytes(sliceOutput, deltaSlice);
      }
    });
    return record.slice(0, sliceOutput.size());
  }

  private static class RecoverIntoHandler implements Handler {
    private final Map<Integer, MemTable> recoveringTables;
    private final Map<BatchKey, Long> localCache = new HashMap<>();
    private final VersionSet versions;
    private long sequence;

    private RecoverIntoHandler(Map<Integer, MemTable> recoveringTables, VersionSet versions, long sequenceBegin) {
      this.recoveringTables = recoveringTables;
      this.versions = versions;
      this.sequence = sequenceBegin;
    }

    @Override
    public void put(LdbColumnFamily cf, Slice key, Slice value) {
      recoveringTables.get(cf.getId()).add(sequence++, ValueType.VALUE, key, value);
    }

    @Override
    public void delete(LdbColumnFamily cf, Slice key) {
      recoveringTables.get(cf.getId()).add(sequence++, ValueType.DELETION, key, Slices.EMPTY_SLICE);
      localCache.remove(new BatchKey(cf.getId(), key));
    }

    @Override
    public void addLong(LdbColumnFamily cf, Slice key, Slice deltaSlice) {
      BatchKey cacheKey = new BatchKey(cf.getId(), key);

      Long current = localCache.get(cacheKey);
      if (current == null) {
        current = getCurrentLongValue(cf, key);
      }

      long delta = Slices.decodeLong(deltaSlice)
          .orElseThrow(() -> new IllegalArgumentException("deltaSlice is not a long"));

      long newValue = current + delta;

      localCache.put(cacheKey, newValue);

      recoveringTables.get(cf.getId()).add(sequence++, VALUE, key, Slices.encodeLong(newValue));
    }

    private long getCurrentLongValue(LdbColumnFamily cf, Slice key) {
      LookupKey lookupKey = new LookupKey(key, MAX_SEQUENCE_NUMBER);

      // 1. recovering memtable / current memtable
      LookupResult lr = recoveringTables.get(cf.getId()).get(lookupKey);
      if (lr != null) {
        return lr.isDeleted() ? 0L : Slices.decodeLong(lr.getValue()).orElse(0L);
      }

      // 2. SST / VersionSet
      LookupResult fromVersion = versions.get(cf.getId(), lookupKey);
      if (fromVersion != null) {
        return fromVersion.isDeleted() ? 0L : Slices.decodeLong(fromVersion.getValue()).orElse(0L);
      }

      return 0L;
    }

  }

  private static class InsertIntoHandler implements Handler {
    private long sequence;
    private final Map<Integer, ColumnFamilyState> memTables;
    private final VersionSet versions;
    private final Map<BatchKey, Long> localCache = new HashMap<>();
    public InsertIntoHandler(Map<Integer, ColumnFamilyState> memTables, long sequenceBegin, VersionSet versions) {
      this.memTables = memTables;
      this.sequence = sequenceBegin;
      this.versions = versions;
    }

    @Override
    public void put(LdbColumnFamily cf, Slice key, Slice value) {
      getCFState(cf).getMemTable().add(sequence++, VALUE, key, value);
    }

    @Override
    public void delete(LdbColumnFamily cf, Slice key) {
      getCFState(cf).getMemTable().add(sequence++, DELETION, key, Slices.EMPTY_SLICE);
      localCache.remove(new BatchKey(cf.getId(), key));
    }

    @Override
    public void addLong(LdbColumnFamily cf, Slice key, Slice deltaSlice) {
      BatchKey cacheKey = new BatchKey(cf.getId(), key);

      Long current = localCache.get(cacheKey);
      if (current == null) {
        current = getCurrentLongValue(cf, key);
      }

      long delta = Slices.decodeLong(deltaSlice)
          .orElseThrow(() -> new IllegalArgumentException("deltaSlice is not a long"));

      long newValue = current + delta;

      localCache.put(cacheKey, newValue);

      getCFState(cf).getMemTable().add(sequence++, VALUE, key, Slices.encodeLong(newValue));
    }

    private long getCurrentLongValue(LdbColumnFamily cf, Slice key) {
      ColumnFamilyState cfState = getCFState(cf);
      LookupKey lookupKey = new LookupKey(key, MAX_SEQUENCE_NUMBER);

      LookupResult lr = cfState.getMemTable().get(lookupKey);
      if (lr != null) {
        return lr.isDeleted() ? 0L : Slices.decodeLong(lr.getValue()).orElse(0L);
      }

      MemTable imm = cfState.getImmutableMemTable();
      if (imm != null) {
        lr = imm.get(lookupKey);
        if (lr != null) {
          return lr.isDeleted() ? 0L : Slices.decodeLong(lr.getValue()).orElse(0L);
        }
      }

      LookupResult fromVersion = versions.get(cf.getId(), lookupKey);
      if (fromVersion != null) {
        return fromVersion.isDeleted() ? 0L : Slices.decodeLong(fromVersion.getValue()).orElse(0L);
      }

      return 0L;
    }

    private ColumnFamilyState getCFState(LdbColumnFamily cf) {
      ColumnFamilyState familyState = memTables.get(cf.getId());
      if (familyState == null) {
        throw new IllegalArgumentException("Unknown column family id: " + cf.getId());
      }
      return familyState;
    }
  }

  private static final class BatchKey {
    private final int cfId;
    private final byte[] key;
    private final int hash;

    BatchKey(int cfId, Slice key) {
      this.cfId = cfId;
      this.key = key.getBytes();
      this.hash = 31 * cfId + java.util.Arrays.hashCode(this.key);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof BatchKey)) return false;
      BatchKey other = (BatchKey) o;
      return cfId == other.cfId &&
          java.util.Arrays.equals(key, other.key);
    }

    @Override
    public int hashCode() {
      return hash;
    }
  }

  public static class DatabaseShutdownException extends DBException {
    public DatabaseShutdownException() {
    }

    public DatabaseShutdownException(String message) {
      super(message);
    }
  }

  public static class BackgroundProcessingException extends DBException {
    public BackgroundProcessingException(Throwable cause) {
      super(cause);
    }
  }

  private final Object suspensionMutex = new Object();
  private int suspensionCounter;

  @Override
  public void suspendCompactions() throws InterruptedException {
    compactionExecutor.execute(new Runnable() {
      @Override
      public void run() {
        try {
          synchronized (suspensionMutex) {
            suspensionCounter++;
            suspensionMutex.notifyAll();
            while (suspensionCounter > 0 && !compactionExecutor.isShutdown()) {
              suspensionMutex.wait(500);
            }
          }
        } catch (InterruptedException ignored) {
        }
      }
    });
    synchronized (suspensionMutex) {
      while (suspensionCounter < 1) {
        suspensionMutex.wait();
      }
    }
  }

  @Override
  public void resumeCompactions() {
    synchronized (suspensionMutex) {
      suspensionCounter--;
      suspensionMutex.notifyAll();
    }
  }

  @Override
  public void compactRange(byte[] begin, byte[] end) throws DBException {
    throw new UnsupportedOperationException("Not yet implemented");
  }
}