package net.xdob.vexra.adb.h2plugin;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.xdob.vexra.adb.db.AdbSqlPhaseStats;
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
    void preparedSingleValuesInsertUsesAdbDriverBulkPath() throws Exception {
        AdbSqlDiagnosticsRegistry.clear();
        Class.forName(AdbDriver.class.getName());
        String databasePath = tempDir.resolve("adb-driver-prepared-single-bulk").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO TEST(ID, NAME) VALUES (?, ?)")) {
                    insert.setLong(1, 1L);
                    insert.setString(2, "a");
                    Assertions.assertEquals(1, insert.executeUpdate());
                }
                Assertions.assertEquals("a", singleString(statement,
                        "SELECT NAME FROM TEST WHERE ID = 1"));
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
    void repeatedPreparedSingleValuesInsertReusesBulkPlanMetadata() throws Exception {
        AdbSqlDiagnosticsRegistry.clear();
        Class.forName(AdbDriver.class.getName());
        String databasePath = tempDir.resolve("adb-driver-prepared-single-bulk-repeat").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO TEST(ID, NAME) VALUES (?, ?)")) {
                    for (long id = 1L; id <= 3L; id++) {
                        insert.setLong(1, id);
                        insert.setString(2, "n" + id);
                        Assertions.assertEquals(1, insert.executeUpdate());
                    }
                }
                Assertions.assertEquals("n1,n2,n3", csv(statement,
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
    void statementLiteralSingleValuesInsertUsesAdbDriverBulkPath() throws Exception {
        AdbSqlDiagnosticsRegistry.clear();
        Class.forName(AdbDriver.class.getName());
        String databasePath = tempDir.resolve("adb-driver-statement-single-bulk").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
                Assertions.assertEquals(1, statement.executeUpdate(
                        "INSERT INTO TEST(ID, NAME) VALUES (1, 'a')"));
                Assertions.assertEquals("a", singleString(statement,
                        "SELECT NAME FROM TEST WHERE ID = 1"));
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
    void repeatedPreparedPointLookupReusesPlanSession() throws Exception {
        AdbSqlDiagnosticsRegistry.clear();
        Class.forName(AdbDriver.class.getName());
        String databasePath = tempDir.resolve("adb-prepared-point-session-cache").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
                statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (1, 'a'), (2, 'b')");

                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT NAME FROM TEST WHERE ID = ?")) {
                    Assertions.assertEquals("a", preparedName(select, 1L));
                    Assertions.assertEquals("b", preparedName(select, 2L));
                }
            }

            AdbSqlDiagnosticSnapshot snapshot = AdbSqlDiagnosticsRegistry
                    .get(AdbSqlDiagnosticsRegistry.scope(databasePath))
                    .snapshot();
            Assertions.assertTrue(snapshot.getOperationStats().containsKey(
                    "ADB_TABLE_POINT_LOOKUP_FAST TEST"), snapshot.getOperationStats().keySet().toString());
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
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT * FROM TEST WHERE ID = ?")) {
                    select.setLong(1, 3L);
                    try (ResultSet resultSet = select.executeQuery()) {
                        Assertions.assertTrue(resultSet.next());
                        Assertions.assertEquals(3L, resultSet.getLong("ID"));
                        Assertions.assertEquals("c", resultSet.getString("NAME"));
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
            Assertions.assertTrue(snapshot.getPhaseStats().containsKey(
                    "ADB_RANGE_COUNT_VISIBLE_COUNT_RAW"), snapshot.getPhaseStats().keySet().toString());
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
    void tableCountUsesAdbDriverFastPathAndSeesLocalDelta() throws Exception {
        AdbSqlDiagnosticsRegistry.clear();
        Class.forName(AdbDriver.class.getName());
        String databasePath = tempDir.resolve("adb-driver-table-count").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
                Assertions.assertEquals(3, statement.executeUpdate(
                        "INSERT INTO TEST(ID, NAME) VALUES (1, 'a'), (2, 'b'), (3, 'c')"));

                AdbSqlDiagnosticsRegistry.resetAll();
                try (PreparedStatement count = connection.prepareStatement(
                        "SELECT COUNT(*) FROM TEST")) {
                    try (ResultSet resultSet = count.executeQuery()) {
                        Assertions.assertTrue(resultSet.next());
                        Assertions.assertEquals(3L, resultSet.getLong(1));
                        Assertions.assertFalse(resultSet.next());
                    }
                }

                connection.setAutoCommit(false);
                statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (4, 'd')");
                try (PreparedStatement count = connection.prepareStatement(
                        "SELECT COUNT(*) FROM TEST")) {
                    try (ResultSet resultSet = count.executeQuery()) {
                        Assertions.assertTrue(resultSet.next());
                        Assertions.assertEquals(4L, resultSet.getLong(1));
                    }
                }
                connection.rollback();
                connection.setAutoCommit(true);

                try (ResultSet resultSet = statement.executeQuery(
                        "SELECT COUNT(*) FROM TEST")) {
                    Assertions.assertTrue(resultSet.next());
                    Assertions.assertEquals(3L, resultSet.getLong(1));
                    Assertions.assertFalse(resultSet.next());
                }

                statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (4, 'committed')");
                try (PreparedStatement count = connection.prepareStatement(
                        "SELECT COUNT(*) FROM TEST")) {
                    try (ResultSet resultSet = count.executeQuery()) {
                        Assertions.assertTrue(resultSet.next());
                        Assertions.assertEquals(4L, resultSet.getLong(1));
                    }
                }
            }

            AdbSqlDiagnosticSnapshot snapshot = AdbSqlDiagnosticsRegistry
                    .get(AdbSqlDiagnosticsRegistry.scope(databasePath))
                    .snapshot();
            Assertions.assertTrue(snapshot.getOperationStats().containsKey(
                    "ADB_TABLE_TABLE_COUNT_FAST TEST"), snapshot.getOperationStats().keySet().toString());
        } finally {
            DbStoreEngine.close(databasePath);
            AdbSqlDiagnosticsRegistry.clear();
        }
    }

    @Test
    void concurrentTableCountLoadsBaseRowCountOnce() throws Exception {
        AdbSqlDiagnosticsRegistry.clear();
        Class.forName(AdbDriver.class.getName());
        String databasePath = tempDir.resolve("adb-concurrent-table-count").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
                statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES "
                        + "(1, 'a'), (2, 'b'), (3, 'c'), (4, 'd')");
            }

            AdbSqlDiagnosticsRegistry.resetAll();
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    Assertions.assertTrue(start.await(5, TimeUnit.SECONDS));
                    try (Connection connection = DriverManager.getConnection(url);
                         PreparedStatement count = connection.prepareStatement(
                                 "SELECT COUNT(*) FROM TEST")) {
                        try (ResultSet resultSet = count.executeQuery()) {
                            Assertions.assertTrue(resultSet.next());
                            return resultSet.getLong(1);
                        }
                    }
                }));
            }
            start.countDown();
            for (Future<Long> future : futures) {
                Assertions.assertEquals(4L, future.get(10, TimeUnit.SECONDS));
            }

            AdbSqlDiagnosticSnapshot snapshot = AdbSqlDiagnosticsRegistry
                    .get(AdbSqlDiagnosticsRegistry.scope(databasePath))
                    .snapshot();
            Assertions.assertTrue(phaseCount(snapshot, "ADB_ROW_COUNT_PREWARM") >= 1L,
                    snapshot.getPhaseStats().toString());
            Assertions.assertEquals(0L, phaseCount(snapshot, "ADB_ROW_COUNT_CACHE_MISS"),
                    snapshot.getPhaseStats().toString());
            Assertions.assertTrue(
                    phaseCount(snapshot, "ADB_ROW_COUNT_CACHE_HIT") >= 8L,
                    snapshot.getPhaseStats().toString());
        } finally {
            executor.shutdownNow();
            DbStoreEngine.close(databasePath);
            AdbSqlDiagnosticsRegistry.clear();
        }
    }

    @Test
    void rowCountBaseSnapshotCompactsDeltaScanAfterReopen() throws Exception {
        String previousThreshold = System.getProperty(
                "vexra.adb.rowCount.compactDeltaThreshold");
        System.setProperty("vexra.adb.rowCount.compactDeltaThreshold", "2");
        AdbSqlDiagnosticsRegistry.clear();
        String databasePath = tempDir.resolve("adb-row-count-compact").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
                statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (1, 'a')");
                statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (2, 'b')");
                statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (3, 'c')");
            }

            DbStoreEngine.close(databasePath);
            AdbSqlDiagnosticsRegistry.resetAll();
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                Assertions.assertEquals(3L, countRows(statement));
            }
            AdbSqlDiagnosticSnapshot first = AdbSqlDiagnosticsRegistry
                    .get(AdbSqlDiagnosticsRegistry.scope(databasePath))
                    .snapshot();
            Assertions.assertEquals(1L,
                    phaseCount(first, "ADB_ROW_COUNT_BASE_COMPACT"),
                    first.getPhaseStats().toString());

            DbStoreEngine.close(databasePath);
            AdbSqlDiagnosticsRegistry.resetAll();
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                Assertions.assertEquals(3L, countRows(statement));
            }
            AdbSqlDiagnosticSnapshot second = AdbSqlDiagnosticsRegistry
                    .get(AdbSqlDiagnosticsRegistry.scope(databasePath))
                    .snapshot();
            Assertions.assertEquals(0L,
                    phaseCount(second, "ADB_ROW_COUNT_BASE_COMPACT"),
                    second.getPhaseStats().toString());
        } finally {
            DbStoreEngine.close(databasePath);
            AdbSqlDiagnosticsRegistry.clear();
            if (previousThreshold == null) {
                System.clearProperty("vexra.adb.rowCount.compactDeltaThreshold");
            } else {
                System.setProperty("vexra.adb.rowCount.compactDeltaThreshold",
                        previousThreshold);
            }
        }
    }

    @Test
    void rowCountCachePrewarmsAfterReopen() throws Exception {
        AdbSqlDiagnosticsRegistry.clear();
        String databasePath = tempDir.resolve("adb-row-count-prewarm").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
                statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES "
                        + "(1, 'a'), (2, 'b'), (3, 'c')");
            }

            DbStoreEngine.close(databasePath);
            AdbSqlDiagnosticsRegistry.resetAll();
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                Assertions.assertEquals(3L, countRows(statement));
            }

            AdbSqlDiagnosticSnapshot snapshot = AdbSqlDiagnosticsRegistry
                    .get(AdbSqlDiagnosticsRegistry.scope(databasePath))
                    .snapshot();
            Assertions.assertTrue(phaseCount(snapshot, "ADB_ROW_COUNT_PREWARM") >= 1L,
                    snapshot.getPhaseStats().toString());
            Assertions.assertTrue(phaseCount(snapshot, "ADB_ROW_COUNT_CACHE_HIT") >= 1L,
                    snapshot.getPhaseStats().toString());
            Assertions.assertEquals(0L, phaseCount(snapshot, "ADB_ROW_COUNT_CACHE_MISS"),
                    snapshot.getPhaseStats().toString());
        } finally {
            DbStoreEngine.close(databasePath);
            AdbSqlDiagnosticsRegistry.clear();
        }
    }

    @Test
    void rowCountCachePrewarmCanBeDisabled() throws Exception {
        String previous = System.getProperty("vexra.adb.rowCount.prewarm");
        System.setProperty("vexra.adb.rowCount.prewarm", "false");
        AdbSqlDiagnosticsRegistry.clear();
        String databasePath = tempDir.resolve("adb-row-count-prewarm-disabled").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
                statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (1, 'a')");
            }

            DbStoreEngine.close(databasePath);
            AdbSqlDiagnosticsRegistry.resetAll();
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                Assertions.assertEquals(1L, countRows(statement));
            }

            AdbSqlDiagnosticSnapshot snapshot = AdbSqlDiagnosticsRegistry
                    .get(AdbSqlDiagnosticsRegistry.scope(databasePath))
                    .snapshot();
            Assertions.assertEquals(0L, phaseCount(snapshot, "ADB_ROW_COUNT_PREWARM"),
                    snapshot.getPhaseStats().toString());
            Assertions.assertEquals(1L, phaseCount(snapshot, "ADB_ROW_COUNT_CACHE_MISS"),
                    snapshot.getPhaseStats().toString());
        } finally {
            DbStoreEngine.close(databasePath);
            AdbSqlDiagnosticsRegistry.clear();
            if (previous == null) {
                System.clearProperty("vexra.adb.rowCount.prewarm");
            } else {
                System.setProperty("vexra.adb.rowCount.prewarm", previous);
            }
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
    void rollsBackEarlierBulkRowsWhenSameBatchContainsDuplicatePrimaryKey() throws Exception {
        String databasePath = tempDir.resolve("adb-bulk-same-batch-duplicate").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");

                AdbTable table = adbTable(connection, "TEST");
                DbException error = Assertions.assertThrows(DbException.class,
                        () -> table.bulkInsertAppendRows(session(connection), Arrays.asList(
                                row(1L, "first"),
                                row(2L, "second"),
                                row(1L, "duplicate"))));
                Assertions.assertTrue(error.getMessage().contains("primary key"), error.getMessage());
                Assertions.assertEquals(0L, countRows(statement));
                connection.rollback();
            }
        } finally {
            DbStoreEngine.close(databasePath);
        }
    }

    @Test
    void acceptsNonMonotonicUniqueBulkPrimaryKeys() throws Exception {
        String databasePath = tempDir.resolve("adb-bulk-non-monotonic-unique").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");

                AdbTable table = adbTable(connection, "TEST");
                Assertions.assertEquals(3, table.bulkInsertAppendRows(session(connection), Arrays.asList(
                        row(3L, "third"),
                        row(1L, "first"),
                        row(2L, "second"))));
                Assertions.assertEquals(3L, countRows(statement));
                connection.commit();
                Assertions.assertEquals("first,second,third",
                        csv(statement, "SELECT NAME FROM TEST ORDER BY ID"));
            }
        } finally {
            DbStoreEngine.close(databasePath);
        }
    }

    @Test
    void rejectsDuplicatePrimaryKeyAcrossBulkBatchesInOneTransaction() throws Exception {
        String databasePath = tempDir.resolve("adb-bulk-cross-batch-duplicate").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");

                AdbTable table = adbTable(connection, "TEST");
                Assertions.assertEquals(2, table.bulkInsertAppendRows(session(connection), Arrays.asList(
                        row(1L, "first"),
                        row(2L, "second"))));
                DbException error = Assertions.assertThrows(DbException.class,
                        () -> table.bulkInsertAppendRows(session(connection), Arrays.asList(
                                row(2L, "duplicate"),
                                row(3L, "third"))));
                Assertions.assertTrue(error.getMessage().contains("primary key"), error.getMessage());
                Assertions.assertEquals(2L, countRows(statement));
                connection.commit();
                Assertions.assertEquals("first,second",
                        csv(statement, "SELECT NAME FROM TEST ORDER BY ID"));
            }
        } finally {
            DbStoreEngine.close(databasePath);
        }
    }

    @Test
    void appendsMultipleBulkBatchesInOneTransaction() throws Exception {
        String databasePath = tempDir.resolve("adb-bulk-cross-batch-append").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");

                AdbTable table = adbTable(connection, "TEST");
                Assertions.assertEquals(2, table.bulkInsertAppendRows(session(connection), Arrays.asList(
                        row(10L, "a"),
                        row(11L, "b"))));
                Assertions.assertEquals(2, table.bulkInsertAppendRows(session(connection), Arrays.asList(
                        row(12L, "c"),
                        row(13L, "d"))));
                Assertions.assertEquals(4L, countRows(statement));
                connection.commit();
                Assertions.assertEquals("a,b,c,d",
                        csv(statement, "SELECT NAME FROM TEST ORDER BY ID"));
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
        System.setProperty("vexra.adb.sql.diagnostic.detail", "true");
        AdbSqlDiagnosticsRegistry.clear();
        String databasePath = tempDir.resolve("adb-point-cache").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = new org.h2.Driver().connect(url, new Properties());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
                statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (1, 'first')");
                AdbSqlDiagnosticsRegistry.resetAll();
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

            AdbSqlDiagnosticSnapshot snapshot = AdbSqlDiagnosticsRegistry
                    .get(AdbSqlDiagnosticsRegistry.scope(databasePath))
                    .snapshot();
            Assertions.assertTrue(snapshot.getPhaseStats().containsKey(
                    "ADB_PRIMARY_FIND_VISIBLE_ROW"), snapshot.getPhaseStats().keySet().toString());
            Assertions.assertTrue(snapshot.getPhaseStats().containsKey(
                    "ADB_PRIMARY_FIND_ROW_CACHE_MISS"), snapshot.getPhaseStats().keySet().toString());
            Assertions.assertTrue(snapshot.getPhaseStats().containsKey(
                    "ADB_PRIMARY_FIND_ROW_CACHE_HIT"), snapshot.getPhaseStats().keySet().toString());
            Assertions.assertTrue(snapshot.getPhaseStats().containsKey(
                    "ADB_PRIMARY_FIND_COST"), snapshot.getPhaseStats().keySet().toString());
            Assertions.assertTrue(snapshot.getPhaseStats().containsKey(
                    "ADB_PRIMARY_FIND_ROW_DECODE"), snapshot.getPhaseStats().keySet().toString());
            Assertions.assertTrue(snapshot.getPhaseStats().containsKey(
                    "ADB_PRIMARY_FIND_ROW_BUILD"), snapshot.getPhaseStats().keySet().toString());
        } finally {
            DbStoreEngine.close(databasePath);
            AdbSqlDiagnosticsRegistry.clear();
            System.clearProperty("vexra.adb.sql.diagnostic.detail");
        }
    }

    @Test
    void preparedPointLookupDecodeCacheSeesCommittedUpdateAndDelete() throws Exception {
        System.setProperty("vexra.adb.sql.diagnostic.detail", "true");
        AdbSqlDiagnosticsRegistry.clear();
        Class.forName(AdbDriver.class.getName());
        String databasePath = tempDir.resolve("adb-prepared-point-cache").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
                statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (1, 'first')");
                AdbSqlDiagnosticsRegistry.resetAll();

                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT NAME FROM TEST WHERE ID = ?")) {
                    Assertions.assertEquals("first", preparedName(select, 1L));
                    Assertions.assertEquals("first", preparedName(select, 1L));

                    statement.executeUpdate("UPDATE TEST SET NAME = 'second' WHERE ID = 1");
                    Assertions.assertEquals("second", preparedName(select, 1L));

                    statement.executeUpdate("DELETE FROM TEST WHERE ID = 1");
                    Assertions.assertEquals(null, preparedName(select, 1L));
                }
            }

            AdbSqlDiagnosticSnapshot snapshot = AdbSqlDiagnosticsRegistry
                    .get(AdbSqlDiagnosticsRegistry.scope(databasePath))
                    .snapshot();
            Assertions.assertTrue(snapshot.getPhaseStats().containsKey(
                    "ADB_POINT_LOOKUP_DECODE_CACHE_HIT"), snapshot.getPhaseStats().keySet().toString());
            Assertions.assertTrue(snapshot.getPhaseStats().containsKey(
                    "ADB_POINT_LOOKUP_VISIBLE_ROW"), snapshot.getPhaseStats().keySet().toString());
            Assertions.assertTrue(snapshot.getPhaseStats().containsKey(
                    "ADB_POINT_LOOKUP_RESULT_BUILD"), snapshot.getPhaseStats().keySet().toString());
            Assertions.assertTrue(snapshot.getOperationStats().containsKey(
                    "ADB_TABLE_POINT_LOOKUP_FAST TEST"), snapshot.getOperationStats().keySet().toString());
        } finally {
            DbStoreEngine.close(databasePath);
            AdbSqlDiagnosticsRegistry.clear();
            System.clearProperty("vexra.adb.sql.diagnostic.detail");
        }
    }

    @Test
    void preparedPointLookupRecordsVisibleRowDiagnosticBreakdown() throws Exception {
        System.setProperty("vexra.adb.sql.diagnostic.detail", "true");
        AdbSqlDiagnosticsRegistry.clear();
        Class.forName(AdbDriver.class.getName());
        String databasePath = tempDir.resolve("adb-visible-row-breakdown").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
                statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (1, 'committed')");
            }
            DbStoreEngine.close(databasePath);

            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement();
                 PreparedStatement select = connection.prepareStatement(
                         "SELECT NAME FROM TEST WHERE ID = ?")) {
                AdbSqlDiagnosticsRegistry.resetAll();
                Assertions.assertEquals("committed", preparedName(select, 1L));
                Assertions.assertEquals("committed", preparedName(select, 1L));

                connection.setAutoCommit(false);
                statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (2, 'local')");
                Assertions.assertEquals("local", preparedName(select, 2L));
                connection.rollback();
            }

            AdbSqlDiagnosticSnapshot snapshot = AdbSqlDiagnosticsRegistry
                    .get(AdbSqlDiagnosticsRegistry.scope(databasePath))
                    .snapshot();
            Assertions.assertTrue(snapshot.getPhaseStats().containsKey(
                    "ADB_VISIBLE_LOCAL_WRITE_CHECK"), snapshot.getPhaseStats().keySet().toString());
            Assertions.assertTrue(snapshot.getPhaseStats().containsKey(
                    "ADB_VISIBLE_LOCAL_WRITE_MISS"), snapshot.getPhaseStats().keySet().toString());
            Assertions.assertTrue(snapshot.getPhaseStats().containsKey(
                    "ADB_VISIBLE_LOCAL_WRITE_HIT"), snapshot.getPhaseStats().keySet().toString());
            Assertions.assertTrue(snapshot.getPhaseStats().containsKey(
                    "ADB_VISIBLE_ROUTE_POINT_READ"), snapshot.getPhaseStats().keySet().toString());
            Assertions.assertTrue(snapshot.getPhaseStats().containsKey(
                    "ADB_VISIBLE_COMMITTED_CACHE_MISS"), snapshot.getPhaseStats().keySet().toString());
            Assertions.assertTrue(snapshot.getPhaseStats().containsKey(
                    "ADB_VISIBLE_COMMITTED_CACHE_HIT"), snapshot.getPhaseStats().keySet().toString());
            Assertions.assertTrue(snapshot.getPhaseStats().containsKey(
                    "ADB_VISIBLE_COMMITTED_CACHE_VALIDATE"), snapshot.getPhaseStats().keySet().toString());
            Assertions.assertTrue(snapshot.getPhaseStats().containsKey(
                    "ADB_VISIBLE_COMMITTED_STORE_SCAN"), snapshot.getPhaseStats().keySet().toString());
            Assertions.assertTrue(snapshot.getPhaseStats().containsKey(
                    "ADB_VISIBLE_STORE_SEEK"), snapshot.getPhaseStats().keySet().toString());
            Assertions.assertTrue(snapshot.getPhaseStats().containsKey(
                    "ADB_VISIBLE_VERSION_KEY_DECODE"), snapshot.getPhaseStats().keySet().toString());
            Assertions.assertTrue(snapshot.getPhaseStats().containsKey(
                    "ADB_VISIBLE_ROW_VALUE_DECODE"), snapshot.getPhaseStats().keySet().toString());
            Assertions.assertTrue(snapshot.getPhaseStats().containsKey(
                    "ADB_VISIBLE_READ_SET_RECORD"), snapshot.getPhaseStats().keySet().toString());
        } finally {
            DbStoreEngine.close(databasePath);
            AdbSqlDiagnosticsRegistry.clear();
            System.clearProperty("vexra.adb.sql.diagnostic.detail");
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
    void preparedRangeCountSeesLocalInsertDeleteAndRollback() throws Exception {
        AdbSqlDiagnosticsRegistry.clear();
        Class.forName(AdbDriver.class.getName());
        String databasePath = tempDir.resolve("adb-prepared-range-local-write").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
                statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (1, 'a'), (2, 'b'), (3, 'c')");

                connection.setAutoCommit(false);
                statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (4, 'd')");
                Assertions.assertEquals(4L, preparedRangeCount(connection, 1L, 4L));

                statement.executeUpdate("DELETE FROM TEST WHERE ID = 2");
                Assertions.assertEquals(3L, preparedRangeCount(connection, 1L, 4L));

                connection.rollback();
                connection.setAutoCommit(true);
                Assertions.assertEquals(3L, preparedRangeCount(connection, 1L, 4L));
            }

            AdbSqlDiagnosticSnapshot snapshot = AdbSqlDiagnosticsRegistry
                    .get(AdbSqlDiagnosticsRegistry.scope(databasePath))
                    .snapshot();
            Assertions.assertTrue(snapshot.getPhaseStats().containsKey(
                    "ADB_RANGE_COUNT_VISIBLE_COUNT"), snapshot.getPhaseStats().keySet().toString());
            Assertions.assertTrue(snapshot.getOperationStats().containsKey(
                    "ADB_TABLE_RANGE_COUNT_FAST TEST"), snapshot.getOperationStats().keySet().toString());
        } finally {
            DbStoreEngine.close(databasePath);
            AdbSqlDiagnosticsRegistry.clear();
        }
    }

    @Test
    void repeatedPreparedRangeCountReusesPlanSession() throws Exception {
        AdbSqlDiagnosticsRegistry.clear();
        Class.forName(AdbDriver.class.getName());
        String databasePath = tempDir.resolve("adb-prepared-range-session-cache").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
                statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (1, 'a'), (2, 'b'), (3, 'c')");

                try (PreparedStatement count = connection.prepareStatement(
                        "SELECT COUNT(*) FROM TEST WHERE ID BETWEEN ? AND ?")) {
                    count.setLong(1, 1L);
                    count.setLong(2, 2L);
                    Assertions.assertEquals(2L, singleLong(count));

                    count.setLong(1, 2L);
                    count.setLong(2, 3L);
                    Assertions.assertEquals(2L, singleLong(count));
                }
            }

            AdbSqlDiagnosticSnapshot snapshot = AdbSqlDiagnosticsRegistry
                    .get(AdbSqlDiagnosticsRegistry.scope(databasePath))
                    .snapshot();
            Assertions.assertTrue(snapshot.getOperationStats().containsKey(
                    "ADB_TABLE_RANGE_COUNT_FAST TEST"), snapshot.getOperationStats().keySet().toString());
        } finally {
            DbStoreEngine.close(databasePath);
            AdbSqlDiagnosticsRegistry.clear();
        }
    }

    @Test
    void repeatedPreparedTableCountReusesPlanSession() throws Exception {
        AdbSqlDiagnosticsRegistry.clear();
        Class.forName(AdbDriver.class.getName());
        String databasePath = tempDir.resolve("adb-prepared-table-count-session-cache").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:adb:ldb:" + databasePath + ";DB_CLOSE_DELAY=0";
        try {
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE TEST(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
                statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (1, 'a'), (2, 'b')");

                try (PreparedStatement count = connection.prepareStatement(
                        "SELECT COUNT(*) FROM TEST")) {
                    Assertions.assertEquals(2L, singleLong(count));
                    statement.executeUpdate("INSERT INTO TEST(ID, NAME) VALUES (3, 'c')");
                    Assertions.assertEquals(3L, singleLong(count));
                }
            }

            AdbSqlDiagnosticSnapshot snapshot = AdbSqlDiagnosticsRegistry
                    .get(AdbSqlDiagnosticsRegistry.scope(databasePath))
                    .snapshot();
            Assertions.assertTrue(snapshot.getOperationStats().containsKey(
                    "ADB_TABLE_TABLE_COUNT_FAST TEST"), snapshot.getOperationStats().keySet().toString());
        } finally {
            DbStoreEngine.close(databasePath);
            AdbSqlDiagnosticsRegistry.clear();
        }
    }

    private static String preparedName(PreparedStatement select, long id)
            throws SQLException {
        select.setLong(1, id);
        try (ResultSet resultSet = select.executeQuery()) {
            if (!resultSet.next()) {
                return null;
            }
            String value = resultSet.getString(1);
            Assertions.assertFalse(resultSet.next());
            return value;
        }
    }

    private static long singleLong(PreparedStatement statement) throws Exception {
        try (ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            long value = resultSet.getLong(1);
            Assertions.assertFalse(resultSet.next());
            return value;
        }
    }

    private static long preparedRangeCount(Connection connection, long min,
            long max) throws SQLException {
        try (PreparedStatement count = connection.prepareStatement(
                "SELECT COUNT(*) FROM TEST WHERE ID BETWEEN ? AND ?")) {
            count.setLong(1, min);
            count.setLong(2, max);
            try (ResultSet resultSet = count.executeQuery()) {
                Assertions.assertTrue(resultSet.next());
                Assertions.assertEquals(1, resultSet.findColumn("COUNT(*)"));
                long value = resultSet.getLong(1);
                Assertions.assertEquals(value, resultSet.getLong("COUNT(*)"));
                Assertions.assertEquals(String.valueOf(value), resultSet.getString(1));
                Assertions.assertFalse(resultSet.next());
                return value;
            }
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

    private static long phaseCount(AdbSqlDiagnosticSnapshot snapshot, String phase) {
        AdbSqlPhaseStats stats = snapshot.getPhaseStats().get(phase);
        return stats == null ? 0L : stats.getCount();
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
