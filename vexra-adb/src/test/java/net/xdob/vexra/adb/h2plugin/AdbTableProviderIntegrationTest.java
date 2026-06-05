package net.xdob.vexra.adb.h2plugin;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;
import java.sql.Statement;
import net.xdob.vexra.adb.db.DbStoreEngine;
import net.xdob.vexra.adb.db.DbStoreType;
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

    @Test
    void queriesAndDeletesRowsThroughPrimaryAndSecondaryIndexes() throws Exception {
        String databasePath = tempDir.resolve("adb-index").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR, SCORE INT)");
                statement.execute("CREATE INDEX IDX_TEST_NAME ON TEST(NAME)");
                statement.executeUpdate("INSERT INTO TEST(ID, NAME, SCORE) VALUES (1, 'a', 10), (2, 'b', 20), (3, 'b', 30)");

                Assertions.assertEquals("b", singleString(statement, "SELECT NAME FROM TEST WHERE ID = 2"));
                Assertions.assertEquals(2L, singleLong(statement, "SELECT COUNT(*) FROM TEST WHERE NAME = 'b'"));
                Assertions.assertEquals(2L, singleLong(statement, "SELECT COUNT(*) FROM TEST WHERE ID BETWEEN 2 AND 3"));

                statement.executeUpdate("DELETE FROM TEST WHERE ID = 2");
                Assertions.assertEquals(2L, countRows(statement));
                Assertions.assertEquals(1L, singleLong(statement, "SELECT COUNT(*) FROM TEST WHERE NAME = 'b'"));
            }
        } finally {
            DbStoreEngine.close(databasePath);
        }
    }

    @Test
    void rollsBackAndCommitsAdbRowsThroughTransactionEvents() throws Exception {
        String databasePath = tempDir.resolve("adb-txn").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");

                connection.setAutoCommit(false);
                statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (1, 'rollback')");
                connection.rollback();
                Assertions.assertEquals(0L, countRows(statement));

                statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (2, 'commit')");
                connection.commit();
                Assertions.assertEquals(1L, countRows(statement));
                String checkpointPath = tempDir.resolve("adb-txn-checkpoint").toAbsolutePath().toString().replace('\\', '/');
                DbStoreEngine.getOrCreate(DbStoreType.LDB, databasePath, new Properties()).checkpoint(checkpointPath);
            }
            DbStoreEngine.close(databasePath);
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                Assertions.assertEquals(1L, countRows(statement));
                Assertions.assertEquals("commit", singleString(statement, "SELECT NAME FROM TEST WHERE ID = 2"));
            }
        } finally {
            DbStoreEngine.close(databasePath);
        }
    }

    @Test
    void rejectsConcurrentDuplicatePrimaryKeyWrites() throws Exception {
        String databasePath = tempDir.resolve("adb-conflict").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection setup = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = setup.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
            }

            try (Connection first = new org.h2.Driver().connect(url, new Properties());
                 Connection second = new org.h2.Driver().connect(url, new Properties());
                 Statement firstStatement = first.createStatement();
                 Statement secondStatement = second.createStatement()) {
                first.setAutoCommit(false);
                second.setAutoCommit(false);
                firstStatement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (1, 'first')");

                Assertions.assertThrows(SQLException.class,
                        () -> secondStatement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (1, 'second')"));

                first.commit();
                second.rollback();
            }

            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                Assertions.assertEquals(1L, countRows(statement));
                Assertions.assertEquals("first", singleString(statement, "SELECT NAME FROM TEST WHERE ID = 1"));
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

    private static long singleLong(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static String singleString(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }
}
