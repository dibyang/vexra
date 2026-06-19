package net.xdob.vexra.adb.db;

import java.util.List;
import java.util.Locale;

/**
 * ADB SQL 分布式 scan 配置。
 *
 * <p>该配置由 h2db table engine `WITH` 参数解析而来，只控制当前表的 SQL 读路径。
 * 默认关闭，避免影响旧 `jdbc:adb:*` 单机行为。当前阶段仅用于测试级和显式 opt-in
 * 的 SQL 分布式 scan，不改变磁盘格式、JDBC URL 语义或 h2db parser。</p>
 */
public final class AdbSqlDistributedScanConfig {
  static final String ENABLED_PARAM = "adb.distributed.sql";
  static final String SPLIT_ROW_PARAM = "adb.distributed.split.row";
  static final String TABLE_ID_PARAM = "adb.distributed.table.id";
  static final String TABLE_EPOCH_PARAM = "adb.distributed.table.epoch";
  static final String TIMEOUT_PARAM = "adb.distributed.scan.timeoutmillis";
  static final String READ_TS_PARAM = "adb.distributed.scan.readts";
  static final String SCAN_CLIENT_PARAM = "adb.distributed.scan.client";
  static final String WRITE_CLIENT_PARAM = "adb.distributed.write.client";
  static final String WRITE_TIMEOUT_PARAM =
      "adb.distributed.write.timeoutmillis";
  static final String RAFT_GROUP_PARAM = "adb.distributed.raft.group";
  static final String RAFT_PEERS_PARAM = "adb.distributed.raft.peers";
  static final String RAFT_DB_NAME_PARAM = "adb.distributed.raft.dbname";
  static final String CATALOG_PATH_PARAM = "adb.distributed.catalog.path";
  static final String CATALOG_TABLE_PARAM = "adb.distributed.catalog.table";

  private static final String LOCAL_SCAN_CLIENT = "local";
  private static final String RAFT_SCAN_CLIENT = "raft";
  private static final String LOCAL_WRITE_CLIENT = "local";
  private static final String RAFT_WRITE_CLIENT = "raft";
  private static final String DEFAULT_RAFT_DB_NAME = "adb";

  private final boolean enabled;
  private final Long splitRowId;
  private final Integer tableId;
  private final Long tableEpoch;
  private final long timeoutMillis;
  private final long writeTimeoutMillis;
  private final Long readTimestamp;
  private final String scanClient;
  private final String writeClient;
  private final String raftGroup;
  private final String raftPeers;
  private final String raftDbName;

  /**
   * 创建 SQL 分布式 scan 配置。
   *
   * @param enabled 是否启用 SQL 分布式 scan
   * @param splitRowId 可选测试 split rowId；null 表示单 region 全表范围
   * @param timeoutMillis 分布式 scan 超时时间，0 表示不限制
   */
  public AdbSqlDistributedScanConfig(boolean enabled, Long splitRowId,
      long timeoutMillis) {
    this(enabled, splitRowId, timeoutMillis, LOCAL_SCAN_CLIENT, null, null,
        DEFAULT_RAFT_DB_NAME, null, null, null, LOCAL_WRITE_CLIENT,
        timeoutMillis);
  }

  /**
   * 创建 SQL 分布式 scan 配置。
   *
   * @param enabled 是否启用 SQL 分布式 scan
   * @param splitRowId 可选测试 split rowId，null 表示单 region 全表范围
   * @param timeoutMillis 分布式 scan 超时时间，0 表示不限制
   * @param scanClient scan client 类型，local 或 raft
   * @param raftGroup Raft group id，仅 raft client 模式需要
   * @param raftPeers Raft peer 列表，仅 raft client 模式需要
   * @param raftDbName ADB region node 使用的数据库名
   * @param readTimestamp 可选固定读时间戳；null 表示使用当前事务 startTs
   * @param tableId 可选远端 table id；null 表示使用 H2 本地 table id
   * @param tableEpoch 可选远端 table epoch；null 表示使用 H2 本地 table epoch
   */
  public AdbSqlDistributedScanConfig(boolean enabled, Long splitRowId,
      long timeoutMillis, String scanClient, String raftGroup,
      String raftPeers, String raftDbName, Long readTimestamp,
      Integer tableId, Long tableEpoch) {
    this(enabled, splitRowId, timeoutMillis, scanClient, raftGroup, raftPeers,
        raftDbName, readTimestamp, tableId, tableEpoch, LOCAL_WRITE_CLIENT,
        timeoutMillis);
  }

