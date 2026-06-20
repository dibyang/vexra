package net.xdob.vexra.adb.h2plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.h2.message.DbException;
import org.junit.jupiter.api.Test;

class AdbJdbcUrlPrefixProviderTest {

    private final AdbJdbcUrlPrefixProvider provider = new AdbJdbcUrlPrefixProvider();

    @Test
    void mapsLdbUrlToH2UrlWithDefaultTableEngine() {
        assertTrue(provider.acceptsURL("jdbc:adb:ldb:/tmp/adb"));

        assertEquals("jdbc:h2:/tmp/adb;DEFAULT_TABLE_ENGINE=adb_table", provider.toH2Url("jdbc:adb:ldb:/tmp/adb"));
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
    void validatesProductionSettingsBeforeUrlConversion() {
        DbException error = assertThrows(DbException.class, () ->
                provider.toH2Url("jdbc:adb:mem:test;adb.production.mode=mvp-cluster;"
                        + "adb.production.topology=2data1witness"));

        assertTrue(error.getMessage().contains("mvp cluster requires TLS, auth and least privilege"));
    }

    @Test
    void stripsProductionSettingsAfterValidation() {
        assertEquals("jdbc:h2:mem:test;DB_CLOSE_DELAY=0;DEFAULT_TABLE_ENGINE=adb_table",
                provider.toH2Url("jdbc:adb:mem:test;ADB.PRODUCTION.MODE=mvp-cluster;"
                        + "adb.production.topology=2data1witness;adb.security.tls.enabled=true;"
                        + "adb.security.auth.enabled=true;adb.security.leastPrivilege.enabled=true;"
                        + "DB_CLOSE_DELAY=0"));
    }

    @Test
    void mapsPlainAdbUrlToH2Url() {
        assertEquals("jdbc:h2:mem:test;DEFAULT_TABLE_ENGINE=adb_table", provider.toH2Url("jdbc:adb:mem:test"));
    }

    @Test
    void h2DriverAcceptsAdbUrlThroughServiceLoaderProvider() throws Exception {
        assertTrue(new org.h2.Driver().acceptsURL("jdbc:adb:mem:test"));
    }
}
