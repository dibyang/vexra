package net.xdob.vexra.adb.db;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/**
 * SQL 分布式原型使用的共享 catalog 快照。
 *
 * <p>该快照从 UTF-8 properties 文件加载 SQL server 与 region node 共同约定的
 * Raft 目标、表标识和 TSO 读时间戳。它只在建表时解析一次，不持有后台线程或远程连接，
 * 因此可以作为 `ADB-Run-09` 的最小共享 catalog/TSO 原型。</p>
 */
public final class AdbSqlSharedCatalogSnapshot {
  private static final String RAFT_GROUP = "adb.catalog.raft.group";
  private static final String RAFT_PEERS = "adb.catalog.raft.peers";
  private static final String RAFT_DB_NAME = "adb.catalog.raft.dbName";
  private static final String TSO_READ_TS = "adb.catalog.tso.readTs";
  private static final String TSO_CURRENT = "adb.catalog.tso.current";
  private static final String TSO_READ_DELAY = "adb.catalog.tso.readDelay";
  private static final long DEFAULT_READ_DELAY = 1000L;
  private static final String DEFAULT_DB_NAME = "adb";

  private final String raftGroup;
  private final String raftPeers;
  private final String raftDbName;
  private final long readTimestamp;
  private final Properties properties;

  private AdbSqlSharedCatalogSnapshot(String raftGroup, String raftPeers,
      String raftDbName, long readTimestamp, Properties properties) {
    this.raftGroup = requireText(raftGroup, RAFT_GROUP);
    this.raftPeers = requireText(raftPeers, RAFT_PEERS);
    this.raftDbName = defaultText(raftDbName, DEFAULT_DB_NAME);
    if (readTimestamp < 0) {
      throw new IllegalArgumentException("readTimestamp is negative: "
          + readTimestamp);
    }
    this.readTimestamp = readTimestamp;
    this.properties = copyOf(properties);
  }

  /**
   * 从 UTF-8 properties 文件加载共享 catalog 快照。
   *
   * @param catalogPath catalog 文件路径
   * @return catalog 快照
   */
  public static AdbSqlSharedCatalogSnapshot load(String catalogPath) {
    String path = requireText(catalogPath, "catalogPath");
    return load(Paths.get(path));
  }

  /**
   * 从 UTF-8 properties 文件加载共享 catalog 快照。
   *
   * @param catalogPath catalog 文件路径
   * @return catalog 快照
   */
  public static AdbSqlSharedCatalogSnapshot load(Path catalogPath) {
    Objects.requireNonNull(catalogPath, "catalogPath == null");
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(catalogPath,
        StandardCharsets.UTF_8)) {
      properties.load(reader);
    } catch (IOException e) {
      throw new IllegalArgumentException("failed to load SQL shared catalog: "
          + catalogPath, e);
    }
    return new AdbSqlSharedCatalogSnapshot(
        properties.getProperty(RAFT_GROUP),
        properties.getProperty(RAFT_PEERS),
        properties.getProperty(RAFT_DB_NAME),
        resolveReadTimestamp(properties),
        properties);
  }

  /**
   * 按 SQL 表名解析远端 table id/epoch。
   *
   * @param tableName SQL 表名
   * @return 表绑定
   */
  public TableBinding table(String tableName) {
    String normalized = normalizeTableName(tableName);
    Integer tableId = integerValue(tableKey(normalized, "id"));
    Long tableEpoch = longValue(tableKey(normalized, "epoch"));
    if (tableId == null && !normalized.equals(tableName)) {
      tableId = integerValue(tableKey(tableName, "id"));
      tableEpoch = longValue(tableKey(tableName, "epoch"));
    }
    if (tableId == null) {
      throw new IllegalArgumentException("missing catalog table id for "
          + tableName);
    }
    return new TableBinding(tableId, tableEpoch == null ? 0L : tableEpoch);
  }

  public String getRaftGroup() {
    return raftGroup;
  }

  public String getRaftPeers() {
    return raftPeers;
  }

  public String getRaftDbName() {
    return raftDbName;
  }

  public long getReadTimestamp() {
    return readTimestamp;
  }

  private Integer integerValue(String key) {
    String value = trimToNull(properties.getProperty(key));
    return value == null ? null : Integer.valueOf(value);
  }

  private Long longValue(String key) {
    String value = trimToNull(properties.getProperty(key));
    return value == null ? null : Long.valueOf(value);
  }

  private static long resolveReadTimestamp(Properties properties) {
    String fixedReadTs = trimToNull(properties.getProperty(TSO_READ_TS));
    if (fixedReadTs != null) {
      return Long.parseLong(fixedReadTs);
    }
    String current = trimToNull(properties.getProperty(TSO_CURRENT));
    if (current == null) {
      throw new IllegalArgumentException(TSO_READ_TS + " or " + TSO_CURRENT
          + " is required");
    }
    long readDelay = longOrDefault(properties.getProperty(TSO_READ_DELAY),
        DEFAULT_READ_DELAY);
    return Long.parseLong(current) + readDelay;
  }

  private static String tableKey(String tableName, String suffix) {
    return "adb.catalog.table." + tableName + "." + suffix;
  }

  private static String normalizeTableName(String tableName) {
    return requireText(tableName, "tableName").toUpperCase(Locale.ROOT);
  }

  private static long longOrDefault(String value, long defaultValue) {
    String trimmed = trimToNull(value);
    return trimmed == null ? defaultValue : Long.parseLong(trimmed);
  }

  private static Properties copyOf(Properties source) {
    Properties copy = new Properties();
    copy.putAll(source);
    return copy;
  }

  private static String requireText(String value, String name) {
    String text = trimToNull(value);
    if (text == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return text;
  }

  private static String defaultText(String value, String defaultValue) {
    String text = trimToNull(value);
    return text == null ? defaultValue : text;
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /**
   * catalog 中的单表远端标识绑定。
   */
  public static final class TableBinding {
    private final int tableId;
    private final long tableEpoch;

    private TableBinding(int tableId, long tableEpoch) {
      if (tableId <= 0) {
        throw new IllegalArgumentException("tableId must be positive: "
            + tableId);
      }
      if (tableEpoch < 0) {
        throw new IllegalArgumentException("tableEpoch is negative: "
            + tableEpoch);
      }
      this.tableId = tableId;
      this.tableEpoch = tableEpoch;
    }

    public int getTableId() {
      return tableId;
    }

    public long getTableEpoch() {
      return tableEpoch;
    }
  }
}
