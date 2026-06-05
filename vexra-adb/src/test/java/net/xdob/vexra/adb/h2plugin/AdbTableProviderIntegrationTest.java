package net.xdob.vexra.adb.h2plugin;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.Properties;
import java.sql.Statement;
import net.xdob.vexra.adb.db.DbStoreEngine;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdbTableProviderIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void createsAdbTableThroughJdbcUrlPrefix() throws Exception {
        String databasePath = tempDir.resolve("adb-provider").toAbsolutePath().toString().replace('\\', '/');
        try {
            try (Connection connection = new org.h2.Driver().connect("jdbc:adb:ldb:" + databasePath, new Properties());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
            }
        } finally {
            DbStoreEngine.close(databasePath);
        }
    }

    @Test
    void countsRowsAfterReopenThroughJdbcUrlPrefix() throws Exception {
        String databasePath = tempDir.resolve("adb-reopen").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
                statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (1, 'a'), (2, 'b')");
                Assertions.assertEquals(2L, countRows(statement));
            }
            DbStoreEngine.close(databasePath);
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                Assertions.assertEquals(2L, countRows(statement));
            }
        } finally {
            DbStoreEngine.close(databasePath);
        }
    }

    private static long countRows(Statement statement) throws Exception {
        try (ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM TEST")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }
}
