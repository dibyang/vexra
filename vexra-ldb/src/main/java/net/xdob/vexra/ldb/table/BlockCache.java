package net.xdob.vexra.ldb.table;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class BlockCache {

  public static final class Key {
    private final String tableName;
    private final long offset;
    private final int dataSize;

    public Key(String tableName, long offset, int dataSize) {
      this.tableName = Objects.requireNonNull(tableName, "tableName == null");
      this.offset = offset;
      this.dataSize = dataSize;
    }

    public String getTableName() {
      return tableName;
    }

    public long getOffset() {
      return offset;
    }

    public int getDataSize() {
      return dataSize;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Key)) {
        return false;
      }
      Key key = (Key) o;
      return offset == key.offset
          && dataSize == key.dataSize
          && tableName.equals(key.tableName);
    }

    @Override
    public int hashCode() {
      int result = tableName.hashCode();
      result = 31 * result + Long.hashCode(offset);
      result = 31 * result + Integer.hashCode(dataSize);
      return result;
    }

    @Override
    public String toString() {
      return "Key{"
          + "tableName='" + tableName + '\''
          + ", offset=" + offset
          + ", dataSize=" + dataSize
          + '}';
    }
  }

  private final int maxEntries;
  private final LinkedHashMap<Key, Block> lru;

  public BlockCache(int maxEntries) {
    if (maxEntries <= 0) {
      throw new IllegalArgumentException("maxEntries must be > 0");
    }
    this.maxEntries = maxEntries;
    this.lru = new LinkedHashMap<Key, Block>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<Key, Block> eldest) {
        return size() > BlockCache.this.maxEntries;
      }
    };
  }

  public Block get(Key key) {
    synchronized (lru) {
      return lru.get(key);
    }
  }

  public void put(Key key, Block block) {
    synchronized (lru) {
      lru.put(key, block);
    }
  }

  public void invalidateTable(String tableName) {
    synchronized (lru) {
      lru.entrySet().removeIf(e -> e.getKey().getTableName().equals(tableName));
    }
  }

  public void invalidateAll() {
    synchronized (lru) {
      lru.clear();
    }
  }

  public int size() {
    synchronized (lru) {
      return lru.size();
    }
  }
}