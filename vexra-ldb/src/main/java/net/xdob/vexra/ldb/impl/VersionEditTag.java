package net.xdob.vexra.ldb.impl;

import net.xdob.vexra.ldb.util.SliceInput;
import net.xdob.vexra.ldb.util.SliceOutput;
import net.xdob.vexra.ldb.util.VariableLengthQuantity;

import java.util.Map.Entry;

import static java.nio.charset.StandardCharsets.UTF_8;
import static net.xdob.vexra.ldb.util.Slices.readLengthPrefixedBytes;
import static net.xdob.vexra.ldb.util.Slices.writeLengthPrefixedBytes;

public enum VersionEditTag {
  // 8 is no longer used. It was used for large value refs.

  COMPARATOR(1) {
    @Override
    public void readValue(SliceInput sliceInput, VersionEdit versionEdit) {
      byte[] bytes = new byte[VariableLengthQuantity.readVariableLengthInt(sliceInput)];
      sliceInput.readBytes(bytes);
      versionEdit.setComparatorName(new String(bytes, UTF_8));
    }

    @Override
    public void writeValue(SliceOutput sliceOutput, VersionEdit versionEdit) {
      String comparatorName = versionEdit.getComparatorName();
      if (comparatorName != null) {
        VariableLengthQuantity.writeVariableLengthInt(getPersistentId(), sliceOutput);
        byte[] bytes = comparatorName.getBytes(UTF_8);
        VariableLengthQuantity.writeVariableLengthInt(bytes.length, sliceOutput);
        sliceOutput.writeBytes(bytes);
      }
    }
  },

  LOG_NUMBER(2) {
    @Override
    public void readValue(SliceInput sliceInput, VersionEdit versionEdit) {
      versionEdit.setLogNumber(VariableLengthQuantity.readVariableLengthLong(sliceInput));
    }

    @Override
    public void writeValue(SliceOutput sliceOutput, VersionEdit versionEdit) {
      Long logNumber = versionEdit.getLogNumber();
      if (logNumber != null) {
        VariableLengthQuantity.writeVariableLengthInt(getPersistentId(), sliceOutput);
        VariableLengthQuantity.writeVariableLengthLong(logNumber, sliceOutput);
      }
    }
  },

  NEXT_FILE_NUMBER(3) {
    @Override
    public void readValue(SliceInput sliceInput, VersionEdit versionEdit) {
      versionEdit.setNextFileNumber(VariableLengthQuantity.readVariableLengthLong(sliceInput));
    }

    @Override
    public void writeValue(SliceOutput sliceOutput, VersionEdit versionEdit) {
      Long nextFileNumber = versionEdit.getNextFileNumber();
      if (nextFileNumber != null) {
        VariableLengthQuantity.writeVariableLengthInt(getPersistentId(), sliceOutput);
        VariableLengthQuantity.writeVariableLengthLong(nextFileNumber, sliceOutput);
      }
    }
  },

  LAST_SEQUENCE(4) {
    @Override
    public void readValue(SliceInput sliceInput, VersionEdit versionEdit) {
      versionEdit.setLastSequenceNumber(VariableLengthQuantity.readVariableLengthLong(sliceInput));
    }

    @Override
    public void writeValue(SliceOutput sliceOutput, VersionEdit versionEdit) {
      Long lastSequenceNumber = versionEdit.getLastSequenceNumber();
      if (lastSequenceNumber != null) {
        VariableLengthQuantity.writeVariableLengthInt(getPersistentId(), sliceOutput);
        VariableLengthQuantity.writeVariableLengthLong(lastSequenceNumber, sliceOutput);
      }
    }
  },

  COMPACT_POINTER(5) {
    @Override
    public void readValue(SliceInput sliceInput, VersionEdit versionEdit) {
      int cfId = VariableLengthQuantity.readVariableLengthInt(sliceInput);
      int level = VariableLengthQuantity.readVariableLengthInt(sliceInput);
      InternalKey internalKey = new InternalKey(readLengthPrefixedBytes(sliceInput));
      versionEdit.setCompactPointer(cfId, level, internalKey);
    }

    @Override
    public void writeValue(SliceOutput sliceOutput, VersionEdit versionEdit) {
      for (Entry<VersionEdit.CfLevel, InternalKey> entry : versionEdit.getCompactPointers().entrySet()) {
        VariableLengthQuantity.writeVariableLengthInt(getPersistentId(), sliceOutput);

        VariableLengthQuantity.writeVariableLengthInt(entry.getKey().getCfId(), sliceOutput);
        VariableLengthQuantity.writeVariableLengthInt(entry.getKey().getLevel(), sliceOutput);

        writeLengthPrefixedBytes(sliceOutput, entry.getValue().encode());
      }
    }
  },

