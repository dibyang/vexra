package net.xdob.vexra.adb.h2plugin;

import org.h2.api.PluginCapability;
import org.h2.api.TableEngineContext;
import org.h2.api.TableEngineProvider;
import org.h2.command.ddl.CreateTableData;
import org.h2.message.DbException;
import org.h2.table.Table;

/**
 * ADB 的 H2 表引擎 provider 原型。
 *
 * 当前 provider 只负责把 ADB 表引擎 provider 注册到 h2db。
 * 真正的建表逻辑要等 ADB 表/索引实现迁移到 org.h2 类型体系后再接入。
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
     * 当前原型只验证 provider 装载，暂不创建真实表。等 `AdbTable` 迁移到
     * `org.h2` 类型体系后，这里再接入真实建表逻辑。
     *
     * @param data 建表数据
     * @param context 表引擎上下文
     * @return 创建出的表
     */
    @Override
    public Table createTable(CreateTableData data, TableEngineContext context) {
        throw DbException.getUnsupportedException(
                "ADB table provider prototype is registered, but AdbTable is not migrated to org.h2 types yet");
    }
}
