package net.xdob.vexra.ldb;

import java.util.ArrayList;
import java.util.List;

public class Options {
  private boolean createIfMissing = true;
  private boolean errorIfExists;
  private int writeBufferSize = 64 << 20;
  private boolean forceLogOnClose = false;
  private boolean forceSstOnFlush = false;

  private int maxOpenFiles = 1000;

  private int blockRestartInterval = 16;
  private int blockSize = 4 * 1024;
  private CompressionType compressionType = CompressionType.NONE;
  private boolean verifyChecksums = true;
  private boolean paranoidChecks;
  private DBComparator comparator;
  private long cacheSize;
  private final List<LdbColumnFamily> columnFamilies = new ArrayList<>();
  private FilterPolicy filterPolicy;
  private boolean readOnly;
  private boolean cacheBlocks = true;
  private int blockCacheSize = 4096;

  public boolean cacheBlocks() {
    return cacheBlocks;
  }

  public Options cacheBlocks(boolean cacheBlocks) {
    this.cacheBlocks = cacheBlocks;
    return this;
  }

  public int blockCacheSize() {
    return blockCacheSize;
  }

  public Options blockCacheSize(int blockCacheSize) {
    if (blockCacheSize <= 0) {
      throw new IllegalArgumentException("blockCacheSize must be > 0");
    }
    this.blockCacheSize = blockCacheSize;
    return this;
  }


  public boolean readOnly() {
    return readOnly;
  }

  public Options readOnly(boolean readOnly) {
    this.readOnly = readOnly;
    return this;
  }

  public FilterPolicy filterPolicy() {
    return filterPolicy;
  }

  public Options filterPolicy(FilterPolicy filterPolicy) {
    this.filterPolicy = filterPolicy;
    return this;
  }


  public List<LdbColumnFamily> getColumnFamilies() {
    addDefault();
    return columnFamilies;
  }

  private void addDefault() {
    synchronized (columnFamilies) {
      if (columnFamilies.stream().noneMatch(cf -> cf.getId() == LdbColumnFamily.DEFAULT.getId())) {
        columnFamilies.add(LdbColumnFamily.DEFAULT);
      }
    }
  }

  public  Options addColumnFamily(LdbColumnFamily columnFamily) {
    checkArgNotNull(columnFamily, "columnFamily");
    synchronized (columnFamilies) {
      if(columnFamilies.stream().anyMatch(cf -> cf.getId() == columnFamily.getId())){
        throw new IllegalArgumentException("Column family with id " + columnFamily.getId() + " already exists");
      }
      columnFamilies.add(columnFamily);
    }
    return this;
  }

  public boolean createIfMissing() {
    return createIfMissing;
  }

  public Options createIfMissing(boolean createIfMissing) {
    this.createIfMissing = createIfMissing;
    return this;
  }

  public boolean errorIfExists() {
    return errorIfExists;
  }

  public Options errorIfExists(boolean errorIfExists) {
    this.errorIfExists = errorIfExists;
    return this;
  }

  public int writeBufferSize() {
    return writeBufferSize;
  }

  public Options writeBufferSize(int writeBufferSize) {
    this.writeBufferSize = writeBufferSize;
    return this;
  }

  public boolean forceLogOnClose() {
    return forceLogOnClose;
  }

  public Options forceLogOnClose(boolean forceLogOnClose) {
    this.forceLogOnClose = forceLogOnClose;
    return this;
  }

  public boolean forceSstOnFlush() {
    return forceSstOnFlush;
  }

  public Options forceSstOnFlush(boolean forceSstOnFlush) {
    this.forceSstOnFlush = forceSstOnFlush;
    return this;
  }

  public int maxOpenFiles() {
    return maxOpenFiles;
  }

  public Options maxOpenFiles(int maxOpenFiles) {
    this.maxOpenFiles = maxOpenFiles;
    return this;
  }

  public int blockRestartInterval() {
    return blockRestartInterval;
  }

  public Options blockRestartInterval(int blockRestartInterval) {
    this.blockRestartInterval = blockRestartInterval;
    return this;
  }

  public int blockSize() {
    return blockSize;
  }

  public Options blockSize(int blockSize) {
    this.blockSize = blockSize;
    return this;
  }

  public CompressionType compressionType() {
    return compressionType;
  }

  public Options compressionType(CompressionType compressionType) {
    checkArgNotNull(compressionType, "compressionType");
    this.compressionType = compressionType;
    return this;
  }

  public boolean verifyChecksums() {
    return verifyChecksums;
  }

  public Options verifyChecksums(boolean verifyChecksums) {
    this.verifyChecksums = verifyChecksums;
    return this;
  }

  public long cacheSize() {
    return cacheSize;
  }

  public Options cacheSize(long cacheSize) {
    this.cacheSize = cacheSize;
    return this;
  }

  public DBComparator comparator() {
    return comparator;
  }

  public Options comparator(DBComparator comparator) {
    this.comparator = comparator;
    return this;
  }


  public boolean paranoidChecks() {
    return paranoidChecks;
  }

  public Options paranoidChecks(boolean paranoidChecks) {
    this.paranoidChecks = paranoidChecks;
    return this;
  }


  static void checkArgNotNull(Object value, String name) {
    if (value == null) {
      throw new IllegalArgumentException("The " + name + " argument cannot be null");
    }
  }
}
