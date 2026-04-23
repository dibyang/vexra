package net.xdob.vexra.ldb;

import java.io.Closeable;


public interface LDB
    extends  Closeable {
  byte[] get(byte[] key)
      throws DBException;

  byte[] get(byte[] key, ReadOptions options)
      throws DBException;
  byte[] get(LdbColumnFamily cf, byte[] key);
  byte[] get(LdbColumnFamily cf, byte[] key, ReadOptions options);

  SnapshotCursor newSnapshotCursor();

  SnapshotCursor newSnapshotCursor(LdbColumnFamily cf);

  int numberOfFilesInLevel(int level);
  int numberOfFilesInLevel(LdbColumnFamily cf, int level);

  void put(byte[] key, byte[] value)
      throws DBException;

  void delete(byte[] key)
      throws DBException;

  long addLong(byte[] key, long delta)
      throws DBException;

  void put(LdbColumnFamily cf, byte[] key, byte[] value)
      throws DBException;

  void delete(LdbColumnFamily cf, byte[] key)
      throws DBException;

  long addLong(LdbColumnFamily cf, byte[] key, long delta)
      throws DBException;


  void write(LdbWriteBatch updates)
      throws DBException;

  LdbWriteBatch createWriteBatch();

  /**
   * @return null if options.isSnapshot()==false otherwise returns a snapshot
   * of the DB after this operation.
   */
  Snapshot put(byte[] key, byte[] value, WriteOptions options)
      throws DBException;

  /**
   * @return null if options.isSnapshot()==false otherwise returns a snapshot
   * of the DB after this operation.
   */
  Snapshot delete(byte[] key, WriteOptions options)
      throws DBException;

  Snapshot put(LdbColumnFamily cf, byte[] key, byte[] value, WriteOptions options)
      throws DBException;

  Snapshot delete(LdbColumnFamily cf, byte[] key, WriteOptions options)
      throws DBException;

  Snapshot addLong(LdbColumnFamily cf, byte[] key, long delta, WriteOptions options)
      throws DBException;

  /**
   * @return null if options.isSnapshot()==false otherwise returns a snapshot
   * of the DB after this operation.
   */
  Snapshot write(LdbWriteBatch updates, WriteOptions options)
      throws DBException;



  Snapshot getSnapshot();
  Snapshot getSnapshot(LdbColumnFamily cf);

  long[] getApproximateSizes(Range... ranges);

  String getProperty(String name);

  /**
   * Suspends any background compaction threads.  This methods
   * returns once the background compactions are suspended.
   */
  void suspendCompactions()
      throws InterruptedException;

  /**
   * Resumes the background compaction threads.
   */
  void resumeCompactions();

  /**
   * Force a compaction of the specified key range.
   *
   * @param begin if null then compaction start from the first key
   * @param end   if null then compaction ends at the last key
   */
  void compactRange(byte[] begin, byte[] end)
      throws DBException;

  LdbColumnFamily getColumnFamily(int cfId);

  void checkpoint(String targetDir) throws DBException;

}
