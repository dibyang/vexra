package net.xdob.vexra.ldb.table;

import net.xdob.vexra.ldb.FilterPolicy;
import net.xdob.vexra.ldb.Options;
import net.xdob.vexra.ldb.impl.SeekingIterable;
import net.xdob.vexra.ldb.util.*;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Comparator;
import java.util.concurrent.Callable;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public abstract class Table implements SeekingIterable<Slice, Slice> {
  protected final String name;
  protected final FileChannel fileChannel;
  protected final Comparator<Slice> comparator;
  protected final boolean verifyChecksums;
  protected final Block indexBlock;
  protected final BlockHandle metaindexBlockHandle;
  protected final FilterPolicy filterPolicy;
  protected final Slice filterBlock;

  protected final BlockCache blockCache;

  public Table(String name,
               FileChannel fileChannel,
               Comparator<Slice> comparator,
               boolean verifyChecksums,
               Options options,
               BlockCache blockCache) throws IOException {
    requireNonNull(name, "name is null");
    requireNonNull(fileChannel, "fileChannel is null");
    requireNonNull(comparator, "comparator is null");

    long size = fileChannel.size();
    checkArgument(size >= Footer.ENCODED_LENGTH,
        "File is corrupt: size must be at least %s bytes", Footer.ENCODED_LENGTH);

    this.name = name;
    this.fileChannel = fileChannel;
    this.verifyChecksums = verifyChecksums;
    this.comparator = comparator;
    this.filterPolicy = options == null ? null : options.filterPolicy();

    this.blockCache = blockCache;

    Footer footer = init();

    this.indexBlock = openBlock(footer.getIndexBlockHandle());
    this.metaindexBlockHandle = footer.getMetaindexBlockHandle();
    this.filterBlock = readFilterBlock();
  }


  private Slice readFilterBlock() throws IOException {
    if (filterPolicy == null) {
      return null;
    }

    Block metaIndexBlock = openBlock(metaindexBlockHandle);
    BlockIterator iterator = metaIndexBlock.iterator();

    String filterKey = "filter." + filterPolicy.name();
    Slice target = Slices.wrappedBuffer(filterKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));

    while (iterator.hasNext()) {
      BlockEntry entry = iterator.next();
      if (entry.getKey().equals(target)) {
        BlockHandle handle = BlockHandle.readBlockHandle(entry.getValue().input());
        return readRawBlock(handle);
      }
    }
    return null;
  }

  protected abstract Slice readRawBlock(BlockHandle blockHandle) throws IOException;

  public boolean mayContain(Slice userKey) {
    if (filterPolicy == null || filterBlock == null) {
      return true;
    }
    return filterPolicy.keyMayMatch(userKey, filterBlock);
  }

  protected abstract Footer init() throws IOException;

  @Override
  public TableIterator iterator() {
    return new TableIterator(this, indexBlock.iterator());
  }

  public Block openBlock(Slice blockEntry) {
    BlockHandle blockHandle = BlockHandle.readBlockHandle(blockEntry.input());
    return openBlock(blockHandle);
  }

  public Block openBlock(BlockHandle blockHandle) {
    try {
      if (blockCache == null) {
        return readBlock(blockHandle);
      }
      BlockCache.Key key = new BlockCache.Key(
          name,
          blockHandle.getOffset(),
          blockHandle.getDataSize()
      );

      Block cached = blockCache.get(key);
      if (cached != null) {
        return cached;
      }

      Block block = readBlock(blockHandle);
      blockCache.put(key, block);
      return block;
    } catch (IOException e) {
      throw new RuntimeException("Failed to open block " + blockHandle + " in " + name, e);
    }
  }

  protected abstract Block readBlock(BlockHandle blockHandle) throws IOException;

  protected int uncompressedLength(ByteBuffer data) throws IOException {
    return VariableLengthQuantity.readVariableLengthInt(data.duplicate());
  }

  public long getApproximateOffsetOf(Slice key) {
    BlockIterator iterator = indexBlock.iterator();
    iterator.seek(key);
    if (iterator.hasNext()) {
      BlockHandle blockHandle = BlockHandle.readBlockHandle(iterator.next().getValue().input());
      return blockHandle.getOffset();
    }
    return metaindexBlockHandle.getOffset();
  }

  @Override
  public String toString() {
    return "Table{" +
        "name='" + name + '\'' +
        ", comparator=" + comparator +
        ", verifyChecksums=" + verifyChecksums +
        '}';
  }

  public Callable<?> closer() {
    return new Closer(fileChannel, blockCache, name);
  }

  private static class Closer implements Callable<Void> {
    private final Closeable closeable;
    private final BlockCache blockCache;
    private final String tableName;

    private Closer(Closeable closeable, BlockCache blockCache, String tableName) {
      this.closeable = closeable;
      this.blockCache = blockCache;
      this.tableName = tableName;
    }

    @Override
    public Void call() {
      if (blockCache != null) {
        blockCache.invalidateTable(tableName);
      }
      Closeables.closeQuietly(closeable);
      return null;
    }
  }
}