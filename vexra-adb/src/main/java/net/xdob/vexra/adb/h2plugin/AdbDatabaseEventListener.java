package net.xdob.vexra.adb.h2plugin;

import net.xdob.vexra.adb.db.DbStoreEngine;
import org.h2.api.DatabaseEventListener;
import org.h2.engine.ConnectionInfo;
import org.h2.message.DbException;

/**
 * ADB 数据库关闭监听器。
 *
 * <p>当前 h2db 插件 SPI 尚未提供数据库生命周期 provider。ADB 通过 H2 原生
 * `DATABASE_EVENT_LISTENER` 在数据库正常关闭前释放 `DbStoreEngine` 中按 database path
 * 缓存的底层 store，避免 Windows 上 LDB / RocksDB 文件句柄滞留。</p>
 */
public final class AdbDatabaseEventListener implements DatabaseEventListener {

    private String databaseName;

    /**
     * 记录 H2 传入的数据库 URL，并解析为与 `Database.getDatabasePath()` 对齐的归一化名称。
     *
     * @param url H2 数据库 URL
     */
    @Override
    public void init(String url) {
        try {
            databaseName = new ConnectionInfo(url, null, null, null).getName();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw DbException.convert(e);
        }
    }

    /**
     * 在 H2 正常关闭数据库前释放 ADB 底层 store。
     */
    @Override
    public void closingDatabase() {
        if (databaseName != null) {
            DbStoreEngine.close(databaseName);
        }
    }
}