  /**
   * 创建 SQL 分布式读写配置。
   *
   * @param enabled 是否启用 SQL 分布式能力
   * @param splitRowId 可选测试 split rowId；null 表示单 region 全表范围
   * @param timeoutMillis 分布式 scan 超时时间；0 表示不限制
   * @param scanClient scan client 类型，local 或 raft
   * @param raftGroup Raft group id；raft 读或写模式需要
   * @param raftPeers Raft peer 列表；raft 读或写模式需要
   * @param raftDbName ADB region node 使用的数据库名
   * @param readTimestamp 可选固定读时间戳；null 表示使用当前事务 startTs
   * @param tableId 可选远端 table id；null 表示使用 H2 本地 table id
   * @param tableEpoch 可选远端 table epoch；null 表示使用 H2 本地 table epoch
   * @param writeClient write client 类型，local 或 raft
   * @param writeTimeoutMillis 分布式写单阶段超时时间；0 表示不限制
   */
  public AdbSqlDistributedScanConfig(boolean enabled, Long splitRowId,
      long timeoutMillis, String scanClient, String raftGroup,
      String raftPeers, String raftDbName, Long readTimestamp,
      Integer tableId, Long tableEpoch, String writeClient,
      long writeTimeoutMillis) {
    if (timeoutMillis < 0) {
      throw new IllegalArgumentException("timeoutMillis is negative: "
          + timeoutMillis);
    }
    if (writeTimeoutMillis < 0) {
      throw new IllegalArgumentException("writeTimeoutMillis is negative: "
          + writeTimeoutMillis);
    }
    this.enabled = enabled;
    this.splitRowId = splitRowId;
    if (tableId != null && tableId <= 0) {
      throw new IllegalArgumentException("tableId must be positive: "
          + tableId);
    }
    if (tableEpoch != null && tableEpoch < 0) {
      throw new IllegalArgumentException("tableEpoch is negative: "
          + tableEpoch);
    }
    this.tableId = tableId;
    this.tableEpoch = tableEpoch;
    this.timeoutMillis = timeoutMillis;
    if (readTimestamp != null && readTimestamp < 0) {
      throw new IllegalArgumentException("readTimestamp is negative: "
          + readTimestamp);
    }
    this.readTimestamp = readTimestamp;
    this.scanClient = normalizeScanClient(scanClient);
    this.writeClient = normalizeWriteClient(writeClient);
    this.writeTimeoutMillis = writeTimeoutMillis;
    this.raftGroup = trimToNull(raftGroup);
    this.raftPeers = trimToNull(raftPeers);
    this.raftDbName = trimToDefault(raftDbName, DEFAULT_RAFT_DB_NAME);
    validateRemoteRaft();
  }

  /**
   * 从 h2db table engine `WITH` 参数解析配置。
   *
   * @param params table engine 参数；可以为 null
   * @return SQL 分布式 scan 配置
   */
  public static AdbSqlDistributedScanConfig fromTableEngineParams(
      List<String> params) {
    return fromTableEngineParams(params, null);
  }

