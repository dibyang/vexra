package net.xdob.vexra.adb.db;


import java.util.Arrays;

public class DynamicByteBuffer {

  private byte[] buf;

  // 鍐欐寚閽?
  private int pos;

  // 璇绘寚閽?
  private int readPos;

  // =========================
  // 鏋勯€?
  // =========================

  public DynamicByteBuffer() {
    this(128);
  }

  public DynamicByteBuffer(int initialCapacity) {
    this.buf = new byte[initialCapacity];
    this.pos = 0;
    this.readPos = 0;
  }

  // wrap宸叉湁鏁版嵁锛堢敤浜庤鍙栵級
  public static DynamicByteBuffer wrap(byte[] data) {
    DynamicByteBuffer buffer = new DynamicByteBuffer(0);
    buffer.buf = data;
    buffer.pos = data.length;
    buffer.readPos = 0;
    return buffer;
  }

  public static DynamicByteBuffer c(){
    return new DynamicByteBuffer();
  }

  // =========================
  // 鍩虹鑳藉姏
  // =========================

  private void ensureCapacity(int additional) {
    int required = pos + additional;
    if (required > buf.length) {
      int newCap = buf.length == 0 ? 1 : buf.length;
      while (newCap < required) {
        newCap = newCap << 1;
      }
      buf = Arrays.copyOf(buf, newCap);
    }
  }

  public int position() {
    return pos;
  }

  public int remaining() {
    return pos - readPos;
  }

  public boolean hasRemaining() {
    return readPos < pos;
  }

  public void reset() {
    pos = 0;
    readPos = 0;
  }

  public void rewind() {
    readPos = 0;
  }

  // =========================
  // put 鏂规硶锛堝啓锛?
  // =========================

  public DynamicByteBuffer put(byte b) {
    ensureCapacity(1);
    buf[pos++] = b;
    return this;
  }

  public DynamicByteBuffer put(byte[] bytes) {
    if (bytes == null) {
      throw new IllegalArgumentException("bytes is null");
    }
    ensureCapacity(bytes.length);
    System.arraycopy(bytes, 0, buf, pos, bytes.length);
    pos += bytes.length;
    return this;
  }

  public DynamicByteBuffer put(byte[] bytes, int off, int len) {
    ensureCapacity(len);
    System.arraycopy(bytes, off, buf, pos, len);
    pos += len;
    return this;
  }

  public DynamicByteBuffer putInt(int v) {
    ensureCapacity(4);
    buf[pos++] = (byte) (v >>> 24);
    buf[pos++] = (byte) (v >>> 16);
    buf[pos++] = (byte) (v >>> 8);
    buf[pos++] = (byte) v;
    return this;
  }

  public DynamicByteBuffer putLong(long v) {
    ensureCapacity(8);
    buf[pos++] = (byte) (v >>> 56);
    buf[pos++] = (byte) (v >>> 48);
    buf[pos++] = (byte) (v >>> 40);
    buf[pos++] = (byte) (v >>> 32);
    buf[pos++] = (byte) (v >>> 24);
    buf[pos++] = (byte) (v >>> 16);
    buf[pos++] = (byte) (v >>> 8);
    buf[pos++] = (byte) v;
    return this;
  }

  public DynamicByteBuffer putFloat(float f) {
    return putInt(Float.floatToIntBits(f));
  }

  public DynamicByteBuffer putDouble(double d) {
    return putLong(Double.doubleToLongBits(d));
  }

  public DynamicByteBuffer putBytesWithLength(byte[] bytes) {
    if (bytes == null) {
      putInt(-1);
    } else {
      putInt(bytes.length);
      put(bytes);
    }
    return this;
  }

  // =========================
  // get 鏂规硶锛堣锛?
  // =========================

  public byte get() {
    checkReadable(1);
    return buf[readPos++];
  }

  public int getInt() {
    checkReadable(4);
    int v = ((buf[readPos] & 0xFF) << 24)
        | ((buf[readPos + 1] & 0xFF) << 16)
        | ((buf[readPos + 2] & 0xFF) << 8)
        | (buf[readPos + 3] & 0xFF);
    readPos += 4;
    return v;
  }

  public long getLong() {
    checkReadable(8);
    long v = ((long)(buf[readPos] & 0xFF) << 56)
        | ((long)(buf[readPos + 1] & 0xFF) << 48)
        | ((long)(buf[readPos + 2] & 0xFF) << 40)
        | ((long)(buf[readPos + 3] & 0xFF) << 32)
        | ((long)(buf[readPos + 4] & 0xFF) << 24)
        | ((long)(buf[readPos + 5] & 0xFF) << 16)
        | ((long)(buf[readPos + 6] & 0xFF) << 8)
        | ((long)(buf[readPos + 7] & 0xFF));
    readPos += 8;
    return v;
  }

  public float getFloat() {
    return Float.intBitsToFloat(getInt());
  }

  public double getDouble() {
    return Double.longBitsToDouble(getLong());
  }

  public byte[] getBytes(int len) {
    checkReadable(len);
    byte[] result = new byte[len];
    System.arraycopy(buf, readPos, result, 0, len);
    readPos += len;
    return result;
  }

  public byte[] getBytesWithLength() {
    int len = getInt();
    if (len < 0) {
      return null;
    }
    return getBytes(len);
  }

  private void checkReadable(int len) {
    if (readPos + len > pos) {
      throw new IndexOutOfBoundsException("Not enough data to read");
    }
  }

  // =========================
  // 杈撳嚭
  // =========================

  public byte[] toArray() {
    return Arrays.copyOf(buf, pos);
  }

  public int size() {
    return pos;
  }
}