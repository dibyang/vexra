package net.xdob.vexra.cluster.ddl;

/**
 * Schema 版本。
 *
 * <p>SQL session 可绑定某个 schema version，以避免 Online DDL 与事务并发时读到不一致
 * 的表/索引定义。</p>
 */
public final class SchemaVersion {
  private final long version;

  /**
   * 创建 schema version。
   *
   * @param version 版本号，必须非负
   */
  public SchemaVersion(long version) {
    if (version < 0) {
      throw new IllegalArgumentException("version is negative: " + version);
    }
    this.version = version;
  }

  public long getVersion() {
    return version;
  }

  /**
   * 生成下一个 schema version。
   *
   * @return version + 1
   */
  public SchemaVersion next() {
    return new SchemaVersion(version + 1);
  }
}
