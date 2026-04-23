//package net.xdob.vexra.ldb;
//
//import net.xdob.vexra.ldb.impl.LDbImpl;
//import net.xdob.vexra.ldb.util.Slices;
//import org.junit.jupiter.api.AfterEach;
//import org.junit.jupiter.api.Test;
//
//import java.io.File;
//import java.io.IOException;
//import java.nio.charset.StandardCharsets;
//import java.util.Map;
//import java.util.Random;
//import java.util.TreeMap;
//
//import static org.junit.jupiter.api.Assertions.*;
//
//public class LdbRestartReliabilityTest {
//
//  private static final Random RANDOM = new Random(1);
//
//  private final File dbDir = new File("build/tmp/ldb-reliability-test");
//
//  @AfterEach
//  public void tearDown() throws Exception {
//    deleteRecursively(dbDir);
//  }
//
//  @Test
//  public void testAddLongAcrossRestart() throws Exception {
//    deleteRecursively(dbDir);
//
//    try (LDB db = openDb(dbDir, true)) {
//      db.addLong(bytes("seq"), 1L);
//    }
//
//    try (LDB db = openDb(dbDir, false)) {
//      assertEquals(1L, Slices.decodeLong(db.get(bytes("seq")))
//          .orElse(null));
//      db.addLong(bytes("seq"), 2L);
//    }
//
//    try (LDB db = openDb(dbDir, false)) {
//      assertEquals(3L, Slices.decodeLong(db.get(bytes("seq")))
//          .orElse(null));
//    }
//  }
//
//  @Test
//  public void testPutThenRestart() throws Exception {
//    deleteRecursively(dbDir);
//
//    TreeMap<String, String> expected = new TreeMap<>();
//
//    try (LDB db = openDb(dbDir, true)) {
//      for (int i = 0; i < 1000; i++) {
//        String key = "k" + i;
//        String value = "v" + i;
//        db.put(bytes(key), bytes(value));
//        expected.put(key, value);
//      }
//    }
//
//    try (LDB db = openDb(dbDir, false)) {
//      verifyAll(db, expected);
//    }
//  }
//
//  @Test
//  public void testOverwriteThenRestart() throws Exception {
//    deleteRecursively(dbDir);
//
//    try (LDB db = openDb(dbDir, true)) {
//      db.put(bytes("name"), bytes("v1"));
//    }
//
//    try (LDB db = openDb(dbDir, false)) {
//      assertValue(db, "name", "v1");
//      db.put(bytes("name"), bytes("v2"));
//    }
//
//    try (LDB db = openDb(dbDir, false)) {
//      assertValue(db, "name", "v2");
//    }
//  }
//
//  @Test
//  public void testDeleteThenRestart() throws Exception {
//    deleteRecursively(dbDir);
//
//    try (LDB db = openDb(dbDir, true)) {
//      db.put(bytes("k1"), bytes("v1"));
//      db.put(bytes("k2"), bytes("v2"));
//      db.delete(bytes("k1"));
//    }
//
//    try (LDB db = openDb(dbDir, false)) {
//      assertNull(db.get(bytes("k1")));
//      assertValue(db, "k2", "v2");
//    }
//  }
//
//  @Test
//  public void testScanMatchesExpectedAfterRestart() throws Exception {
//    deleteRecursively(dbDir);
//
//    TreeMap<String, String> expected = new TreeMap<>();
//
//    try (LDB db = openDb(dbDir, true)) {
//      for (int i = 0; i < 500; i++) {
//        String key = String.format("k%04d", i);
//        String value = "v" + i;
//        db.put(bytes(key), bytes(value));
//        expected.put(key, value);
//      }
//
//      for (int i = 100; i < 200; i++) {
//        String key = String.format("k%04d", i);
//        db.delete(bytes(key));
//        expected.remove(key);
//      }
//
//      for (int i = 300; i < 350; i++) {
//        String key = String.format("k%04d", i);
//        String value = "overwrite-" + i;
//        db.put(bytes(key), bytes(value));
//        expected.put(key, value);
//      }
//    }
//
//    try (LDB db = openDb(dbDir, false)) {
//      verifyAll(db, expected);
//
//      TreeMap<String, String> scanned = scanAll(db);
//      assertEquals(expected, scanned);
//    }
//  }
//
//  @Test
//  public void testManyRestartCycles() throws Exception {
//    deleteRecursively(dbDir);
//
//    TreeMap<String, String> expected = new TreeMap<>();
//
//    try (LDB db = openDb(dbDir, true)) {
//      assertTrue(scanAll(db).isEmpty());
//    }
//
//    for (int round = 0; round < 10; round++) {
//      try (LDB db = openDb(dbDir, false)) {
//        for (int i = 0; i < 1000; i++) {
//          int op = RANDOM.nextInt(3);
//          int id = RANDOM.nextInt(3000);
//          String key = "rk" + id;
//
//          switch (op) {
//            case 0: {
//              String value = randomValue(id);
//              db.put(bytes(key), bytes(value));
//              expected.put(key, value);
//              break;
//            }
//            case 1: {
//              db.delete(bytes(key));
//              expected.remove(key);
//              break;
//            }
//            default: {
//              byte[] actual = db.get(bytes(key));
//              String expectedValue = expected.get(key);
//              if (expectedValue == null) {
//                assertNull(actual, "key=" + key);
//              } else {
//                assertArrayEquals(bytes(expectedValue), actual, "key=" + key);
//              }
//            }
//          }
//        }
//      }
//
//      try (LDB db = openDb(dbDir, false)) {
//        verifyAll(db, expected);
//        assertEquals(expected, scanAll(db), "round=" + round);
//      }
//    }
//  }
//
//  @Test
//  public void testWriteBatchThenRestart() throws Exception {
//    deleteRecursively(dbDir);
//
//    TreeMap<String, String> expected = new TreeMap<>();
//
//    try (LDB db = openDb(dbDir, true)) {
//      LdbWriteBatch batch = db.createWriteBatch();
//      for (int i = 0; i < 200; i++) {
//        String key = "bk" + i;
//        String value = "bv" + i;
//        batch.put(bytes(key), bytes(value));
//        expected.put(key, value);
//      }
//      db.write(batch);
//    }
//
//    try (LDB db = openDb(dbDir, false)) {
//      verifyAll(db, expected);
//    }
//  }
//
//  @Test
//  public void testLargeValuesThenRestart() throws Exception {
//    deleteRecursively(dbDir);
//
//    TreeMap<String, String> expected = new TreeMap<>();
//
//    try (LDB db = openDb(dbDir, true)) {
//      for (int i = 0; i < 50; i++) {
//        String key = "large-" + i;
//        String value = repeat("value-" + i + "-", 1000);
//        db.put(bytes(key), bytes(value));
//        expected.put(key, value);
//      }
//    }
//
//    try (LDB db = openDb(dbDir, false)) {
//      verifyAll(db, expected);
//    }
//  }
//
//  @Test
//  public void testColumnFamilyThenRestart() throws Exception {
//    deleteRecursively(dbDir);
//
//    TreeMap<String, String> expectedDefault = new TreeMap<>();
//    TreeMap<String, String> expectedMeta = new TreeMap<>();
//
//    try (LDB db = openDbWithColumnFamilies(dbDir, true)) {
//      LdbColumnFamily meta = requireColumnFamily(db, 2);
//
//      db.put(bytes("d1"), bytes("dv1"));
//      expectedDefault.put("d1", "dv1");
//
//      db.put(meta, bytes("m1"), bytes("mv1"));
//      db.put(meta, bytes("m2"), bytes("mv2"));
//      expectedMeta.put("m1", "mv1");
//      expectedMeta.put("m2", "mv2");
//    }
//
//    try (LDB db = openDbWithColumnFamilies(dbDir, false)) {
//      LdbColumnFamily meta = requireColumnFamily(db, 2);
//
//      verifyAll(db, expectedDefault);
//      verifyAll(db, meta, expectedMeta);
//
//      assertEquals(expectedMeta, scanAll(db, meta));
//    }
//  }
//
//  private void verifyAll(LDB db, TreeMap<String, String> expected) {
//    for (Map.Entry<String, String> e : expected.entrySet()) {
//      assertArrayEquals(bytes(e.getValue()), db.get(bytes(e.getKey())), "key=" + e.getKey());
//    }
//
//    for (int i = 0; i < 50; i++) {
//      assertNull(db.get(bytes("missing-" + i)));
//    }
//  }
//
//  private void verifyAll(LDB db, LdbColumnFamily cf, TreeMap<String, String> expected) {
//    for (Map.Entry<String, String> e : expected.entrySet()) {
//      assertArrayEquals(bytes(e.getValue()), db.get(cf, bytes(e.getKey())), "cf key=" + e.getKey());
//    }
//
//    for (int i = 0; i < 50; i++) {
//      assertNull(db.get(cf, bytes("missing-" + i)));
//    }
//  }
//
//  private void assertValue(LDB db, String key, String expected) {
//    assertArrayEquals(bytes(expected), db.get(bytes(key)), "key=" + key);
//  }
//
//  private TreeMap<String, String> scanAll(LDB db) throws DBException {
//    TreeMap<String, String> result = new TreeMap<>();
//    try (SnapshotCursor cursor = db.newSnapshotCursor()) {
//      cursor.seekToFirst();
//      while (cursor.isValid()) {
//        result.put(str(cursor.key()), str(cursor.value()));
//        cursor.next();
//      }
//    } catch (Exception e) {
//      throw new RuntimeException(e);
//    }
//    return result;
//  }
//
//  private TreeMap<String, String> scanAll(LDB db, LdbColumnFamily cf) throws DBException {
//    TreeMap<String, String> result = new TreeMap<>();
//    try (SnapshotCursor cursor = db.newSnapshotCursor(cf)) {
//      cursor.seekToFirst();
//      while (cursor.isValid()) {
//        result.put(str(cursor.key()), str(cursor.value()));
//        cursor.next();
//      }
//    } catch (Exception e) {
//      throw new RuntimeException(e);
//    }
//    return result;
//  }
//
//  private LdbColumnFamily requireColumnFamily(LDB db, int cfId) {
//    LdbColumnFamily cf = db.getColumnFamily(cfId);
//    if (cf == null) {
//      throw new IllegalStateException("ColumnFamily not found, cfId=" + cfId);
//    }
//    return cf;
//  }
//
//  private static String repeat(String s, int times) {
//    StringBuilder sb = new StringBuilder(s.length() * times);
//    for (int i = 0; i < times; i++) {
//      sb.append(s);
//    }
//    return sb.toString();
//  }
//
//  private static String randomValue(int id) {
//    StringBuilder sb = new StringBuilder();
//    sb.append("value-").append(id).append('-');
//    int len = 20 + RANDOM.nextInt(200);
//    for (int i = 0; i < len; i++) {
//      sb.append((char) ('a' + RANDOM.nextInt(26)));
//    }
//    return sb.toString();
//  }
//
//  private static byte[] bytes(String s) {
//    return s.getBytes(StandardCharsets.UTF_8);
//  }
//
//  private static String str(byte[] bytes) {
//    return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
//  }
//
//  private static void deleteRecursively(File file) throws IOException {
//    if (!file.exists()) {
//      return;
//    }
//    if (file.isDirectory()) {
//      File[] files = file.listFiles();
//      if (files != null) {
//        for (File f : files) {
//          deleteRecursively(f);
//        }
//      }
//    }
//    if (!file.delete()) {
//      throw new IOException("Failed to delete " + file);
//    }
//  }
//
//  /**
//   * 这里改成你项目里的真实打开方式。
//   */
//  private LDB openDb(File dir, boolean createIfMissing) throws Exception {
//    Options options = new Options()
//        .createIfMissing(true)
//        .errorIfExists(false)
//        .verifyChecksums(true)
//        .paranoidChecks(true)
//        .cacheSize(0)
//        .maxOpenFiles(1000)
//        .blockSize(4096)
//        .blockRestartInterval(16)
//        .compressionType(CompressionType.LZ4);
//
//    LDB ldb = new LDbImpl(options, dir);
//    return ldb;
//  }
//
//  /**
//   * 如果你的 ColumnFamily 需要在 Options 里显式声明，就在这里配。
//   * 如果默认 openDb 就已经包含多个 CF，也可以直接 return openDb(dir, createIfMissing)。
//   */
//  private LDB openDbWithColumnFamilies(File dir, boolean createIfMissing) throws Exception {
//    // 示例：
//    // Options options = new Options();
//    // options.createIfMissing(createIfMissing);
//    // options.columnFamilies(new LdbColumnFamily(1, "default"), new LdbColumnFamily(2, "meta"));
//    // return LdbFactory.open(dir, options);
//    Options options = new Options()
//        .createIfMissing(true)
//        .errorIfExists(false)
//        .verifyChecksums(true)
//        .paranoidChecks(true)
//        .cacheSize(0)
//        .maxOpenFiles(1000)
//        .blockSize(4096)
//        .blockRestartInterval(16)
//        .compressionType(CompressionType.LZ4);
//
//    LDB ldb = new LDbImpl(options, dir);
//    return ldb;
//  }
//}