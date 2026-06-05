package net.xdob.vexra.adb.h2plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.h2.api.DatabaseLifecycleProvider;
import org.h2.api.PluginCapability;
import org.junit.jupiter.api.Test;

class AdbDatabaseLifecycleProviderTest {

    private final AdbDatabaseLifecycleProvider provider = new AdbDatabaseLifecycleProvider();

    @Test
    void exposesDatabaseLifecycleCapability() {
        assertEquals(DatabaseLifecycleProvider.TYPE, provider.getType());
        assertEquals(AdbDatabaseLifecycleProvider.ID, provider.getId());
        assertTrue(provider.supports(PluginCapability.DATABASE_LIFECYCLE));
    }
}