  DELETED_FILE(6) {
    @Override
    public void readValue(SliceInput sliceInput, VersionEdit versionEdit) {
      int cfId = VariableLengthQuantity.readVariableLengthInt(sliceInput);
      int level = VariableLengthQuantity.readVariableLengthInt(sliceInput);
      long fileNumber = VariableLengthQuantity.readVariableLengthLong(sliceInput);
      versionEdit.deleteFile(cfId, level, fileNumber);
    }

    @Override
    public void writeValue(SliceOutput sliceOutput, VersionEdit versionEdit) {
      for (Entry<VersionEdit.CfLevel, Long> entry : versionEdit.getDeletedFiles().entries()) {
        VariableLengthQuantity.writeVariableLengthInt(getPersistentId(), sliceOutput);

        VariableLengthQuantity.writeVariableLengthInt(entry.getKey().getCfId(), sliceOutput);
        VariableLengthQuantity.writeVariableLengthInt(entry.getKey().getLevel(), sliceOutput);

        VariableLengthQuantity.writeVariableLengthLong(entry.getValue(), sliceOutput);
      }
    }
  },

  NEW_FILE(7) {
    @Override
    public void readValue(SliceInput sliceInput, VersionEdit versionEdit) {
      int cfId = VariableLengthQuantity.readVariableLengthInt(sliceInput);
      int level = VariableLengthQuantity.readVariableLengthInt(sliceInput);

      long fileNumber = VariableLengthQuantity.readVariableLengthLong(sliceInput);
      long fileSize = VariableLengthQuantity.readVariableLengthLong(sliceInput);

      InternalKey smallestKey = new InternalKey(readLengthPrefixedBytes(sliceInput));
      InternalKey largestKey = new InternalKey(readLengthPrefixedBytes(sliceInput));

      versionEdit.addFile(cfId, level, fileNumber, fileSize, smallestKey, largestKey);
    }

    @Override
    public void writeValue(SliceOutput sliceOutput, VersionEdit versionEdit) {
      for (Entry<VersionEdit.CfLevel, FileMetaData> entry : versionEdit.getNewFiles().entries()) {
        VariableLengthQuantity.writeVariableLengthInt(getPersistentId(), sliceOutput);

        VariableLengthQuantity.writeVariableLengthInt(entry.getKey().getCfId(), sliceOutput);
        VariableLengthQuantity.writeVariableLengthInt(entry.getKey().getLevel(), sliceOutput);

        FileMetaData fileMetaData = entry.getValue();
        VariableLengthQuantity.writeVariableLengthLong(fileMetaData.getNumber(), sliceOutput);
        VariableLengthQuantity.writeVariableLengthLong(fileMetaData.getFileSize(), sliceOutput);

        writeLengthPrefixedBytes(sliceOutput, fileMetaData.getSmallest().encode());
        writeLengthPrefixedBytes(sliceOutput, fileMetaData.getLargest().encode());
      }
    }
  },

  PREVIOUS_LOG_NUMBER(9) {
    @Override
    public void readValue(SliceInput sliceInput, VersionEdit versionEdit) {
      long previousLogNumber = VariableLengthQuantity.readVariableLengthLong(sliceInput);
      versionEdit.setPreviousLogNumber(previousLogNumber);
    }

    @Override
    public void writeValue(SliceOutput sliceOutput, VersionEdit versionEdit) {
      Long previousLogNumber = versionEdit.getPreviousLogNumber();
      if (previousLogNumber != null) {
        VariableLengthQuantity.writeVariableLengthInt(getPersistentId(), sliceOutput);
        VariableLengthQuantity.writeVariableLengthLong(previousLogNumber, sliceOutput);
      }
    }
  };

  public static VersionEditTag getValueTypeByPersistentId(int persistentId) {
    for (VersionEditTag tag : VersionEditTag.values()) {
      if (tag.persistentId == persistentId) {
        return tag;
      }
    }
    throw new IllegalArgumentException(
        String.format("Unknown %s persistentId %d", VersionEditTag.class.getSimpleName(), persistentId));
  }

  private final int persistentId;

  VersionEditTag(int persistentId) {
    this.persistentId = persistentId;
  }

  public int getPersistentId() {
    return persistentId;
  }

  public abstract void readValue(SliceInput sliceInput, VersionEdit versionEdit);

  public abstract void writeValue(SliceOutput sliceOutput, VersionEdit versionEdit);
}