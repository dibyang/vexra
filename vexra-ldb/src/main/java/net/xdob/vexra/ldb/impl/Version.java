package net.xdob.vexra.ldb.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import net.xdob.vexra.ldb.util.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;
import static net.xdob.vexra.ldb.impl.DbConstants.MAX_MEM_COMPACT_LEVEL;
import static net.xdob.vexra.ldb.impl.DbConstants.NUM_LEVELS;
import static net.xdob.vexra.ldb.impl.SequenceNumber.MAX_SEQUENCE_NUMBER;
import static net.xdob.vexra.ldb.impl.VersionSet.MAX_GRAND_PARENT_OVERLAP_BYTES;

// todo this class should be immutable
public class Version implements SeekingIterable<InternalKey, Slice> {
  private final AtomicInteger retained = new AtomicInteger(1);
  private final VersionSet versionSet;

  /**
   * 每个 CF 一套 level 视图
   */
  private final Map<Integer, CfVersionLevels> cfVersions = new TreeMap<>();

  // move these mutable fields somewhere else
  private int compactionLevel;
  private double compactionScore;
  private FileMetaData fileToCompact;
  private int fileToCompactLevel;
  private int fileToCompactCfId = -1;

  public Version(VersionSet versionSet) {
    this.versionSet = versionSet;
    checkArgument(NUM_LEVELS > 1, "levels must be at least 2");
  }

  private TableCache getTableCache() {
    return versionSet.getTableCache();
  }

  public final InternalKeyComparator getInternalKeyComparator() {
    return versionSet.getInternalKeyComparator();
  }

  public Set<Integer> getColumnFamilyIds() {
    return Collections.unmodifiableSet(cfVersions.keySet());
  }

  private CfVersionLevels getOrCreateCfLevels(int cfId) {
    CfVersionLevels levels = cfVersions.get(cfId);
    if (levels == null) {
      levels = new CfVersionLevels(cfId, getTableCache(), getInternalKeyComparator());
      cfVersions.put(cfId, levels);
    }
    return levels;
  }

  private CfVersionLevels getCfLevels(int cfId) {
    CfVersionLevels levels = cfVersions.get(cfId);
    if (levels == null) {
      throw new IllegalArgumentException("Unknown column family id: " + cfId);
    }
    return levels;
  }

  private CfVersionLevels findCfLevels(int cfId) {
    return cfVersions.get(cfId);
  }

  public void assertNoOverlappingFiles() {
    for (CfVersionLevels cfLevels : cfVersions.values()) {
      cfLevels.assertNoOverlappingFiles();
    }
  }

  public synchronized int getCompactionLevel() {
    return compactionLevel;
  }

  public synchronized void setCompactionLevel(int compactionLevel) {
    this.compactionLevel = compactionLevel;
  }

  public synchronized double getCompactionScore() {
    return compactionScore;
  }

  public synchronized void setCompactionScore(double compactionScore) {
    this.compactionScore = compactionScore;
  }

  @Override
  public MergingIterator iterator() {
    ImmutableList.Builder<InternalIterator> builder = ImmutableList.builder();
    for (CfVersionLevels cfLevels : cfVersions.values()) {
      builder.add(cfLevels.level0.iterator());
      builder.addAll(cfLevels.getLevelIterators());
    }
    return new MergingIterator(builder.build(), getInternalKeyComparator());
  }

  public MergingIterator iterator(int cfId) {
    CfVersionLevels cfLevels = getCfLevels(cfId);
    ImmutableList.Builder<InternalIterator> builder = ImmutableList.builder();
    builder.add(cfLevels.level0.iterator());
    builder.addAll(cfLevels.getLevelIterators());
    return new MergingIterator(builder.build(), getInternalKeyComparator());
  }

  List<InternalTableIterator> getLevel0Files(int cfId) {
    CfVersionLevels cfLevels = findCfLevels(cfId);
    if (cfLevels == null) {
      return Collections.emptyList();
    }
    return cfLevels.getLevel0Files();
  }

  List<LevelIterator> getLevelIterators(int cfId) {
    CfVersionLevels cfLevels = findCfLevels(cfId);
    if (cfLevels == null) {
      return Collections.emptyList();
    }
    return cfLevels.getLevelIterators();
  }

