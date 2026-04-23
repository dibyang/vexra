package net.xdob.vexra.ldb.impl;

import net.xdob.vexra.ldb.RawCursor;
import net.xdob.vexra.ldb.SnapshotCursor;
import net.xdob.vexra.ldb.util.Slice;

import java.util.Arrays;
import java.util.NoSuchElementException;

import static java.util.Objects.requireNonNull;

public final class DbSnapshotCursor implements SnapshotCursor {
  private final RawCursor rawCursor;
  private final SnapshotImpl snapshot;
  private final java.util.Comparator<Slice> userComparator;

  private boolean valid;
  private byte[] currentKey;
  private byte[] currentValue;
  private boolean closed;

  public DbSnapshotCursor(RawCursor rawCursor,
                          SnapshotImpl snapshot,
                          InternalKeyComparator comparator) {
    this.rawCursor = requireNonNull(rawCursor, "rawCursor is null");
    requireNonNull(comparator, "comparator is null");
    this.userComparator = comparator.getUserComparator();
    this.snapshot = requireNonNull(snapshot, "snapshot is null");
    this.snapshot.getVersion().retain();
    this.valid = false;
    this.closed = false;
  }

  @Override
  public boolean isValid() {
    return valid;
  }

  @Override
  public void seekToFirst() {
    rawCursor.seekToFirst();
    positionToVisible();
  }

  @Override
  public void seekToLast() {
    throw new UnsupportedOperationException("seekToLast not supported yet");
  }

  @Override
  public void seek(byte[] target) {
    requireNonNull(target, "target is null");
    rawCursor.seek(target);
    positionToVisible();
  }

  @Override
  public void seekForPrev(byte[] target) {
    throw new UnsupportedOperationException("seekForPrev not supported yet");
  }

  @Override
  public void next() {
    if (!valid) {
      throw new NoSuchElementException("Cursor is not valid");
    }
    positionToVisible();
  }

  @Override
  public void prev() {
    throw new UnsupportedOperationException("prev not supported yet");
  }

  @Override
  public byte[] key() {
    if (!valid) {
      throw new NoSuchElementException("Cursor is not valid");
    }
    return currentKey;
  }

  @Override
  public byte[] value() {
    if (!valid) {
      throw new NoSuchElementException("Cursor is not valid");
    }
    return currentValue;
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    try {
      rawCursor.close();
    } finally {
      snapshot.getVersion().release();
    }
  }

  private void positionToVisible() {
    valid = false;
    currentKey = null;
    currentValue = null;

    while (rawCursor.isValid()) {
      InternalKey ik = rawCursor.key();
      Slice userKey = ik.getUserKey();

      // 先在当前 userKey 范围内，跳过所有 snapshot 之后的版本
      while (rawCursor.isValid()) {
        ik = rawCursor.key();
        if (userComparator.compare(ik.getUserKey(), userKey) != 0) {
          break;
        }
        if (ik.getSequenceNumber() <= snapshot.getLastSequence()) {
          break;
        }
        rawCursor.next();
      }

      if (!rawCursor.isValid()) {
        return;
      }

      ik = rawCursor.key();

      // 如果已经切到下一个 userKey，重新走下一轮
      if (userComparator.compare(ik.getUserKey(), userKey) != 0) {
        continue;
      }

      // 现在 ik 是这个 userKey 的第一个 <= snapshot 的版本
      if (ik.getValueType() == ValueType.DELETION) {
        skipAllVersionsOfCurrentUserKey(userKey);
        continue;
      }

      byte[] k = ik.getUserKey().getBytes();
      byte[] v = rawCursor.value().getBytes();
      currentKey = Arrays.copyOf(k, k.length);
      currentValue = Arrays.copyOf(v, v.length);

      skipAllVersionsOfCurrentUserKey(userKey);

      valid = true;
      return;
    }
  }

  private void skipAllVersionsOfCurrentUserKey(Slice userKey) {
    while (rawCursor.isValid()) {
      InternalKey ik = rawCursor.key();
      if (userComparator.compare(ik.getUserKey(), userKey) != 0) {
        return;
      }
      rawCursor.next();
    }
  }
}