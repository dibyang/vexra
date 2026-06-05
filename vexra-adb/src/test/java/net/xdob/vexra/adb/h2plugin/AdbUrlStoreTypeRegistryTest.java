package net.xdob.vexra.adb.h2plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import net.xdob.vexra.adb.db.DbStoreType;
import org.h2.store.fs.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdbUrlStoreTypeRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void registersPersistentDatabaseStoreType() {
        String databasePath = tempDir.resolve("rocks-db").toAbsolutePath().toString();

        AdbUrlStoreTypeRegistry.register("jdbc:h2:" + databasePath + ";DEFAULT_TABLE_ENGINE=adb_table",
                DbStoreType.ROCKSDB);

        assertEquals(DbStoreType.ROCKSDB, AdbUrlStoreTypeRegistry.getStoreType(databasePath));
    }

    @Test
    void defaultsToLdbForUnregisteredPersistentDatabase() {
        String databasePath = tempDir.resolve("default-ldb").toAbsolutePath().toString();

        assertEquals(DbStoreType.LDB, AdbUrlStoreTypeRegistry.getStoreType(databasePath));
    }

    @Test
    void skipsMemoryDatabaseRegistration() {
        String databasePath = tempDir.resolve("mem-not-registered").toAbsolutePath().toString();

        AdbUrlStoreTypeRegistry.register("jdbc:h2:mem:" + FileUtils.toRealPath(databasePath), DbStoreType.ROCKSDB);

        assertEquals(DbStoreType.LDB, AdbUrlStoreTypeRegistry.getStoreType(databasePath));
    }
}