  public LookupResult get(int cfId, LookupKey key) {
    CfVersionLevels cfLevels = findCfLevels(cfId);
    if (cfLevels == null) {
      return null;
    }

    ReadStats readStats = new ReadStats();
    LookupResult lookupResult = cfLevels.level0.get(key, readStats);
    if (lookupResult == null) {
      for (Level level : cfLevels.levels) {
        lookupResult = level.get(key, readStats);
        if (lookupResult != null) {
          break;
        }
      }
    }
    updateStats(cfId, readStats.getSeekFileLevel(), readStats.getSeekFile());
    return lookupResult;
  }

  int pickLevelForMemTableOutput(int cfId, Slice smallestUserKey, Slice largestUserKey) {
    int level = 0;
    if (!overlapInLevel(cfId, 0, smallestUserKey, largestUserKey)) {
      InternalKey start = new InternalKey(smallestUserKey, MAX_SEQUENCE_NUMBER, ValueType.VALUE);
      InternalKey limit = new InternalKey(largestUserKey, 0, ValueType.VALUE);
      while (level < MAX_MEM_COMPACT_LEVEL) {
        if (overlapInLevel(cfId, level + 1, smallestUserKey, largestUserKey)) {
          break;
        }
        long sum = Compaction.totalFileSize(
            versionSet.getOverlappingInputs(cfId, level + 2, start, limit));
        if (sum > MAX_GRAND_PARENT_OVERLAP_BYTES) {
          break;
        }
        level++;
      }
    }
    return level;
  }

  public boolean overlapInLevel(int cfId, int level, Slice smallestUserKey, Slice largestUserKey) {
    requireNonNull(smallestUserKey, "smallestUserKey is null");
    requireNonNull(largestUserKey, "largestUserKey is null");
    checkArgument(level >= 0 && level < NUM_LEVELS, "Invalid level %s", level);

    CfVersionLevels cfLevels = findCfLevels(cfId);
    if (cfLevels == null) {
      return false;
    }

    if (level == 0) {
      return cfLevels.level0.someFileOverlapsRange(smallestUserKey, largestUserKey);
    }
    return cfLevels.levels.get(level - 1).someFileOverlapsRange(smallestUserKey, largestUserKey);
  }

  public int numberOfLevels() {
    return NUM_LEVELS;
  }

  public int numberOfFilesInLevel(int cfId, int level) {
    CfVersionLevels cfLevels = findCfLevels(cfId);
    if (cfLevels == null) {
      return 0;
    }
    if (level == 0) {
      return cfLevels.level0.getFiles().size();
    } else {
      return cfLevels.levels.get(level - 1).getFiles().size();
    }
  }

  public Multimap<VersionEdit.CfLevel, FileMetaData> getFiles() {
    ImmutableMultimap.Builder<VersionEdit.CfLevel, FileMetaData> builder = ImmutableMultimap.builder();
    for (Map.Entry<Integer, CfVersionLevels> cfEntry : cfVersions.entrySet()) {
      int cfId = cfEntry.getKey();
      CfVersionLevels cfLevels = cfEntry.getValue();

      builder.putAll(new VersionEdit.CfLevel(cfId, 0), cfLevels.level0.getFiles());
      for (Level level : cfLevels.levels) {
        builder.putAll(new VersionEdit.CfLevel(cfId, level.getLevelNumber()), level.getFiles());
      }
    }
    return builder.build();
  }

  public List<FileMetaData> getFiles(int cfId, int level) {
    CfVersionLevels cfLevels = findCfLevels(cfId);
    if (cfLevels == null) {
      return Collections.emptyList();
    }
    if (level == 0) {
      return cfLevels.level0.getFiles();
    } else {
      return cfLevels.levels.get(level - 1).getFiles();
    }
  }

  public void addFile(int cfId, int level, FileMetaData fileMetaData) {
    CfVersionLevels cfLevels = getOrCreateCfLevels(cfId);
    if (level == 0) {
      cfLevels.level0.addFile(fileMetaData);
    } else {
      cfLevels.levels.get(level - 1).addFile(fileMetaData);
    }
  }