  /**
   * 从 h2db table engine `WITH` 参数解析配置，并可按表名接入共享 catalog。
   *
   * @param params table engine 参数；可以为 null
   * @param tableName 当前 SQL 表名；使用 catalog.path 时用于解析 table id/epoch
   * @return SQL 分布式 scan 配置
   */
  public static AdbSqlDistributedScanConfig fromTableEngineParams(
      List<String> params, String tableName) {
    boolean enabled = false;
    Long splitRowId = null;
    long timeoutMillis = 5000L;
    long writeTimeoutMillis = 5000L;
    String scanClient = LOCAL_SCAN_CLIENT;
    String writeClient = LOCAL_WRITE_CLIENT;
    String raftGroup = null;
    String raftPeers = null;
    String raftDbName = DEFAULT_RAFT_DB_NAME;
    Long readTimestamp = null;
    Integer tableId = null;
    Long tableEpoch = null;
    String catalogPath = null;
    String catalogTable = null;
    if (params != null) {
      for (String raw : params) {
        if (raw == null) {
          continue;
        }
        String param = raw.trim();
        int separator = param.indexOf('=');
        if (separator <= 0) {
          continue;
        }
        String key = param.substring(0, separator).trim()
            .toLowerCase(Locale.ROOT);
        String value = param.substring(separator + 1).trim();
        if (ENABLED_PARAM.equals(key)) {
          enabled = Boolean.parseBoolean(value);
        } else if (SPLIT_ROW_PARAM.equals(key)) {
          splitRowId = Long.valueOf(value);
        } else if (TABLE_ID_PARAM.equals(key)) {
          tableId = Integer.valueOf(value);
        } else if (TABLE_EPOCH_PARAM.equals(key)) {
          tableEpoch = Long.valueOf(value);
        } else if (TIMEOUT_PARAM.equals(key)) {
          timeoutMillis = Long.parseLong(value);
        } else if (READ_TS_PARAM.equals(key)) {
          readTimestamp = Long.valueOf(value);
        } else if (SCAN_CLIENT_PARAM.equals(key)) {
          scanClient = value;
        } else if (WRITE_CLIENT_PARAM.equals(key)) {
          writeClient = value;
        } else if (WRITE_TIMEOUT_PARAM.equals(key)) {
          writeTimeoutMillis = Long.parseLong(value);
        } else if (RAFT_GROUP_PARAM.equals(key)) {
          raftGroup = value;
        } else if (RAFT_PEERS_PARAM.equals(key)) {
          raftPeers = value;
        } else if (RAFT_DB_NAME_PARAM.equals(key)) {
          raftDbName = value;
        } else if (CATALOG_PATH_PARAM.equals(key)) {
          catalogPath = value;
        } else if (CATALOG_TABLE_PARAM.equals(key)) {
          catalogTable = value;
        }
      }
    }
    if (trimToNull(catalogPath) != null) {
      AdbSqlSharedCatalogSnapshot catalog =
          AdbSqlSharedCatalogSnapshot.load(catalogPath);
      AdbSqlSharedCatalogSnapshot.TableBinding binding = catalog.table(
          trimToDefault(catalogTable, tableName));
      if (tableId == null) {
        tableId = binding.getTableId();
      }
      if (tableEpoch == null) {
        tableEpoch = binding.getTableEpoch();
      }
      if (readTimestamp == null) {
        readTimestamp = catalog.getReadTimestamp();
      }
      if (raftGroup == null) {
        raftGroup = catalog.getRaftGroup();
      }
      if (raftPeers == null) {
        raftPeers = catalog.getRaftPeers();
      }
      if (DEFAULT_RAFT_DB_NAME.equals(raftDbName)) {
        raftDbName = catalog.getRaftDbName();
      }
    }
    return new AdbSqlDistributedScanConfig(enabled, splitRowId, timeoutMillis,
        scanClient, raftGroup, raftPeers, raftDbName, readTimestamp, tableId,
        tableEpoch, writeClient, writeTimeoutMillis);
  }

  public boolean isEnabled() {
    return enabled;
  }

  public Long getSplitRowId() {
    return splitRowId;
  }

  public Integer getTableId() {
    return tableId;
  }

  public Long getTableEpoch() {
    return tableEpoch;
  }

  public long getTimeoutMillis() {
    return timeoutMillis;
  }

  public long getWriteTimeoutMillis() {
    return writeTimeoutMillis;
  }

  public Long getReadTimestamp() {
    return readTimestamp;
  }

  public String getScanClient() {
    return scanClient;
  }

  public boolean isRaftScanClient() {
    return enabled && RAFT_SCAN_CLIENT.equals(scanClient);
  }

  public String getWriteClient() {
    return writeClient;
  }

  public boolean isRaftWriteClient() {
    return enabled && RAFT_WRITE_CLIENT.equals(writeClient);
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

  private void validateRemoteRaft() {
    if (!isRaftScanClient() && !isRaftWriteClient()) {
      return;
    }
    if (raftGroup == null) {
      throw new IllegalArgumentException(
          RAFT_GROUP_PARAM + " is required when scan client is raft");
    }
    if (raftPeers == null) {
      throw new IllegalArgumentException(
          RAFT_PEERS_PARAM + " is required when scan client is raft");
    }
  }

  private static String normalizeScanClient(String value) {
    String normalized = trimToDefault(value, LOCAL_SCAN_CLIENT)
        .toLowerCase(Locale.ROOT);
    if (!LOCAL_SCAN_CLIENT.equals(normalized)
        && !RAFT_SCAN_CLIENT.equals(normalized)) {
      throw new IllegalArgumentException(
          "unsupported distributed scan client: " + value);
    }
    return normalized;
  }

  private static String normalizeWriteClient(String value) {
    String normalized = trimToDefault(value, LOCAL_WRITE_CLIENT)
        .toLowerCase(Locale.ROOT);
    if (!LOCAL_WRITE_CLIENT.equals(normalized)
        && !RAFT_WRITE_CLIENT.equals(normalized)) {
      throw new IllegalArgumentException(
          "unsupported distributed write client: " + value);
    }
    return normalized;
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String trimToDefault(String value, String defaultValue) {
    String trimmed = trimToNull(value);
    return trimmed == null ? defaultValue : trimmed;
  }
}
