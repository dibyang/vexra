package net.xdob.vexra.ldb.impl;

import net.xdob.vexra.ldb.RawCursor;
import net.xdob.vexra.ldb.util.DbIterator;
import net.xdob.vexra.ldb.util.Slice;
import net.xdob.vexra.ldb.util.Slices;

import java.util.Map;
import java.util.NoSuchElementException;

import static net.xdob.vexra.ldb.impl.SequenceNumber.MAX_SEQUENCE_NUMBER;

public final class DbRawCursor implements RawCursor {
  private final DbIterator it;
  private Map.Entry<InternalKey, Slice> current = null;
  public DbRawCursor(DbIterator it) {
    this.it = it;
  }

  @Override
  public boolean isValid() {
    return current!=null;
  }

  @Override
  public void seekToFirst() {
    it.seekToFirst();
    peek();
  }

  private void peek() {
    try {
      current = it.peek();
    } catch (NoSuchElementException e) {
      current = null;
    }
  }

  @Override
  public void seek(byte[] target) {
    it.seek(new InternalKey(Slices.wrappedBuffer(target), MAX_SEQUENCE_NUMBER, ValueType.VALUE));
    peek();
  }

  @Override
  public void next() {
    if (!isValid()) {
      throw new IllegalStateException("Iterator is not valid");
    }
    it.next();
    peek();
  }

  @Override
  public InternalKey key() {
    if (!isValid()) {
      throw new IllegalStateException("Iterator is not valid");
    }
    return current.getKey();
  }

  @Override
  public Slice value() {
    if (!isValid()) {
      throw new IllegalStateException("Iterator is not valid");
    }
    return current.getValue();
  }

  @Override
  public void seekToLast() {
    throw new UnsupportedOperationException("seekToLast not supported yet");
  }

  @Override
  public void seekForPrev(byte[] target) {
    throw new UnsupportedOperationException("seekForPrev not supported yet");
  }


  @Override
  public void prev() {
    throw new UnsupportedOperationException("prev not supported yet");
  }

  @Override
  public void close() {
    it.close();
  }
}
