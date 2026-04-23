package net.xdob.vexra.ldb.table;

import net.xdob.vexra.ldb.CompressionType;
import net.xdob.vexra.ldb.Options;
import net.xdob.vexra.ldb.util.*;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.util.Comparator;
import java.util.concurrent.Callable;

import static com.google.common.base.Preconditions.checkArgument;

public class MMapTable extends Table {
  private static final int DEFAULT_SCRATCH_SIZE = 64 * 1024;

  private static final ThreadLocal<ByteBuffer> UNCOMPRESSED_SCRATCH =
      ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(DEFAULT_SCRATCH_SIZE));

  private MappedByteBuffer data;

  public MMapTable(String name,
                   FileChannel fileChannel,
                   Comparator<Slice> comparator,
                   boolean verifyChecksums,
                   Options options,
                   BlockCache blockCache) throws IOException {
    super(name, fileChannel, comparator, verifyChecksums, options, blockCache);
    checkArgument(fileChannel.size() <= Integer.MAX_VALUE,
        "File must be smaller than %s bytes", Integer.MAX_VALUE);
  }

  @Override
  protected Footer init() throws IOException {
    long size = fileChannel.size();
    data = fileChannel.map(MapMode.READ_ONLY, 0, size);
    Slice footerSlice = Slices.copiedBuffer(data, (int) size - Footer.ENCODED_LENGTH, Footer.ENCODED_LENGTH);
    return Footer.readFooter(footerSlice);
  }

  @Override
  public Callable<?> closer() {
    return new Closer(name, fileChannel, data, blockCache);
  }

  private static class Closer implements Callable<Void> {
    private final String name;
    private final Closeable closeable;
    private final MappedByteBuffer data;
    private final BlockCache blockCache;

    public Closer(String name, Closeable closeable, MappedByteBuffer data, BlockCache blockCache) {
      this.name = name;
      this.closeable = closeable;
      this.data = data;
      this.blockCache = blockCache;
    }

    @Override
    public Void call() {
      if (blockCache != null) {
        blockCache.invalidateTable(name);
      }
      ByteBufferSupport.unmap(data);
      Closeables.closeQuietly(closeable);
      return null;
    }
  }

  @Override
  protected Block readBlock(BlockHandle blockHandle) throws IOException {
    return new Block(readRawBlock(blockHandle), comparator);
  }

  @Override
  protected Slice readRawBlock(BlockHandle blockHandle) throws IOException {
    BlockTrailer blockTrailer = BlockTrailer.readBlockTrailer(
        Slices.copiedBuffer(
            this.data,
            (int) blockHandle.getOffset() + blockHandle.getDataSize(),
            BlockTrailer.ENCODED_LENGTH
        )
    );

    ByteBuffer blockBuffer = read(this.data, (int) blockHandle.getOffset(), blockHandle.getDataSize());

    if (blockTrailer.getCompressionType() == CompressionType.LZ4) {
      ByteBuffer headerView = blockBuffer.duplicate();
      int uncompressedLength = VariableLengthQuantity.readVariableLengthInt(headerView);

      ByteBuffer compressedView = headerView.slice();

      ByteBuffer out = ensureScratchCapacity(uncompressedLength);
      out.clear();
      out.limit(uncompressedLength);

      Lz4Codec.decompress(compressedView, out);

      out.flip();
      return Slices.copiedBuffer(out);
    }

    return Slices.copiedBuffer(blockBuffer);
  }

  private static ByteBuffer ensureScratchCapacity(int required) {
    ByteBuffer buffer = UNCOMPRESSED_SCRATCH.get();
    if (buffer.capacity() < required) {
      int newCapacity = Math.max(required, buffer.capacity() << 1);
      buffer = ByteBuffer.allocateDirect(newCapacity);
      UNCOMPRESSED_SCRATCH.set(buffer);
    }
    return buffer;
  }


  public static ByteBuffer read(MappedByteBuffer data, int offset, int length) throws IOException {
    ByteBuffer dup = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    dup.position(offset);
    dup.limit(offset + length);
    return dup.slice().order(ByteOrder.LITTLE_ENDIAN);
  }
}