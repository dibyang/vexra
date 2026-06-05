package net.xdob.vexra.adb.h2plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.h2.api.DatabaseLifecycleProvider;
import org.h2.api.JdbcUrlPrefixProvider;
import org.h2.api.PluginProvider;
import org.h2.api.TableEngineProvider;
import org.h2.api.TransactionEventProvider;
import org.junit.jupiter.api.Test;

class AdbH2PluginTest {

    private final AdbH2Plugin plugin = new AdbH2Plugin();

    @Test
    void exposesStablePluginMetadata() {
        assertEquals(AdbH2Plugin.PLUGIN_ID, plugin.getId());
        assertEquals(AdbH2Plugin.PLUGIN_VERSION, plugin.getVersion());
        assertEquals("Vexra ADB H2 Plugin", plugin.getDisplayName());
        assertEquals("[2.3,3.0)", plugin.getH2VersionRange());
    }

    @Test
    void registersAllRequiredProviders() {
        Map<String, String> providers = new HashMap<>();
        for (PluginProvider provider : plugin.getProviders()) {
            providers.put(provider.getType(), provider.getId());
        }

        assertEquals(AdbTableProvider.ID, providers.get(TableEngineProvider.TYPE));
        assertEquals(AdbJdbcUrlPrefixProvider.ID, providers.get(JdbcUrlPrefixProvider.TYPE));
        assertEquals(AdbTransactionEventProvider.ID, providers.get(TransactionEventProvider.TYPE));
        assertEquals(AdbDatabaseLifecycleProvider.ID, providers.get(DatabaseLifecycleProvider.TYPE));
        assertTrue(providers.size() >= 4);
    }
}
