package net.xdob.vexra.adb.h2plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AdbJdbcUrlPrefixProviderTest {

    private final AdbJdbcUrlPrefixProvider provider = new AdbJdbcUrlPrefixProvider();

    @Test
    void mapsLdbUrlToH2UrlWithDefaultTableEngine() {
        assertTrue(provider.acceptsURL("jdbc:adb:ldb:/tmp/adb"));

        assertEquals("jdbc:h2:/tmp/adb;DEFAULT_TABLE_ENGINE=adb_table",
                provider.toH2Url("jdbc:adb:ldb:/tmp/adb"));
    }

    @Test
    void mapsRocksDbUrlToH2UrlWithDefaultTableEngine() {
        assertEquals("jdbc:h2:/tmp/adb;DEFAULT_TABLE_ENGINE=adb_table",
                provider.toH2Url("jdbc:adb:rocksdb:/tmp/adb"));
    }

    @Test
    void keepsExplicitDefaultTableEngine() {
        assertEquals("jdbc:h2:mem:test;DEFAULT_TABLE_ENGINE=custom",
                provider.toH2Url("jdbc:adb:mem:test;DEFAULT_TABLE_ENGINE=custom"));
    }

    @Test
    void mapsPlainAdbUrlToH2Url() {
        assertEquals("jdbc:h2:mem:test;DEFAULT_TABLE_ENGINE=adb_table",
                provider.toH2Url("jdbc:adb:mem:test"));
    }

    @Test
    void h2DriverAcceptsAdbUrlThroughServiceLoaderProvider() throws Exception {
        assertTrue(new org.h2.Driver().acceptsURL("jdbc:adb:mem:test"));
    }
}
