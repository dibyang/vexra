package net.xdob.vexra.adb.h2plugin;

import net.xdob.vexra.adb.db.DbStoreEngine;
import org.h2.api.DatabaseLifecycleContext;
import org.h2.api.DatabaseLifecycleProvider;
import org.h2.api.PluginCapability;

/**
 * ADB 数据库生命周期 provider。
 *
 * <p>h2db 已提供正式的 `DatabaseLifecycleProvider` SPI，因此 ADB 不再通过 JDBC URL 注入
 * `DATABASE_EVENT_LISTENER`。数据库关闭前释放 `DbStoreEngine` 中按 database name 缓存的底层
 * store，避免 Windows 下 LDB / RocksDB 文件句柄滞留。</p>
 */
public final class AdbDatabaseLifecycleProvider implements DatabaseLifecycleProvider {

    public static final String ID = "adb_database_lifecycle";

    /**
     * 返回 provider 类型。
     *
     * @return database lifecycle provider 类型
     */
    @Override
    public String getType() {
        return TYPE;
    }

    /**
     * 返回 provider 标识。
     *
     * @return provider id
     */
    @Override
    public String getId() {
        return ID;
    }

    /**
     * 判断当前 provider 是否支持指定能力。
     *
     * @param capability 能力名称
     * @return 支持数据库生命周期监听时返回 true
     */
    @Override
    public boolean supports(String capability) {
        return PluginCapability.DATABASE_LIFECYCLE.equals(capability);
    }

    /**
     * 在 H2 关闭底层 storage 资源前释放 ADB 缓存的 store。
     *
     * @param context 数据库生命周期上下文
     */
    @Override
    public void beforeClose(DatabaseLifecycleContext context) {
        DbStoreEngine.close(context.getDatabaseName());
    }
}