  private boolean updateStats(int cfId, int seekFileLevel, FileMetaData seekFile) {
    if (seekFile == null) {
      return false;
    }

    seekFile.decrementAllowedSeeks();
    if (seekFile.getAllowedSeeks() <= 0 && fileToCompact == null) {
      fileToCompact = seekFile;
      fileToCompactLevel = seekFileLevel;
      fileToCompactCfId = cfId;
      return true;
    }
    return false;
  }

  public FileMetaData getFileToCompact() {
    return fileToCompact;
  }

  public int getFileToCompactLevel() {
    return fileToCompactLevel;
  }

  public int getFileToCompactCfId() {
    return fileToCompactCfId;
  }

  public long getApproximateOffsetOf(int cfId, InternalKey key) {
    long result = 0;
    for (int level = 0; level < NUM_LEVELS; level++) {
      for (FileMetaData fileMetaData : getFiles(cfId, level)) {
        if (getInternalKeyComparator().compare(fileMetaData.getLargest(), key) <= 0) {
          result += fileMetaData.getFileSize();
        } else if (getInternalKeyComparator().compare(fileMetaData.getSmallest(), key) > 0) {
          if (level > 0) {
            break;
          }
        } else {
          result += getTableCache().getApproximateOffsetOf(fileMetaData, key.encode());
        }
      }
    }
    return result;
  }

  public void retain() {
    int was = retained.getAndIncrement();
    assert was > 0 : "Version was retain after it was disposed.";
  }

  public void release() {
    int now = retained.decrementAndGet();
    assert now >= 0 : "Version was released after it was disposed.";
    if (now == 0) {
      versionSet.removeVersion(this);
    }
  }

  public boolean isDisposed() {
    return retained.get() <= 0;
  }

  /**
   * 每个 CF 一套 level 结构
   */
  private static final class CfVersionLevels {
    private final int cfId;
    private final Level0 level0;
    private final List<Level> levels;

    private CfVersionLevels(int cfId,
                            TableCache tableCache,
                            InternalKeyComparator comparator) {
      this.cfId = cfId;
      this.level0 = new Level0(new ArrayList<FileMetaData>(), tableCache, comparator);

      ImmutableList.Builder<Level> builder = ImmutableList.builder();
      for (int i = 1; i < NUM_LEVELS; i++) {
        builder.add(new Level(i, new ArrayList<FileMetaData>(), tableCache, comparator));
      }
      this.levels = builder.build();
    }

    private List<InternalTableIterator> getLevel0Files() {
      ImmutableList.Builder<InternalTableIterator> builder = ImmutableList.builder();
      for (FileMetaData file : level0.getFiles()) {
        builder.add(level0.getTableCache().newIterator(file));
      }
      return builder.build();
    }

    private List<LevelIterator> getLevelIterators() {
      ImmutableList.Builder<LevelIterator> builder = ImmutableList.builder();
      for (Level level : levels) {
        if (!level.getFiles().isEmpty()) {
          builder.add(level.iterator());
        }
      }
      return builder.build();
    }

    private void assertNoOverlappingFiles() {
      for (int level = 1; level < NUM_LEVELS; level++) {
        assertNoOverlappingFiles(level);
      }
    }

    private void assertNoOverlappingFiles(int level) {
      if (level <= 0) {
        return;
      }
      List<FileMetaData> files = getFiles(level);
      long previousFileNumber = 0;
      InternalKey previousEnd = null;
      for (FileMetaData fileMetaData : files) {
        if (previousEnd != null) {
          checkArgument(
              level0.getInternalKeyComparator().compare(previousEnd, fileMetaData.getSmallest()) < 0,
              "Overlapping files %s and %s in cf %s level %s",
              previousFileNumber,
              fileMetaData.getNumber(),
              cfId,
              level
          );
        }
        previousFileNumber = fileMetaData.getNumber();
        previousEnd = fileMetaData.getLargest();
      }
    }

    private List<FileMetaData> getFiles(int level) {
      if (level == 0) {
        return level0.getFiles();
      }
      return levels.get(level - 1).getFiles();
    }
  }
}