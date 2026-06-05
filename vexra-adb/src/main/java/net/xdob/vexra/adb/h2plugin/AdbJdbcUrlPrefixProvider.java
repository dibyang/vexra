package net.xdob.vexra.adb.h2plugin;

import java.util.Locale;
import org.h2.api.JdbcUrlPrefixProvider;

/**
 * ADB JDBC URL 前缀兼容 provider。
 *
 * <p>该 provider 只负责把历史 `jdbc:adb:*` 连接串映射到 h2db 原生 `jdbc:h2:*`
 * 连接串，真实 JDBC 连接、SQL 解析、Server 和工具链仍由 h2db 承担。映射过程中会默认追加
 * `DEFAULT_TABLE_ENGINE=adb_table`，让旧入口在未显式指定表引擎时优先进入 ADB 表 provider。
 */
public final class AdbJdbcUrlPrefixProvider implements JdbcUrlPrefixProvider {

    public static final String ID = "adb_jdbc_url_prefix";

    public static final String URL_PREFIX = "jdbc:adb:";

    private static final String H2_URL_PREFIX = "jdbc:h2:";

    private static final String LDB_STORE_PREFIX = "ldb:";

    private static final String ROCKSDB_STORE_PREFIX = "rocksdb:";

    private static final String DEFAULT_TABLE_ENGINE_SETTING = "DEFAULT_TABLE_ENGINE";

    /**
     * 返回 H2 插件注册表中的 provider 类型。
     *
     * @return JDBC URL 前缀 provider 类型
     */
    @Override
    public String getType() {
        return TYPE;
    }

    /**
     * 返回 ADB URL 前缀 provider 的稳定标识。
     *
     * @return provider 标识
     */
    @Override
    public String getId() {
        return ID;
    }

    /**
     * 判断当前 provider 是否支持指定能力。
     *
     * @param capability 能力名称
     * @return JDBC URL 前缀 provider 暂不暴露额外能力，固定返回 false
     */
    @Override
    public boolean supports(String capability) {
        return false;
    }

    /**
     * 返回该 provider 接管的 JDBC URL 前缀。
     *
     * @return `jdbc:adb:` 前缀
     */
    @Override
    public String getUrlPrefix() {
        return URL_PREFIX;
    }

    /**
     * 将历史 ADB JDBC URL 映射为 h2db 原生 JDBC URL。
     *
     * <p>旧实现允许 `jdbc:adb:ldb:` 和 `jdbc:adb:rocksdb:` 作为存储类型前缀。当前阶段先去掉这些
     * ADB 私有前缀，并通过默认表引擎把建表入口导向 ADB provider；存储类型的完整语义后续应迁移到
     * ADB provider 参数或 h2db storage provider。
     *
     * @param url 原始 ADB JDBC URL
     * @return 映射后的 `jdbc:h2:` URL，不匹配时返回 null
     */
    @Override
    public String toH2Url(String url) {
        if (!acceptsURL(url)) {
            return null;
        }
        String name = url.substring(URL_PREFIX.length());
        if (startsWithIgnoreCase(name, LDB_STORE_PREFIX)) {
            name = name.substring(LDB_STORE_PREFIX.length());
        } else if (startsWithIgnoreCase(name, ROCKSDB_STORE_PREFIX)) {
            name = name.substring(ROCKSDB_STORE_PREFIX.length());
        }
        String h2Url = H2_URL_PREFIX + name;
        if (containsSetting(h2Url, DEFAULT_TABLE_ENGINE_SETTING)) {
            return h2Url;
        }
        return h2Url + ";" + DEFAULT_TABLE_ENGINE_SETTING + "=" + AdbTableProvider.ID;
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static boolean containsSetting(String url, String settingName) {
        String marker = ";" + settingName.toUpperCase(Locale.ROOT) + "=";
        return url.toUpperCase(Locale.ROOT).contains(marker);
    }
}
