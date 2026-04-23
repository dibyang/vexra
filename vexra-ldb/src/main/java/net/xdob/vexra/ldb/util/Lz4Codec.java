package net.xdob.vexra.ldb.util;

import net.jpountz.lz4.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * LZ4 压缩工具类
 * <p>
 * 设计目标：
 * 1. JDK 8 可用
 * 2. 优先使用本地最快实现，失败则回退到纯 Java
 * 3. 支持 byte[] 和 stream 两种模式
 * 4. 适合 WAL / Raft Log / Snapshot / Value 压缩
 */
public final class Lz4Codec {

  private static final LZ4Factory FACTORY = createFactory();

  /**
   * fastCompressor:
   * 追求压缩速度，适合 WAL / Raft Log 等写路径
   */
  private static final LZ4Compressor FAST_COMPRESSOR = FACTORY.fastCompressor();

  /**
   * highCompressor:
   * 压缩率更高，CPU 开销更大，适合 Snapshot / 冷数据导出
   */
  private static final LZ4Compressor HIGH_COMPRESSOR = FACTORY.highCompressor();

  /**
   * fastDecompressor:
   * 解压快，但要求知道原始长度
   */
  private static final LZ4FastDecompressor FAST_DECOMPRESSOR = FACTORY.fastDecompressor();

  /**
   * safeDecompressor:
   * 更安全，适合不知道原始长度、或者做容错场景
   */
  private static final LZ4SafeDecompressor SAFE_DECOMPRESSOR = FACTORY.safeDecompressor();

  private Lz4Codec() {
  }

  private static LZ4Factory createFactory() {
    try {
      return LZ4Factory.fastestInstance();
    } catch (Throwable e) {
      return LZ4Factory.safeInstance();
    }
  }

  /**
   * 压缩，返回仅包含压缩内容的 byte[]。
   * <p>
   * 注意：
   * 这个方法本身不保存原始长度。
   * 解压时需要调用方自己知道原始长度，或者配合 compressWithLengthHeader 使用。
   */
  public static byte[] compress(byte[] data) {
    return compress(data, false);
  }

  /**
   * 压缩
   *
   * @param data            输入数据
   * @param highCompression 是否使用高压缩率模式
   */
  public static byte[] compress(byte[] data, boolean highCompression) {
    Objects.requireNonNull(data, "data");
    if (data.length == 0) {
      return new byte[0];
    }

    LZ4Compressor compressor = highCompression ? HIGH_COMPRESSOR : FAST_COMPRESSOR;
    int maxCompressedLength = maxCompressedLength(compressor, data.length);
    byte[] compressed = new byte[maxCompressedLength];
    int compressedLen = compressor.compress(data, 0, data.length, compressed, 0, maxCompressedLength);

    byte[] result = new byte[compressedLen];
    System.arraycopy(compressed, 0, result, 0, compressedLen);
    return result;
  }

  public static int maxCompressedLength(boolean highCompression, int dataLength) {
    LZ4Compressor compressor = highCompression ? HIGH_COMPRESSOR : FAST_COMPRESSOR;
    return maxCompressedLength(compressor, dataLength);
  }

  private static int maxCompressedLength(LZ4Compressor compressor, int dataLength) {
    return compressor.maxCompressedLength(dataLength);
  }

  public static int compress(byte[] input, int inputOffset, int length, byte[] output, int outputOffset) {
    return compress(false, input, inputOffset, length, output, outputOffset);
  }

  public static int compress(boolean highCompression, byte[] input, int inputOffset, int length, byte[] output, int outputOffset) {
    Objects.requireNonNull(input, "data");
    if (length == 0) {
      return 0;
    }

    LZ4Compressor compressor = highCompression ? HIGH_COMPRESSOR : FAST_COMPRESSOR;
    int maxCompressedLength = compressor.maxCompressedLength(length);
    return compressor.compress(input, inputOffset, length, output, outputOffset, maxCompressedLength);
  }


