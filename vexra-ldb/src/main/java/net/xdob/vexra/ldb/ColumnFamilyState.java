package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.impl.*;

import java.io.File;
import java.io.IOException;


import static java.util.Objects.requireNonNull;

public final class ColumnFamilyState {
  private final LdbColumnFamily columnFamily;
  private final File dir;

  private MemTable memTable;
  private MemTable immutableMemTable;

  private long immutableLogNumber;

  public ColumnFamilyState(
      LdbColumnFamily columnFamily,
      File databaseDir,
      Options options,
      InternalKeyComparator internalKeyComparator) throws IOException {

    this.columnFamily = requireNonNull(columnFamily, "columnFamily is null");
    requireNonNull(databaseDir, "databaseDir is null");
    requireNonNull(options, "options is null");
    requireNonNull(internalKeyComparator, "internalKeyComparator is null");

    this.dir = resolveCfDir(databaseDir, columnFamily);
    if (!dir.exists() && !dir.mkdirs()) {
      throw new IOException("Unable to create database directory: " + dir);
    }
    if (!dir.isDirectory()) {
      throw new IOException("Column family path is not a directory: " + dir);
    }


    this.memTable = new MemTable(internalKeyComparator);
    this.immutableMemTable = null;
  }

  public long getImmutableLogNumber() {
    return immutableLogNumber;
  }

  public void setImmutableLogNumber(long immutableLogNumber) {
    this.immutableLogNumber = immutableLogNumber;
  }

  public LdbColumnFamily getColumnFamily() {
    return columnFamily;
  }

  public File getDir() {
    return dir;
  }


  public MemTable getMemTable() {
    return memTable;
  }

  public void setMemTable(MemTable memTable) {
    this.memTable = memTable;
  }

  public MemTable getImmutableMemTable() {
    return immutableMemTable;
  }

  public void setImmutableMemTable(MemTable immutableMemTable) {
    this.immutableMemTable = immutableMemTable;
  }

  public void close() {

  }

  public static File resolveCfDir(File databaseDir, LdbColumnFamily cf) {
    return databaseDir;
  }
}