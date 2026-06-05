package net.xdob.vexra.adb.h2plugin;

import java.util.Arrays;
import org.h2.api.H2Plugin;
import org.h2.api.PluginProvider;

/**
 * ADB 的 H2 插件描述入口。
 *
 * 当前版本先完成插件注册和 provider 暴露，用于验证 h2db 的插件装载链路。
 * 真正的 ADB 建表逻辑仍需后续把现有 org.adb 类型体系迁移到 org.h2 类型体系。
 */
public final class AdbH2Plugin implements H2Plugin {

    public static final String PLUGIN_ID = "net.xdob.vexra.adb.h2plugin";

    public static final String PLUGIN_VERSION = "0.1.0-SNAPSHOT";

    private static final PluginProvider TABLE_PROVIDER = new AdbTableProvider();

    private static final PluginProvider JDBC_URL_PREFIX_PROVIDER = new AdbJdbcUrlPrefixProvider();

    /**
     * 返回 ADB 插件在 H2 插件注册表中的稳定标识。
     *
     * @return 插件标识
     */
    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    /**
     * 返回 ADB 插件版本，用于诊断和后续依赖约束。
     *
     * @return 插件版本
     */
    @Override
    public String getVersion() {
        return PLUGIN_VERSION;
    }

    /**
     * 返回诊断信息中展示的插件名称。
     *
     * @return 展示名称
     */
    @Override
    public String getDisplayName() {
        return "Vexra ADB H2 Plugin";
    }

    /**
     * 暴露当前 ADB 插件提供的 provider。
     *
     * @return provider 列表
     */
    @Override
    public Iterable<? extends PluginProvider> getProviders() {
        return Arrays.asList(TABLE_PROVIDER, JDBC_URL_PREFIX_PROVIDER);
    }

    /**
     * 声明当前原型适配的 h2db 版本范围。
     *
     * @return H2 版本范围
     */
    @Override
    public String getH2VersionRange() {
        return "[2.3,3.0)";
    }
}
