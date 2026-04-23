package net.xdob.vexra.ldb.table;

import net.xdob.vexra.ldb.Options;
import net.xdob.vexra.ldb.util.Lz4Codec;
import net.xdob.vexra.ldb.util.Slice;
import net.xdob.vexra.ldb.util.Slices;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Comparator;

import static net.xdob.vexra.ldb.CompressionType.LZ4;

public class FileChannelTable extends Table {

  private static final int DEFAULT_SCRATCH_SIZE = 64 * 1024;

  private static final ThreadLocal<ByteBuffer> UNCOMPRESSED_SCRATCH =
      ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(DEFAULT_SCRATCH_SIZE));

  public FileChannelTable(
      String name,
      FileChannel fileChannel,
      Comparator<Slice> comparator,
      boolean verifyChecksums,
      Options options,
      BlockCache blockCache) throws IOException {
    super(name, fileChannel, comparator, verifyChecksums, options, blockCache);
  }

  @Override
  protected Footer init() throws IOException {
    long size = fileChannel.size();
    ByteBuffer footerData = readFully(size - Footer.ENCODED_LENGTH, Footer.ENCODED_LENGTH);
    return Footer.readFooter(Slices.copiedBuffer(footerData));
  }

  @Override
  protected Block readBlock(BlockHandle blockHandle) throws IOException {
    return new Block(readRawBlock(blockHandle), comparator);
  }

  @Override
  protected Slice readRawBlock(BlockHandle blockHandle) throws IOException {
    ByteBuffer trailerData = readFully(
        blockHandle.getOffset() + blockHandle.getDataSize(),
        BlockTrailer.ENCODED_LENGTH
    );
    BlockTrailer blockTrailer = BlockTrailer.readBlockTrailer(Slices.copiedBuffer(trailerData));

    ByteBuffer blockData = readFully(blockHandle.getOffset(), blockHandle.getDataSize());

    // todo: verifyChecksums 时补 CRC 校验

    if (blockTrailer.getCompressionType() == LZ4) {
      int uncompressedLength = uncompressedLength(blockData.duplicate());

      ByteBuffer out = ensureScratchCapacity(uncompressedLength);
      out.clear();
      out.limit(uncompressedLength);

      Lz4Codec.decompress(blockData.duplicate(), out);
      out.flip();

      return Slices.copiedBuffer(out);
    }

    return Slices.copiedBuffer(blockData);
  }

  private ByteBuffer ensureScratchCapacity(int required) {
    ByteBuffer buffer = UNCOMPRESSED_SCRATCH.get();
    if (buffer.capacity() < required) {
      int newCapacity = Math.max(required, buffer.capacity() << 1);
      buffer = ByteBuffer.allocateDirect(newCapacity);
      UNCOMPRESSED_SCRATCH.set(buffer);
    }
    return buffer;
  }

  private ByteBuffer readFully(long offset, int length) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(length);
    while (buffer.hasRemaining()) {
      int n = fileChannel.read(buffer, offset + buffer.position());
      if (n < 0) {
        throw new IOException("Unexpected EOF while reading file. offset=" + offset + ", length=" + length);
      }
    }
    buffer.flip();
    return buffer;
  }
}