  public static int compress(ByteBuffer src, ByteBuffer dst) throws IOException {
    Objects.requireNonNull(src, "src");
    Objects.requireNonNull(dst, "dst");

    int srcLen = src.remaining();
    if (srcLen == 0) {
      return 0;
    }

    byte[] srcArray;
    int srcOffset;

    if (src.hasArray()) {
      srcArray = src.array();
      srcOffset = src.arrayOffset() + src.position();
    } else {
      // fallback（direct buffer）
      srcArray = new byte[srcLen];
      src.duplicate().get(srcArray);
      srcOffset = 0;
    }

    LZ4Compressor compressor = FAST_COMPRESSOR;
    int maxLen = compressor.maxCompressedLength(srcLen);

    if (dst.remaining() < maxLen) {
      throw new IOException("dst buffer too small: need=" + maxLen + ", actual=" + dst.remaining());
    }

    byte[] dstArray;
    int dstOffset;

    if (dst.hasArray()) {
      dstArray = dst.array();
      dstOffset = dst.arrayOffset() + dst.position();
    } else {
      dstArray = new byte[maxLen];
      dstOffset = 0;
    }

    int compressedLen = compressor.compress(
        srcArray, srcOffset, srcLen,
        dstArray, dstOffset, maxLen
    );

    if (dst.hasArray()) {
      dst.position(dst.position() + compressedLen);
    } else {
      dst.put(dstArray, 0, compressedLen);
    }

    return compressedLen;
  }

  /**
   * 解压
   * <p>
   * 适合调用方知道原始长度的场景。
   */
  public static byte[] decompress(byte[] compressed, int originalLength) {
    Objects.requireNonNull(compressed, "compressed");
    if (compressed.length == 0) {
      return new byte[0];
    }
    if (originalLength < 0) {
      throw new IllegalArgumentException("originalLength must be >= 0");
    }

    byte[] restored = new byte[originalLength];
    FAST_DECOMPRESSOR.decompress(compressed, 0, restored, 0, originalLength);
    return restored;
  }

  /**
   * 解压
   * <p>
   * 适合调用方不知道原始长度的场景。
   */
  public static int decompress(ByteBuffer compressed, ByteBuffer uncompressed) throws IOException {
    Objects.requireNonNull(compressed, "compressed");
    Objects.requireNonNull(uncompressed, "uncompressed");

    int compressedLen = compressed.remaining();
    int originalLen = uncompressed.remaining();

    if (compressedLen == 0) {
      return 0;
    }

    byte[] srcArray;
    int srcOffset;

    if (compressed.hasArray()) {
      srcArray = compressed.array();
      srcOffset = compressed.arrayOffset() + compressed.position();
    } else {
      srcArray = new byte[compressedLen];
      compressed.duplicate().get(srcArray);
      srcOffset = 0;
    }

    byte[] dstArray;
    int dstOffset;

    if (uncompressed.hasArray()) {
      dstArray = uncompressed.array();
      dstOffset = uncompressed.arrayOffset() + uncompressed.position();
    } else {
      dstArray = new byte[originalLen];
      dstOffset = 0;
    }

    FAST_DECOMPRESSOR.decompress(
        srcArray, srcOffset,
        dstArray, dstOffset,
        originalLen
    );

    if (uncompressed.hasArray()) {
      uncompressed.position(uncompressed.position() + originalLen);
    } else {
      uncompressed.put(dstArray, 0, originalLen);
    }

    return originalLen;
  }

  /**
   * 压缩并在头部写入原始长度（4字节，大端）
   * <p>
   * 格式：
   * [originalLength(4 bytes)][compressed bytes]
   * <p>
   * 适合你自己内部协议直接落盘 / 发网络包。
   */
  public static byte[] compressWithLengthHeader(byte[] data) {
    Objects.requireNonNull(data, "data");

    byte[] compressed = compress(data);
    byte[] result = new byte[4 + compressed.length];

    writeInt(result, 0, data.length);
    System.arraycopy(compressed, 0, result, 4, compressed.length);
    return result;
  }

  /**
   * 解压带长度头的内容
   */
  public static byte[] decompressWithLengthHeader(byte[] encoded) {
    Objects.requireNonNull(encoded, "encoded");
    if (encoded.length < 4) {
      throw new IllegalArgumentException("invalid lz4 payload: length header missing");
    }

    int originalLength = readInt(encoded, 0);
    if (originalLength < 0) {
      throw new IllegalArgumentException("invalid original length: " + originalLength);
    }

    int compressedLen = encoded.length - 4;
    byte[] restored = new byte[originalLength];
    FAST_DECOMPRESSOR.decompress(encoded, 4, restored, 0, originalLength);

    return restored;
  }

