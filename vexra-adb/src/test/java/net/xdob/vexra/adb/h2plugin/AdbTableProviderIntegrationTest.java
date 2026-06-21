package net.xdob.vexra.adb.h2plugin;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.sql.Statement;
import net.xdob.vexra.adb.jdbc.AdbDriver;
import net.xdob.vexra.adb.db.AdbSqlDiagnosticSnapshot;
import net.xdob.vexra.adb.db.AdbSqlDiagnosticsRegistry;
import net.xdob.vexra.adb.db.AdbTable;
import net.xdob.vexra.adb.db.DbStoreEngine;
import net.xdob.vexra.adb.db.DbStoreType;
import org.h2.engine.SessionLocal;
import org.h2.jdbc.JdbcConnection;
import org.h2.message.DbException;
import org.h2.result.DefaultRow;
import org.h2.schema.Schema;
import org.h2.table.Table;
import org.h2.value.Value;
import org.h2.value.ValueBigint;
import org.h2.value.ValueVarchar;
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
    void recordsSqlDiagnosticsThroughJdbcTableEnginePath() throws Exception {
        AdbSqlDiagnosticsRegistry.clear();
        String databasePath = tempDir.resolve("adb-sql-diagnostics").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
                statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (1, 'a'), (2, 'b')");
                Assertions.assertEquals("b", singleString(statement, "SELECT NAME FROM TEST WHERE ID = 2"));
                Assertions.assertEquals(2L, countRows(statement));
            }

            AdbSqlDiagnosticSnapshot snapshot = AdbSqlDiagnosticsRegistry
                    .get(AdbSqlDiagnosticsRegistry.scope(databasePath))
                    .snapshot();
            Map<String, Number> metrics = snapshot.toMetrics("adb_sql");

            Assertions.assertTrue(snapshot.getTotalSqlCount() >= 3,
                    "expected table engine operations to be recorded");
            Assertions.assertTrue(snapshot.getMaxLatencyMillis() >= 0);
            Assertions.assertFalse(snapshot.getOperationStats().isEmpty());
            Assertions.assertTrue(snapshot.getOperationStats().containsKey(
                    "ADB_TABLE_ADD_ROW TEST"));
            Assertions.assertTrue(metrics.containsKey("adb_sql_total_sql_count"));
        } finally {
            DbStoreEngine.close(databasePath);
            AdbSqlDiagnosticsRegistry.clear();
        }
    }

    @Test
    void preparedMultiValuesInsertUsesAdbDriverBulkPath() throws Exception {
        AdbSqlDiagnosticsRegistry.clear();
        Class.forName(AdbDriver.class.getName());
        String databasePath = tempDir.resolve("adb-driver-prepared-bulk").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO TEST(ID, NAME) VALUES (?, ?), (?, ?), (?, ?)")) {
                    insert.setLong(1, 1L);
                    insert.setString(2, "a");
                    insert.setLong(3, 2L);
                    insert.setString(4, "b");
                    insert.setLong(5, 3L);
                    insert.setString(6, "c");
                    Assertions.assertEquals(3, insert.executeUpdate());
                }
                Assertions.assertEquals("a,b,c", csv(statement,
                        "SELECT NAME FROM TEST ORDER BY ID"));
            }

            AdbSqlDiagnosticSnapshot snapshot = AdbSqlDiagnosticsRegistry
                    .get(AdbSqlDiagnosticsRegistry.scope(databasePath))
                    .snapshot();
            Assertions.assertTrue(snapshot.getOperationStats().containsKey(
                    "ADB_TABLE_BULK_ADD_ROW TEST"), snapshot.getOperationStats().keySet().toString());
            Assertions.assertFalse(snapshot.getOperationStats().containsKey(
                    "ADB_TABLE_ADD_ROW TEST"), snapshot.getOperationStats().keySet().toString());
        } finally {
            DbStoreEngine.close(databasePath);
            AdbSqlDiagnosticsRegistry.clear();
        }
    }

    @Test
    void statementLiteralMultiValuesInsertUsesAdbDriverBulkPath() throws Exception {
        AdbSqlDiagnosticsRegistry.clear();
        Class.forName(AdbDriver.class.getName());
        String databasePath = tempDir.resolve("adb-driver-statement-bulk").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
                Assertions.assertEquals(3, statement.executeUpdate(
                        "INSERT INTO TEST(ID, NAME) VALUES (1, 'a'), (2, 'b'), (3, 'c')"));
                Assertions.assertEquals("a,b,c", csv(statement,
                        "SELECT NAME FROM TEST ORDER BY ID"));
            }

            AdbSqlDiagnosticSnapshot snapshot = AdbSqlDiagnosticsRegistry
                    .get(AdbSqlDiagnosticsRegistry.scope(databasePath))
                    .snapshot();
            Assertions.assertTrue(snapshot.getOperationStats().containsKey(
                    "ADB_TABLE_BULK_ADD_ROW TEST"), snapshot.getOperationStats().keySet().toString());
            Assertions.assertFalse(snapshot.getOperationStats().containsKey(
                    "ADB_TABLE_ADD_ROW TEST"), snapshot.getOperationStats().keySet().toString());
        } finally {
            DbStoreEngine.close(databasePath);
            AdbSqlDiagnosticsRegistry.clear();
        }
    }

    @Test
    void unsupportedStatementInsertFallsBackToH2Path() throws Exception {
        AdbSqlDiagnosticsRegistry.clear();
        Class.forName(AdbDriver.class.getName());
        String databasePath = tempDir.resolve("adb-driver-statement-fallback").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
                Assertions.assertEquals(1, statement.executeUpdate(
                        "INSERT INTO TEST(ID, NAME) VALUES (1, UPPER('a'))"));
                Assertions.assertEquals("A", singleString(statement,
                        "SELECT NAME FROM TEST WHERE ID = 1"));
            }

            AdbSqlDiagnosticSnapshot snapshot = AdbSqlDiagnosticsRegistry
                    .get(AdbSqlDiagnosticsRegistry.scope(databasePath))
                    .snapshot();
            Assertions.assertTrue(snapshot.getOperationStats().containsKey(
                    "ADB_TABLE_ADD_ROW TEST"), snapshot.getOperationStats().keySet().toString());
            Assertions.assertFalse(snapshot.getOperationStats().containsKey(
                    "ADB_TABLE_BULK_ADD_ROW TEST"), snapshot.getOperationStats().keySet().toString());
        } finally {
            DbStoreEngine.close(databasePath);
            AdbSqlDiagnosticsRegistry.clear();
        }
    }

    @Test
    void preparedPrimaryKeyLookupUsesAdbDriverFastPath() throws Exception {
        AdbSqlDiagnosticsRegistry.clear();
        Class.forName(AdbDriver.class.getName());
        String databasePath = tempDir.resolve("adb-driver-point-lookup").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
                Assertions.assertEquals(3, statement.executeUpdate(
                        "INSERT INTO TEST(ID, NAME) VALUES (1, 'a'), (2, 'b'), (3, 'c')"));

                AdbSqlDiagnosticsRegistry.resetAll();
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT NAME FROM TEST WHERE ID = ?")) {
                    select.setLong(1, 2L);
                    try (ResultSet resultSet = select.executeQuery()) {
                        Assertions.assertTrue(resultSet.next());
                        Assertions.assertEquals("b", resultSet.getString(1));
                        Assertions.assertFalse(resultSet.next());
                    }
                }
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT NAME, NAME FROM TEST WHERE ID = ?")) {
                    select.setLong(1, 2L);
                    try (ResultSet resultSet = select.executeQuery()) {
                        Assertions.assertTrue(resultSet.next());
                        Assertions.assertEquals("b", resultSet.getString(1));
                        Assertions.assertEquals("b", resultSet.getString(2));
                        Assertions.assertFalse(resultSet.next());
                    }
                }
            }

            AdbSqlDiagnosticSnapshot snapshot = AdbSqlDiagnosticsRegistry
                    .get(AdbSqlDiagnosticsRegistry.scope(databasePath))
                    .snapshot();
            Assertions.assertTrue(snapshot.getOperationStats().containsKey(
                    "ADB_TABLE_POINT_LOOKUP_FAST TEST"), snapshot.getOperationStats().keySet().toString());
            Assertions.assertFalse(snapshot.getOperationStats().containsKey(
                    "ADB_TABLE_PRIMARY_FIND TEST"), snapshot.getOperationStats().keySet().toString());
        } finally {
            DbStoreEngine.close(databasePath);
            AdbSqlDiagnosticsRegistry.clear();
        }
    }

    @Test
    void rejectsDuplicatePrimaryKeyWithinOneMultiValuesInsert() throws Exception {
        String databasePath = tempDir.resolve("adb-multi-values-duplicate").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR) "
                        + "ENGINE \"adb_table\" WITH \"adb.sql.diagnostics=false\"");

                connection.setAutoCommit(false);
                Assertions.assertThrows(SQLException.class,
                        () -> statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES "
                                + "(1, 'a'), (2, 'b'), (2, 'duplicate')"));
                connection.rollback();
                Assertions.assertEquals(0L, countRows(statement));
            }
        } finally {
            DbStoreEngine.close(databasePath);
        }
    }

    @Test
    void canDisableSqlDiagnosticsThroughTableEngineParams() throws Exception {
        AdbSqlDiagnosticsRegistry.clear();
        String databasePath = tempDir.resolve("adb-sql-diagnostics-disabled").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR) "
                        + "ENGINE \"adb_table\" WITH \"adb.sql.diagnostics=false\"");
                statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (1, 'a')");
                Assertions.assertEquals("a", singleString(statement, "SELECT NAME FROM TEST WHERE ID = 1"));
            }

            Assertions.assertFalse(AdbSqlDiagnosticsRegistry.snapshotAll()
                    .containsKey(AdbSqlDiagnosticsRegistry.scope(databasePath)));
        } finally {
            DbStoreEngine.close(databasePath);
            AdbSqlDiagnosticsRegistry.clear();
        }
    }

    @Test
    void preparedPrimaryKeyRangeCountUsesAdbDriverFastPath() throws Exception {
        AdbSqlDiagnosticsRegistry.clear();
        Class.forName(AdbDriver.class.getName());
        String databasePath = tempDir.resolve("adb-driver-range-count").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
                Assertions.assertEquals(5, statement.executeUpdate(
                        "INSERT INTO TEST(ID, NAME) VALUES "
                                + "(1, 'a'), (2, 'b'), (3, 'c'), (4, 'd'), (5, 'e')"));

                AdbSqlDiagnosticsRegistry.resetAll();
                try (PreparedStatement count = connection.prepareStatement(
                        "SELECT COUNT(*) FROM TEST WHERE ID BETWEEN ? AND ?")) {
                    count.setLong(1, 2L);
                    count.setLong(2, 4L);
                    try (ResultSet resultSet = count.executeQuery()) {
                        Assertions.assertTrue(resultSet.next());
                        Assertions.assertEquals(3L, resultSet.getLong(1));
                        Assertions.assertFalse(resultSet.next());
                    }
                }
            }

            AdbSqlDiagnosticSnapshot snapshot = AdbSqlDiagnosticsRegistry
                    .get(AdbSqlDiagnosticsRegistry.scope(databasePath))
                    .snapshot();
            Assertions.assertTrue(snapshot.getOperationStats().containsKey(
                    "ADB_TABLE_RANGE_COUNT_FAST TEST"), snapshot.getOperationStats().keySet().toString());
            Assertions.assertFalse(snapshot.getOperationStats().containsKey(
                    "ADB_TABLE_PRIMARY_FIND TEST"), snapshot.getOperationStats().keySet().toString());
        } finally {
            DbStoreEngine.close(databasePath);
            AdbSqlDiagnosticsRegistry.clear();
        }
    }

    @Test
    void preparedNonPrimaryRangeCountFallsBackToH2Path() throws Exception {
        AdbSqlDiagnosticsRegistry.clear();
        Class.forName(AdbDriver.class.getName());
        String databasePath = tempDir.resolve("adb-driver-range-count-fallback").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
                statement.execute("CREATE INDEX IDX_TEST_NAME ON TEST(NAME)");
                Assertions.assertEquals(3, statement.executeUpdate(
                        "INSERT INTO TEST(ID, NAME) VALUES (1, 'a'), (2, 'b'), (3, 'c')"));

                AdbSqlDiagnosticsRegistry.resetAll();
                try (PreparedStatement count = connection.prepareStatement(
                        "SELECT COUNT(*) FROM TEST WHERE NAME BETWEEN ? AND ?")) {
                    count.setString(1, "a");
                    count.setString(2, "b");
                    try (ResultSet resultSet = count.executeQuery()) {
                        Assertions.assertTrue(resultSet.next());
                        Assertions.assertEquals(2L, resultSet.getLong(1));
                    }
                }
            }

            AdbSqlDiagnosticSnapshot snapshot = AdbSqlDiagnosticsRegistry
                    .get(AdbSqlDiagnosticsRegistry.scope(databasePath))
                    .snapshot();
            Assertions.assertFalse(snapshot.getOperationStats().containsKey(
                    "ADB_TABLE_RANGE_COUNT_FAST TEST"), snapshot.getOperationStats().keySet().toString());
        } finally {
            DbStoreEngine.close(databasePath);
            AdbSqlDiagnosticsRegistry.clear();
        }
    }

    @Test
    void rejectsDuplicatePrimaryKeyThroughBulkInsertPath() throws Exception {
        String databasePath = tempDir.resolve("adb-bulk-duplicate").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
                statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (1, 'a')");
                connection.commit();

                AdbTable table = adbTable(connection, "TEST");
                DbException error = Assertions.assertThrows(DbException.class,
                        () -> table.bulkInsertAppendRows(session(connection),
                                Collections.singletonList(row(1L, "duplicate"))));
                Assertions.assertTrue(error.getMessage().contains("primary key"), error.getMessage());
                connection.rollback();
            }
        } finally {
            DbStoreEngine.close(databasePath);
        }
    }

    @Test
    void bulkInsertsRowsAndSecondaryIndexEntries() throws Exception {
        String databasePath = tempDir.resolve("adb-bulk-secondary-index").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR, SCORE INT)");
                statement.execute("CREATE INDEX IDX_TEST_NAME ON TEST(NAME)");

                AdbTable table = adbTable(connection, "TEST");
                int inserted = table.bulkInsertAppendRows(session(connection), Arrays.asList(
                        row(1L, "a", 10),
                        row(2L, "b", 20),
                        row(3L, "b", 30)));
                connection.commit();

                Assertions.assertEquals(3, inserted);
                Assertions.assertEquals(3L, countRows(statement));
                Assertions.assertEquals(2L,
                        singleLong(statement, "SELECT COUNT(*) FROM TEST WHERE NAME = 'b'"));
                Assertions.assertEquals("b",
                        singleString(statement, "SELECT NAME FROM TEST WHERE ID = 2"));
            }
        } finally {
            DbStoreEngine.close(databasePath);
        }
    }

    @Test
    void rejectsDuplicateSecondaryUniqueKeyThroughBulkInsertPath() throws Exception {
        String databasePath = tempDir.resolve("adb-bulk-secondary-unique").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR, SCORE INT)");
                statement.execute("CREATE UNIQUE INDEX IDX_TEST_NAME ON TEST(NAME)");
                AdbTable table = adbTable(connection, "TEST");
                DbException error = Assertions.assertThrows(DbException.class,
                        () -> table.bulkInsertAppendRows(session(connection), Arrays.asList(
                                row(1L, "dup", 10),
                                row(2L, "dup", 20))));
                Assertions.assertTrue(error.getMessage().contains("Unique index"), error.getMessage());
                Assertions.assertEquals(0L, countRows(statement));
                connection.rollback();
            }
        } finally {
            DbStoreEngine.close(databasePath);
        }
    }

    @Test
    void rollsBackBulkInsertedSecondaryIndexEntries() throws Exception {
        String databasePath = tempDir.resolve("adb-bulk-secondary-rollback").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR, SCORE INT)");
                statement.execute("CREATE INDEX IDX_TEST_NAME ON TEST(NAME)");
                AdbTable table = adbTable(connection, "TEST");
                table.bulkInsertAppendRows(session(connection), Arrays.asList(
                        row(1L, "rollback", 10),
                        row(2L, "rollback", 20)));
                connection.rollback();

                Assertions.assertEquals(0L, countRows(statement));
                Assertions.assertEquals(0L,
                        singleLong(statement, "SELECT COUNT(*) FROM TEST WHERE NAME = 'rollback'"));
            }
        } finally {
            DbStoreEngine.close(databasePath);
        }
    }

    @Test
    void rejectsRegionCommitCoordinatorThroughBulkInsertPath() throws Exception {
        String databasePath = tempDir.resolve("adb-bulk-region-write").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR) "
                        + "ENGINE \"adb_table\" WITH "
                        + "\"adb.distributed.sql=true\", "
                        + "\"adb.distributed.write.client=raft\", "
                        + "\"adb.distributed.raft.group=group-1\", "
                        + "\"adb.distributed.raft.peers=n1@127.0.0.1:19001\"");
                connection.commit();

                AdbTable table = adbTable(connection, "TEST");
                DbException error = Assertions.assertThrows(DbException.class,
                        () -> table.bulkInsertAppendRows(session(connection),
                                Collections.singletonList(row(2L, "remote"))));
                Assertions.assertTrue(error.getMessage().contains("local-only"), error.getMessage());
                connection.rollback();
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
    void pointLookupCacheSeesCommittedUpdateAndDelete() throws Exception {
        String databasePath = tempDir.resolve("adb-point-cache").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
                statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (1, 'first')");
                Assertions.assertEquals("first",
                        singleString(statement, "SELECT NAME FROM TEST WHERE ID = 1"));
                Assertions.assertEquals("first",
                        singleString(statement, "SELECT NAME FROM TEST WHERE ID = 1"));

                statement.executeUpdate("UPDATE TEST SET NAME = 'second' WHERE ID = 1");
                Assertions.assertEquals("second",
                        singleString(statement, "SELECT NAME FROM TEST WHERE ID = 1"));

                statement.executeUpdate("DELETE FROM TEST WHERE ID = 1");
                Assertions.assertEquals(0L,
                        singleLong(statement, "SELECT COUNT(*) FROM TEST WHERE ID = 1"));
            }
        } finally {
            DbStoreEngine.close(databasePath);
        }
    }

    @Test
    void rangeCountSeesLocalDeleteAndRollback() throws Exception {
        String databasePath = tempDir.resolve("adb-range-local-delete").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
                statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (1, 'a'), (2, 'b'), (3, 'c')");

                connection.setAutoCommit(false);
                statement.executeUpdate("DELETE FROM TEST WHERE ID = 2");
                Assertions.assertEquals(2L,
                        singleLong(statement, "SELECT COUNT(*) FROM TEST WHERE ID BETWEEN 1 AND 3"));
                connection.rollback();

                Assertions.assertEquals(3L,
                        singleLong(statement, "SELECT COUNT(*) FROM TEST WHERE ID BETWEEN 1 AND 3"));
            }
        } finally {
            DbStoreEngine.close(databasePath);
        }
    }

    @Test
    void executesDistributedSqlPlanThroughJdbcWhenTableOptsIn() throws Exception {
        String databasePath = tempDir.resolve("adb-distributed-sql").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR) "
                        + "ENGINE \"adb_table\" WITH \"adb.distributed.sql=true\", \"adb.distributed.split.row=3\"");
                statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (1, 'a'), (2, 'b'), (3, 'c'), (4, 'd')");

                Assertions.assertEquals("b,c,d",
                        csv(statement, "SELECT NAME FROM TEST WHERE ID BETWEEN 2 AND 4 ORDER BY ID"));
                Assertions.assertEquals(3L,
                        singleLong(statement, "SELECT COUNT(*) FROM TEST WHERE ID BETWEEN 2 AND 4"));

                String explain = singleString(statement,
                        "EXPLAIN SELECT NAME FROM TEST WHERE ID BETWEEN 2 AND 4");
                Assertions.assertTrue(explain.contains("ADB_DISTRIBUTED_SCAN"), explain);
                Assertions.assertTrue(explain.contains("regions=2"), explain);
            }
        } finally {
            DbStoreEngine.close(databasePath);
        }
    }

    @Test
    void rejectsProductionDistributedTableWithoutSecureDefaults() throws Exception {
        String databasePath = tempDir.resolve("adb-production-reject").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                SQLException error = Assertions.assertThrows(SQLException.class,
                        () -> statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR) "
                                + "ENGINE \"adb_table\" WITH \"adb.production.mode=mvp-cluster\", "
                                + "\"adb.production.topology=2data1witness\", "
                                + "\"adb.distributed.sql=true\", \"adb.distributed.split.row=3\""));

                Assertions.assertTrue(error.getMessage().contains(
                        "mvp cluster requires TLS, auth and least privilege"), error.getMessage());
            }
        } finally {
            DbStoreEngine.close(databasePath);
        }
    }

    @Test
    void allowsProductionDistributedTableWithWitnessAndSecureDefaults() throws Exception {
        String databasePath = tempDir.resolve("adb-production-allow").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR) "
                        + "ENGINE \"adb_table\" WITH \"adb.production.mode=mvp-cluster\", "
                        + "\"adb.production.topology=2data1witness\", "
                        + "\"adb.security.tls.enabled=true\", "
                        + "\"adb.security.auth.enabled=true\", "
                        + "\"adb.security.leastPrivilege.enabled=true\", "
                        + "\"adb.distributed.sql=true\", \"adb.distributed.split.row=3\"");
                statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (1, 'a'), (4, 'd')");

                Assertions.assertEquals("a,d",
                        csv(statement, "SELECT NAME FROM TEST WHERE ID BETWEEN 1 AND 4 ORDER BY ID"));
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

    private static String csv(Statement statement, String sql) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                if (builder.length() > 0) {
                    builder.append(',');
                }
                builder.append(resultSet.getString(1));
            }
        }
        return builder.toString();
    }

    private static AdbTable adbTable(Connection connection, String tableName) throws Exception {
        SessionLocal session = session(connection);
        Schema schema = session.getDatabase().getSchema(session.getCurrentSchemaName());
        Table table = schema.findTableOrView(session, tableName);
        Assertions.assertTrue(table instanceof AdbTable, "expected AdbTable: " + table);
        return (AdbTable) table;
    }

    private static SessionLocal session(Connection connection) throws Exception {
        return (SessionLocal) connection.unwrap(JdbcConnection.class).getSession();
    }

    private static DefaultRow row(long id, String name) {
        DefaultRow row = new DefaultRow(new Value[]{ValueBigint.get(id), ValueVarchar.get(name)});
        row.setKey(id);
        return row;
    }

    private static DefaultRow row(long id, String name, int score) {
        DefaultRow row = new DefaultRow(new Value[]{
                ValueBigint.get(id),
                ValueVarchar.get(name),
                org.h2.value.ValueInteger.get(score)});
        row.setKey(id);
        return row;
    }
}
