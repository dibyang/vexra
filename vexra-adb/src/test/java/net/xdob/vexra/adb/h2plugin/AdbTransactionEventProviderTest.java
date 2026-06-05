package net.xdob.vexra.adb.h2plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.h2.api.PluginCapability;
import org.h2.api.TransactionEventProvider;
import org.junit.jupiter.api.Test;

class AdbTransactionEventProviderTest {

    private final AdbTransactionEventProvider provider = new AdbTransactionEventProvider();

    @Test
    void exposesTransactionEventCapability() {
        assertEquals(TransactionEventProvider.TYPE, provider.getType());
        assertEquals(AdbTransactionEventProvider.ID, provider.getId());
        assertTrue(provider.supports(PluginCapability.TRANSACTION_EVENTS));
        assertFalse(provider.supports(PluginCapability.TABLE_CREATE));
    }
}