  /**
   * 安全解压
   * <p>
   * 用于：
   * - 不完全信任输入
   * - 不确定压缩流是否完整
   * - 想避免因为长度不匹配导致的错误传播
   *
   * @param compressed        压缩数据
   * @param maxOriginalLength 允许的最大解压长度，防止恶意超大分配
   */
  public static byte[] safeDecompress(byte[] compressed, int maxOriginalLength) {
    Objects.requireNonNull(compressed, "compressed");
    if (maxOriginalLength < 0) {
      throw new IllegalArgumentException("maxOriginalLength must be >= 0");
    }
    if (compressed.length == 0) {
      return new byte[0];
    }

    byte[] restored = new byte[maxOriginalLength];
    int actualLen = SAFE_DECOMPRESSOR.decompress(compressed, 0, compressed.length, restored, 0);

    byte[] result = new byte[actualLen];
    System.arraycopy(restored, 0, result, 0, actualLen);
    return result;
  }

  /**
   * 包装输出流
   * <p>
   * 适合大对象顺序输出，比如 snapshot 文件。
   */
  public static OutputStream wrap(OutputStream out) throws IOException {
    return wrap(out, false, 64 * 1024);
  }

  public static OutputStream wrap(OutputStream out, boolean highCompression, int blockSize) throws IOException {
    Objects.requireNonNull(out, "out");
    if (blockSize <= 0) {
      throw new IllegalArgumentException("blockSize must be > 0");
    }

    LZ4Compressor compressor = highCompression ? HIGH_COMPRESSOR : FAST_COMPRESSOR;
    return new LZ4BlockOutputStream(out, blockSize, compressor);
  }

  /**
   * 包装输入流
   */
  public static InputStream wrap(InputStream in) throws IOException {
    Objects.requireNonNull(in, "in");
    return new LZ4BlockInputStream(in);
  }

  /**
   * 直接压缩整个输入流到输出流
   */
  public static void compress(InputStream in, OutputStream out) throws IOException {
    compress(in, out, false, 64 * 1024);
  }

  public static void compress(InputStream in, OutputStream out, boolean highCompression, int blockSize) throws IOException {
    Objects.requireNonNull(in, "in");
    Objects.requireNonNull(out, "out");

    byte[] buffer = new byte[8192];
    try (OutputStream lz4Out = wrap(out, highCompression, blockSize)) {
      int n;
      while ((n = in.read(buffer)) >= 0) {
        lz4Out.write(buffer, 0, n);
      }
      lz4Out.flush();
    }
  }

  /**
   * 直接从压缩输入流解压到输出流
   */
  public static void decompress(InputStream in, OutputStream out) throws IOException {
    Objects.requireNonNull(in, "in");
    Objects.requireNonNull(out, "out");

    byte[] buffer = new byte[8192];
    try (InputStream lz4In = wrap(in)) {
      int n;
      while ((n = lz4In.read(buffer)) >= 0) {
        out.write(buffer, 0, n);
      }
      out.flush();
    }
  }

  /**
   * 便捷方法：压缩字符串内容（UTF-8）
   */
  public static byte[] compressString(String text) {
    Objects.requireNonNull(text, "text");
    try {
      return compressWithLengthHeader(text.getBytes("UTF-8"));
    } catch (IOException e) {
      throw new IllegalStateException("UTF-8 not supported", e);
    }
  }

  /**
   * 便捷方法：解压字符串内容（UTF-8）
   */
  public static String decompressString(byte[] encoded) {
    try {
      return new String(decompressWithLengthHeader(encoded), "UTF-8");
    } catch (IOException e) {
      throw new IllegalStateException("UTF-8 not supported", e);
    }
  }

  /**
   * 把一个 byte[] 按 stream 方式压缩。
   * 适合你想和 LZ4BlockInputStream / OutputStream 配套时使用。
   */
  public static byte[] compressByStream(byte[] data) {
    Objects.requireNonNull(data, "data");
    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      compress(new ByteArrayInputStream(data), baos);
      return baos.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("lz4 compressByStream failed", e);
    }
  }

  /**
   * 把一个 block stream 格式的 byte[] 解压。
   */
  public static byte[] decompressByStream(byte[] compressed) {
    Objects.requireNonNull(compressed, "compressed");
    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      decompress(new ByteArrayInputStream(compressed), baos);
      return baos.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("lz4 decompressByStream failed", e);
    }
  }

  private static void writeInt(byte[] buf, int offset, int value) {
    buf[offset] = (byte) (value >>> 24);
    buf[offset + 1] = (byte) (value >>> 16);
    buf[offset + 2] = (byte) (value >>> 8);
    buf[offset + 3] = (byte) value;
  }

  private static int readInt(byte[] buf, int offset) {
    return ((buf[offset] & 0xFF) << 24)
        | ((buf[offset + 1] & 0xFF) << 16)
        | ((buf[offset + 2] & 0xFF) << 8)
        | (buf[offset + 3] & 0xFF);
  }
}
