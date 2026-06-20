package net.xdob.vexra.adb.h2plugin;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import net.xdob.vexra.adb.db.AdbCrossRegionTxnGuard;
import net.xdob.vexra.adb.db.AdbProductionCapability;
import net.xdob.vexra.adb.db.AdbProductionGuard;
import net.xdob.vexra.adb.db.AdbProductionRequestContext;
import net.xdob.vexra.adb.db.AdbTable;
import net.xdob.vexra.adb.db.AdbSqlDistributedScanConfig;
import net.xdob.vexra.adb.db.AdbSqlDistributedScanRuntime;
import net.xdob.vexra.adb.db.AdbSqlDistributedTimestampProvider;
import net.xdob.vexra.adb.db.AdbSqlDistributedWriteRuntime;
import net.xdob.vexra.adb.db.AdbSqlDiagnosticsRegistry;
import net.xdob.vexra.adb.db.DbStoreEngine;
import net.xdob.vexra.adb.db.DbStoreType;
import net.xdob.vexra.adb.db.TxnManager;
import net.xdob.vexra.adb.key.TabId;
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
        AdbSqlDistributedScanConfig scanConfig =
            AdbSqlDistributedScanConfig.fromTableEngineParams(
                context.getTableEngineParams(), data.tableName);
        AdbProductionGuard productionGuard =
            productionGuard(context.getTableEngineParams());
        AdbProductionRequestContext requestContext =
            AdbProductionRequestContext.local("create table " + data.tableName);
        requireProductionCapability(productionGuard,
            AdbProductionCapability.LOCAL_SQL, requestContext);
        boolean productionParamsPresent =
            hasProductionParams(context.getTableEngineParams());
        if (productionParamsPresent && scanConfig.isEnabled()) {
            requireProductionCapability(productionGuard,
                AdbProductionCapability.DISTRIBUTED_SQL, requestContext);
        }
        if (productionParamsPresent && scanConfig.isRaftWriteClient()) {
            requireProductionCapability(productionGuard,
                AdbProductionCapability.SINGLE_REGION_TRANSACTION,
                requestContext);
        }
        DbStoreType storeType = AdbUrlStoreTypeRegistry.getStoreType(databasePath);
        net.xdob.vexra.adb.DbStore dbStore = DbStoreEngine.getOrCreate(storeType, databasePath, new java.util.Properties());
        TxnManager txnManager = new TxnManager(dbStore);
        if (productionParamsPresent) {
            txnManager.setTxnRegionGuard(
                AdbCrossRegionTxnGuard.fromProductionGuard(productionGuard));
        }
        txnManager.setSqlDiagnosticRecorder(AdbSqlDiagnosticsRegistry
            .getOrCreate(AdbSqlDiagnosticsRegistry.scope(databasePath)));
        if (scanConfig.isEnabled()) {
            txnManager.setSqlDistributedScanRuntime(
                new AdbSqlDistributedScanRuntime(dbStore, scanConfig));
        }
        AdbTable table = new AdbTable(data, context.getDatabase().getStore(),
            dbStore, txnManager);
        if (scanConfig.isRaftWriteClient()) {
            AdbSqlDistributedWriteRuntime writeRuntime =
                new AdbSqlDistributedWriteRuntime(scanConfig);
            txnManager.setSqlDistributedWriteRuntime(writeRuntime);
            txnManager.setTimestampProvider(
                new AdbSqlDistributedTimestampProvider(
                    scanConfig.getReadTimestamp()));
            txnManager.setRegionCommitCoordinator(writeRuntime.coordinator(
                TabId.of(table.getId(), 0L)));
        }
        return table;
    }

    private static boolean isH2SystemTable(CreateTableData data) {
        String tableName = data.tableName;
        return tableName != null
                && (tableName.startsWith("SYS") || "INFORMATION_SCHEMA".equals(data.schema.getName()));
    }

    private static AdbProductionGuard productionGuard(List<String> params) {
        Properties properties = productionProperties(params);
        if (properties.isEmpty()) {
            return AdbProductionGuard.singleNodeDefault();
        }
        return AdbProductionGuard.fromProperties(properties);
    }

    private static boolean hasProductionParams(List<String> params) {
        return !productionProperties(params).isEmpty();
    }

    private static Properties productionProperties(List<String> params) {
        Properties properties = new Properties();
        if (params == null) {
            return properties;
        }
        for (String raw : params) {
            if (raw == null) {
                continue;
            }
            String param = raw.trim();
            int separator = param.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = productionKey(param.substring(0, separator));
            if (key != null) {
                properties.setProperty(key, param.substring(separator + 1).trim());
            }
        }
        return properties;
    }

    private static String productionKey(String rawKey) {
        String key = rawKey.trim().toLowerCase(Locale.ROOT);
        if (AdbProductionGuard.MODE_KEY.equals(key)) {
            return AdbProductionGuard.MODE_KEY;
        }
        if (AdbProductionGuard.TOPOLOGY_KEY.equals(key)) {
            return AdbProductionGuard.TOPOLOGY_KEY;
        }
        if (AdbProductionGuard.INSTALL_TOPOLOGY_KEY.equals(key)) {
            return AdbProductionGuard.INSTALL_TOPOLOGY_KEY;
        }
        if (AdbProductionGuard.ALLOW_EXPERIMENTAL_KEY.toLowerCase(Locale.ROOT)
            .equals(key)) {
            return AdbProductionGuard.ALLOW_EXPERIMENTAL_KEY;
        }
        if (AdbProductionGuard.TLS_KEY.equals(key)) {
            return AdbProductionGuard.TLS_KEY;
        }
        if (AdbProductionGuard.AUTH_KEY.equals(key)) {
            return AdbProductionGuard.AUTH_KEY;
        }
        if (AdbProductionGuard.LEAST_PRIVILEGE_KEY.toLowerCase(Locale.ROOT)
            .equals(key)) {
            return AdbProductionGuard.LEAST_PRIVILEGE_KEY;
        }
        return null;
    }

    private static void requireProductionCapability(
        AdbProductionGuard productionGuard, AdbProductionCapability capability,
        AdbProductionRequestContext requestContext) {
        try {
            productionGuard.requireCapability(capability, requestContext);
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
    }
}
