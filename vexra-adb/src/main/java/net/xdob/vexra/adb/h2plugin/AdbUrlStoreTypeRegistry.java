package net.xdob.vexra.adb.h2plugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.xdob.vexra.adb.db.DbStoreType;
import org.h2.store.fs.FileUtils;

/**
 * ADB JDBC URL 到底层存储类型的迁移期注册表。
 *
 * <p>h2db 当前的 `STORAGE_ENGINE` 用于选择数据库主存储路径，并会触发 system catalog provider
 * 约束。ADB 现阶段只把 LDB/RocksDB 用作表数据外部存储，因此 URL 前缀映射时在这里记录存储类型，
 * 表 provider 再按数据库路径取回。
 */
public final class AdbUrlStoreTypeRegistry {

    private static final Map<String, DbStoreType> STORE_TYPES = new ConcurrentHashMap<>();

    private AdbUrlStoreTypeRegistry() {
    }

    /**
     * 记录数据库路径对应的 ADB 存储类型。
     *
     * @param h2Url 映射后的 h2db URL
     * @param storeType ADB 存储类型
     */
    public static void register(String h2Url, DbStoreType storeType) {
        String databasePath = toDatabasePath(h2Url);
        if (databasePath != null) {
            STORE_TYPES.put(databasePath, storeType);
        }
    }

    /**
     * 查询数据库路径对应的 ADB 存储类型。
     *
     * @param databasePath h2db database path
     * @return ADB 存储类型，未登记时默认为 LDB
     */
    public static DbStoreType getStoreType(String databasePath) {
        return STORE_TYPES.getOrDefault(FileUtils.toRealPath(databasePath), DbStoreType.LDB);
    }

    private static String toDatabasePath(String h2Url) {
        String name = h2Url.substring("jdbc:h2:".length());
        int settingIndex = name.indexOf(';');
        if (settingIndex >= 0) {
            name = name.substring(0, settingIndex);
        }
        if (name.regionMatches(true, 0, "mem:", 0, "mem:".length())
                || name.regionMatches(true, 0, "tcp:", 0, "tcp:".length())
                || name.regionMatches(true, 0, "ssl:", 0, "ssl:".length())) {
            return null;
        }
        return FileUtils.toRealPath(name);
    }
}
