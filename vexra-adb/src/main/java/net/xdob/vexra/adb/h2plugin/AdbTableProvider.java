package net.xdob.vexra.adb.h2plugin;

import net.xdob.vexra.adb.db.AdbTable;
import net.xdob.vexra.adb.db.AdbSqlDistributedScanConfig;
import net.xdob.vexra.adb.db.AdbSqlDistributedScanRuntime;
import net.xdob.vexra.adb.db.DbStoreEngine;
import net.xdob.vexra.adb.db.DbStoreType;
import net.xdob.vexra.adb.db.TxnManager;
import org.h2.api.PluginCapability;
import org.h2.api.TableEngineContext;
import org.h2.api.TableEngineProvider;
import org.h2.command.ddl.CreateTableData;
import org.h2.message.DbException;
import org.h2.mvstore.db.MVStoreBackedStorageEngine;
import org.h2.table.Table;

/**
 * ADB 的 H2 表引擎 provider。
 *
 * <p>该 provider 是 h2db 建表流程进入 ADB 表实现的唯一入口。调用方可以通过 SQL `ENGINE`
 * 或 URL `DEFAULT_TABLE_ENGINE=adb_table` 选择它；h2db 主存储继续使用 MVStore，ADB 表数据
 * 由 `DbStoreEngine` 打开。
 */
public final class AdbTableProvider implements TableEngineProvider {

    public static final String ID = "adb_table";

    /**
     * 返回 H2 插件注册表中的 provider 类型。
     *
     * @return provider 类型
     */
    @Override
    public String getType() {
        return TYPE;
    }

    /**
     * 返回 ADB 表引擎 provider 的稳定标识。
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
     * @return 支持建表能力时返回 true
     */
    @Override
    public boolean supports(String capability) {
        return PluginCapability.TABLE_CREATE.equals(capability);
    }

    /**
     * 创建 ADB 表。
     *
     * <p>当前实现让 h2db 的 MVStore 主路径继续承载系统元数据，ADB 自有 `DbStore` 承载业务表数据。
     *
     * @param data 建表数据
     * @param context 表引擎上下文
     * @return 创建出的表
     */
    @Override
    public Table createTable(CreateTableData data, TableEngineContext context) {
        if (isH2SystemTable(data)) {
            if (context.getStorageEngine() instanceof MVStoreBackedStorageEngine) {
                MVStoreBackedStorageEngine storageEngine = (MVStoreBackedStorageEngine) context.getStorageEngine();
                return storageEngine.getStore().createTable(data);
            }
            throw DbException.getUnsupportedException("ADB table provider requires MVStore-backed system tables");
        }
        String databasePath = context.getDatabase().getDatabasePath();
        DbStoreType storeType = AdbUrlStoreTypeRegistry.getStoreType(databasePath);
        net.xdob.vexra.adb.DbStore dbStore = DbStoreEngine.getOrCreate(storeType, databasePath, new java.util.Properties());
        TxnManager txnManager = new TxnManager(dbStore);
        AdbSqlDistributedScanConfig scanConfig =
            AdbSqlDistributedScanConfig.fromTableEngineParams(
                context.getTableEngineParams());
        if (scanConfig.isEnabled()) {
            txnManager.setSqlDistributedScanRuntime(
                new AdbSqlDistributedScanRuntime(dbStore, scanConfig));
        }
        return new AdbTable(data, context.getDatabase().getStore(), dbStore, txnManager);
    }

    private static boolean isH2SystemTable(CreateTableData data) {
        String tableName = data.tableName;
        return tableName != null
                && (tableName.startsWith("SYS") || "INFORMATION_SCHEMA".equals(data.schema.getName()));
    }
}
