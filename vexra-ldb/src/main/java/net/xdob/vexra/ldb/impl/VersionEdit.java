package net.xdob.vexra.ldb.impl;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import net.xdob.vexra.ldb.util.DynamicSliceOutput;
import net.xdob.vexra.ldb.util.Slice;
import net.xdob.vexra.ldb.util.SliceInput;
import net.xdob.vexra.ldb.util.VariableLengthQuantity;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public class VersionEdit {
  private String comparatorName;
  private Long logNumber;
  private Long nextFileNumber;
  private Long previousLogNumber;
  private Long lastSequenceNumber;

  /**
   * key = (cfId, level)
   */
  private final Map<CfLevel, InternalKey> compactPointers = new TreeMap<>();

  /**
   * key = (cfId, level)
   */
  private final Multimap<CfLevel, FileMetaData> newFiles = ArrayListMultimap.create();

  /**
   * key = (cfId, level)
   */
  private final Multimap<CfLevel, Long> deletedFiles = ArrayListMultimap.create();

  public VersionEdit() {
  }

  public VersionEdit(Slice slice) {
    SliceInput sliceInput = slice.input();
    while (sliceInput.isReadable()) {
      int i = VariableLengthQuantity.readVariableLengthInt(sliceInput);
      VersionEditTag tag = VersionEditTag.getValueTypeByPersistentId(i);
      tag.readValue(sliceInput, this);
    }
  }

  public String getComparatorName() {
    return comparatorName;
  }

  public void setComparatorName(String comparatorName) {
    this.comparatorName = comparatorName;
  }

  public Long getLogNumber() {
    return logNumber;
  }

  public void setLogNumber(long logNumber) {
    this.logNumber = logNumber;
  }

  public Long getNextFileNumber() {
    return nextFileNumber;
  }

  public void setNextFileNumber(long nextFileNumber) {
    this.nextFileNumber = nextFileNumber;
  }

  public Long getPreviousLogNumber() {
    return previousLogNumber;
  }

  public void setPreviousLogNumber(long previousLogNumber) {
    this.previousLogNumber = previousLogNumber;
  }

  public Long getLastSequenceNumber() {
    return lastSequenceNumber;
  }

  public void setLastSequenceNumber(long lastSequenceNumber) {
    this.lastSequenceNumber = lastSequenceNumber;
  }

  public Map<CfLevel, InternalKey> getCompactPointers() {
    return ImmutableMap.copyOf(compactPointers);
  }

  public void setCompactPointer(int cfId, int level, InternalKey key) {
    compactPointers.put(new CfLevel(cfId, level), key);
  }

  public void setCompactPointers(Map<CfLevel, InternalKey> compactPointers) {
    this.compactPointers.putAll(compactPointers);
  }

  public Multimap<CfLevel, FileMetaData> getNewFiles() {
    return ImmutableMultimap.copyOf(newFiles);
  }

  public void addFile(int cfId, int level,
                      long fileNumber,
                      long fileSize,
                      InternalKey smallest,
                      InternalKey largest) {
    FileMetaData fileMetaData = new FileMetaData(cfId, fileNumber, fileSize, smallest, largest);
    addFile(cfId, level, fileMetaData);
  }

  public void addFile(int cfId, int level, FileMetaData fileMetaData) {
    newFiles.put(new CfLevel(cfId, level), fileMetaData);
  }

  public void addFiles(Multimap<CfLevel, FileMetaData> files) {
    newFiles.putAll(files);
  }

  public Multimap<CfLevel, Long> getDeletedFiles() {
    return ImmutableMultimap.copyOf(deletedFiles);
  }

  public void deleteFile(int cfId, int level, long fileNumber) {
    deletedFiles.put(new CfLevel(cfId, level), fileNumber);
  }

  public Slice encode() {
    DynamicSliceOutput dynamicSliceOutput = new DynamicSliceOutput(4096);
    for (VersionEditTag versionEditTag : VersionEditTag.values()) {
      versionEditTag.writeValue(dynamicSliceOutput, this);
    }
    return dynamicSliceOutput.slice();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("VersionEdit");
    sb.append("{comparatorName='").append(comparatorName).append('\'');
    sb.append(", logNumber=").append(logNumber);
    sb.append(", nextFileNumber=").append(nextFileNumber);
    sb.append(", previousLogNumber=").append(previousLogNumber);
    sb.append(", lastSequenceNumber=").append(lastSequenceNumber);
    sb.append(", compactPointers=").append(compactPointers);
    sb.append(", newFiles=").append(newFiles);
    sb.append(", deletedFiles=").append(deletedFiles);
    sb.append('}');
    return sb.toString();
  }

  public static final class CfLevel implements Comparable<CfLevel> {
    private final int cfId;
    private final int level;

    public CfLevel(int cfId, int level) {
      this.cfId = cfId;
      this.level = level;
    }

    public int getCfId() {
      return cfId;
    }

    public int getLevel() {
      return level;
    }

    @Override
    public int compareTo(CfLevel o) {
      int c = Integer.compare(this.cfId, o.cfId);
      if (c != 0) {
        return c;
      }
      return Integer.compare(this.level, o.level);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof CfLevel)) {
        return false;
      }
      CfLevel cfLevel = (CfLevel) o;
      return cfId == cfLevel.cfId && level == cfLevel.level;
    }

    @Override
    public int hashCode() {
      return Objects.hash(cfId, level);
    }

    @Override
    public String toString() {
      return "CfLevel{" +
          "cfId=" + cfId +
          ", level=" + level +
          '}';
    }
  }
}