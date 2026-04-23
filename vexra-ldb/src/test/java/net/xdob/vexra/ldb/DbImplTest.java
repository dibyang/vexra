package net.xdob.vexra.ldb;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import com.google.common.primitives.UnsignedBytes;
import net.xdob.vexra.ldb.impl.DbConstants;
import net.xdob.vexra.ldb.impl.LDBFactory;
import net.xdob.vexra.ldb.impl.LDbImpl;
import net.xdob.vexra.ldb.impl.ValueType;
import net.xdob.vexra.ldb.util.FileUtils;
import net.xdob.vexra.ldb.util.Slices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;

import static com.google.common.collect.Maps.immutableEntry;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static net.xdob.vexra.ldb.CompressionType.NONE;
import static net.xdob.vexra.ldb.impl.DbConstants.NUM_LEVELS;
import static net.xdob.vexra.ldb.table.BlockHelper.afterString;
import static net.xdob.vexra.ldb.table.BlockHelper.beforeString;
import static org.junit.jupiter.api.Assertions.*;

public class DbImplTest {
  // You can set the STRESS_FACTOR system property to make the tests run more iterations.
  public static final double STRESS_FACTOR = Double.parseDouble(System.getProperty("STRESS_FACTOR", "1"));

  private static final String DOES_NOT_EXIST_FILENAME = "/foo/bar/doowop/idontexist";
  private static final String DOES_NOT_EXIST_FILENAME_PATTERN = ".foo.bar.doowop.idontexist";

  private File databaseDir;

  @Test
  public void testBackgroundCompaction()
      throws Exception {
    Options options = new Options();
    options.maxOpenFiles(100);
    options.createIfMissing(true);
    LDbImpl db = new LDbImpl(options, this.databaseDir);
    Random random = new Random(301);
    for (int i = 0; i < 200000 * STRESS_FACTOR; i++) {
      db.put(randomString(random, 64).getBytes(), new byte[]{0x01}, new WriteOptions().sync(false));
      db.get(randomString(random, 64).getBytes());
      if ((i % 50000) == 0 && i != 0) {
        System.out.println(i + " rows written");
      }
    }
  }

  @Test
  public void testCompactionsOnBigDataSet()
      throws Exception {
    Options options = new Options();
    options.createIfMissing(true);
    LDbImpl db = new LDbImpl(options, databaseDir);
    for (int index = 0; index < 5000000; index++) {
      String key = "Key LOOOOOOOOOOOOOOOOOONG KEY " + index;
      String value = "This is element " + index + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABZASDFASDKLFJASDFKJSDFLKSDJFLKJSDHFLKJHSDJFSDFHJASDFLKJSDF";
      db.put(key.getBytes(UTF_8), value.getBytes(UTF_8));
    }
  }

  @Test
  public void testEmpty()
      throws Exception {
    Options options = new Options();
    File databaseDir = this.databaseDir;
    DbStringWrapper db = new DbStringWrapper(options, databaseDir);
    assertNull(db.get("foo"));
  }

  @Test
  public void testEmptyBatch()
      throws Exception {
    Options options = new Options().createIfMissing(true);
    LDB db = new LDBFactory().open(databaseDir, options);

    LdbWriteBatch batch = db.createWriteBatch();
    batch.close();
    db.write(batch);

    db.close();

    new LDBFactory().open(databaseDir, options);
  }

  @Test
  public void testReadWrite()
      throws Exception {
    DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
    db.put("foo", "v1");
    assertEquals("v1", db.get("foo"));
    db.put("bar", "v2");
    db.put("foo", "v3");
    assertEquals("v3", db.get("foo"));
    assertEquals("v2", db.get("bar"));
  }

  @Test
  public void testPutDeleteGet()
      throws Exception {
    DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
    db.put("foo", "v1");
    assertEquals("v1", db.get("foo"));
    db.put("foo", "v2");
    assertEquals("v2", db.get("foo"));
    db.delete("foo");
    assertNull(db.get("foo"));
  }

  @Test
  public void testGetFromImmutableLayer()
      throws Exception {
    DbStringWrapper db = new DbStringWrapper(new Options().writeBufferSize(100000), databaseDir);
    db.put("foo", "v1");
    assertEquals("v1", db.get("foo"));

    db.put("k1", longString(100000, 'x'));
    db.put("k2", longString(100000, 'y'));
    assertEquals("v1", db.get("foo"));
  }

  @Test
  public void testGetFromVersions()
      throws Exception {
    DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
    db.put("foo", "v1");
    db.compactMemTable();
    assertEquals("v1", db.get("foo"));
  }

  @Test
  public void testGetSnapshot()
      throws Exception {
    DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);

    for (int i = 0; i < 2; i++) {
      String key = (i == 0) ? "foo" : longString(200, 'x');
      db.put(key, "v1");
      Snapshot s1 = db.getSnapshot();
      db.put(key, "v2");
      assertEquals("v2", db.get(key));
      assertEquals("v1", db.get(key, s1));

      db.compactMemTable();
      assertEquals("v2", db.get(key));
      assertEquals("v1", db.get(key, s1));
      s1.close();
    }
  }

  @Test
  public void testGetLevel0Ordering()
      throws Exception {
    DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);

    db.put("bar", "b");
    db.put("foo", "v1");
    db.compactMemTable();
    db.put("foo", "v2");
    db.compactMemTable();
    assertEquals("v2", db.get("foo"));
  }

  @Test
  public void testGetOrderedByLevels()
      throws Exception {
    DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
    db.put("foo", "v1");
    db.compact("a", "z");
    assertEquals("v1", db.get("foo"));
    db.put("foo", "v2");
    assertEquals("v2", db.get("foo"));
    db.compactMemTable();
    assertEquals("v2", db.get("foo"));
  }

  @Test
  public void testGetPicksCorrectFile()
      throws Exception {
    DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
    db.put("a", "va");
    db.compact("a", "b");
    db.put("x", "vx");
    db.compact("x", "y");
    db.put("f", "vf");
    db.compact("f", "g");

    assertEquals("va", db.get("a"));
    assertEquals("vf", db.get("f"));
    assertEquals("vx", db.get("x"));
  }

  @Test
  public void testEmptyCursor()
      throws Exception {
    DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);

    try (StringDbCursor cursor = db.cursor()) {
      cursor.seekToFirst();
      assertInvalid(cursor);
    }

    try (StringDbCursor cursor = db.cursor()) {
      cursor.seek("foo");
      assertInvalid(cursor);
    }
  }

  @Test
  public void testCursorSingle()
      throws Exception {
    DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
    db.put("a", "va");

    try (StringDbCursor cursor = db.cursor()) {
      cursor.seekToFirst();
      assertSequence(cursor, immutableEntry("a", "va"));
    }
  }

  @Test
  public void testCursorMultiple()
      throws Exception {
    DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
    db.put("a", "va");
    db.put("b", "vb");
    db.put("c", "vc");

    try (StringDbCursor cursor = db.cursor()) {
      cursor.seekToFirst();
      assertSequence(cursor,
          immutableEntry("a", "va"),
          immutableEntry("b", "vb"),
          immutableEntry("c", "vc"));

      db.put("a", "va2");
      db.put("a2", "va3");
      db.put("b", "vb2");
      db.put("c", "vc2");

      cursor.seekToFirst();
      assertSequence(cursor,
          immutableEntry("a", "va"),
          immutableEntry("b", "vb"),
          immutableEntry("c", "vc"));
    }
  }

  @Test
  public void testRecover()
      throws Exception {
    DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
    db.put("foo", "v1");
    db.put("baz", "v5");

    db.reopen();

    assertEquals("v1", db.get("foo"));
    assertEquals("v5", db.get("baz"));
    db.put("bar", "v2");
    db.put("foo", "v3");

    db.reopen();

    assertEquals("v3", db.get("foo"));
    db.put("foo", "v4");
    assertEquals("v4", db.get("foo"));
    assertEquals("v2", db.get("bar"));
    assertEquals("v5", db.get("baz"));
  }

  @Test
  public void testRecoveryWithEmptyLog()
      throws Exception {
    DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
    db.put("foo", "v1");
    db.put("foo", "v2");
    db.reopen();
    db.reopen();
    db.put("foo", "v3");
    db.reopen();
    assertEquals("v3", db.get("foo"));
  }

  @Test
  public void testRecoverDuringMemtableCompaction()
      throws Exception {
    DbStringWrapper db = new DbStringWrapper(new Options().writeBufferSize(1000000), databaseDir);

    db.put("foo", "v1");
    db.put("big1", longString(10000000, 'x'));
    db.put("big2", longString(1000, 'y'));
    db.put("bar", "v2");

    db.reopen();
    assertEquals("v1", db.get("foo"));
    assertEquals("v2", db.get("bar"));
    assertEquals(longString(10000000, 'x'), db.get("big1"));
    assertEquals(longString(1000, 'y'), db.get("big2"));
  }

  @Test
  public void testSparseMerge()
      throws Exception {
    DbStringWrapper db = new DbStringWrapper(new Options().compressionType(NONE), databaseDir);

    fillLevels(db, "A", "Z");

    String value = longString(1000, 'x');
    db.put("A", "va");

    for (int i = 0; i < 100000; i++) {
      String key = String.format("B%010d", i);
      db.put(key, value);
    }
    db.put("C", "vc");
    db.compactMemTable();
    db.compactRange(0, "A", "Z");

    db.put("A", "va2");
    db.put("B100", "bvalue2");
    db.put("C", "vc2");
    db.compactMemTable();
  }

  @Test
  public void testApproximateSizes()
      throws Exception {
    DbStringWrapper db = new DbStringWrapper(new Options().writeBufferSize(100000000).compressionType(NONE), databaseDir);

    assertBetween(db.size("", "xyz"), 0, 0);
    db.reopen();
    assertBetween(db.size("", "xyz"), 0, 0);

    int n = 80;
    Random random = new Random(301);
    for (int i = 0; i < n; i++) {
      db.put(key(i), randomString(random, 100000));
    }

    assertBetween(db.size("", key(50)), 0, 0);

    for (int run = 0; run < 3; run++) {
      db.reopen();

      for (int compactStart = 0; compactStart < n; compactStart += 10) {
        for (int i = 0; i < n; i += 10) {
          assertBetween(db.size("", key(i)), 100000 * i, 100000 * i + 10000);
          assertBetween(db.size("", key(i) + ".suffix"), 100000 * (i + 1), 100000 * (i + 1) + 10000);
          assertBetween(db.size(key(i), key(i + 10)), 100000 * 10, 100000 * 10 + 10000);
        }
        assertBetween(db.size("", key(50)), 5000000, 5010000);
        assertBetween(db.size("", key(50) + ".suffix"), 5100000, 5110000);

        db.compactRange(0, key(compactStart), key(compactStart + 9));
      }
    }
  }

  @Test
  public void testApproximateSizesMixOfSmallAndLarge()
      throws Exception {
    DbStringWrapper db = new DbStringWrapper(new Options().compressionType(NONE), databaseDir);
    Random random = new Random(301);
    String big1 = randomString(random, 100000);
    db.put(key(0), randomString(random, 10000));
    db.put(key(1), randomString(random, 10000));
    db.put(key(2), big1);
    db.put(key(3), randomString(random, 10000));
    db.put(key(4), big1);
    db.put(key(5), randomString(random, 10000));
    db.put(key(6), randomString(random, 300000));
    db.put(key(7), randomString(random, 10000));

    for (int run = 0; run < 3; run++) {
      db.reopen();

      assertBetween(db.size("", key(0)), 0, 0);
      assertBetween(db.size("", key(1)), 10000, 11000);
      assertBetween(db.size("", key(2)), 20000, 21000);
      assertBetween(db.size("", key(3)), 120000, 121000);
      assertBetween(db.size("", key(4)), 130000, 131000);
      assertBetween(db.size("", key(5)), 230000, 231000);
      assertBetween(db.size("", key(6)), 240000, 241000);
      assertBetween(db.size("", key(7)), 540000, 541000);
      assertBetween(db.size("", key(8)), 550000, 551000);

      assertBetween(db.size(key(3), key(5)), 110000, 111000);

      db.compactRange(0, key(0), key(100));
    }
  }

  @Test
  public void testCursorPinsRef()
      throws Exception {
    DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
    db.put("foo", "hello");

    try (StringDbCursor cursor = db.cursor()) {
      db.put("foo", "newvalue1");
      for (int i = 0; i < 100; i++) {
        db.put(key(i), key(i) + longString(100000, 'v'));
      }
      db.put("foo", "newvalue1");

      cursor.seekToFirst();
      assertSequence(cursor, immutableEntry("foo", "hello"));
    }
  }

  @Test
  public void testSnapshot()
      throws Exception {
    DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
    db.put("foo", "v1");
    Snapshot s1 = db.getSnapshot();
    db.put("foo", "v2");
    Snapshot s2 = db.getSnapshot();
    db.put("foo", "v3");
    Snapshot s3 = db.getSnapshot();

    db.put("foo", "v4");

    assertEquals("v1", db.get("foo", s1));
    assertEquals("v2", db.get("foo", s2));
    assertEquals("v3", db.get("foo", s3));
    assertEquals("v4", db.get("foo"));

    s3.close();
    assertEquals("v1", db.get("foo", s1));
    assertEquals("v2", db.get("foo", s2));
    assertEquals("v4", db.get("foo"));

    s1.close();
    assertEquals("v2", db.get("foo", s2));
    assertEquals("v4", db.get("foo"));

    s2.close();
    assertEquals("v4", db.get("foo"));
  }

  @Test
  public void testHiddenValuesAreRemoved()
      throws Exception {
    DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
    Random random = new Random(301);
    fillLevels(db, "a", "z");

    String big = randomString(random, 50000);
    db.put("foo", big);
    db.put("pastFoo", "v");

    Snapshot snapshot = db.getSnapshot();

    db.put("foo", "tiny");
    db.put("pastFoo2", "v2");

    db.compactMemTable();

    assertEquals(big, db.get("foo", snapshot));
    assertBetween(db.size("", "pastFoo"), 50000, 60000);
    snapshot.close();
    assertEquals(asList("tiny", big), db.allEntriesFor("foo"));
    db.compactRange(0, "", "x");
    assertEquals(asList("tiny"), db.allEntriesFor("foo"));
    db.compactRange(1, "", "x");
    assertEquals(asList("tiny"), db.allEntriesFor("foo"));

    assertBetween(db.size("", "pastFoo"), 0, 1000);
  }

  @Test
  public void testDeletionMarkers1() throws Exception {
    DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);

    db.put("foo", "v1");
    db.compactMemTable();

    int last = DbConstants.MAX_MEM_COMPACT_LEVEL;
    assertEquals(1, db.numberOfFilesInLevel(last)); // foo => v1 is now in last level

    // Place a table at level last-1 to prevent merging with preceding mutation
    db.put("a", "begin");
    db.put("z", "end");
    db.compactMemTable();

    assertEquals(1, db.numberOfFilesInLevel(last));
    assertEquals(1, db.numberOfFilesInLevel(last - 1));
    assertEquals("begin", db.get("a"));
    assertEquals("v1", db.get("foo"));
    assertEquals("end", db.get("z"));

    db.delete("foo");
    db.put("foo", "v2");

    // 当前 memtable + 低层旧值
    assertEquals(asList("v2", "DEL", "v1"), db.allEntriesFor("foo"));

    db.compactMemTable();  // Moves to level last-2

    assertEquals("begin", db.get("a"));
    assertEquals("v2", db.get("foo"));
    assertEquals("end", db.get("z"));

    // compact memtable 后，DEL 仍然保留
    assertEquals(asList("v2", "DEL", "v1"), db.allEntriesFor("foo"));

    db.compactRange(last - 2, "", "z");

    // DEL eliminated, but v1 remains because we aren't compacting that level
    // (DEL can be eliminated because v2 hides v1).
    assertEquals(asList("v2", "v1"), db.allEntriesFor("foo"));

    db.compactRange(last - 1, "", "z");

    // Merging last-1 w/ last, so we are the base level for "foo", so
    // obsolete entries are removed and only v2 remains.
    assertEquals(asList("v2"), db.allEntriesFor("foo"));
  }

  @Test
  public void testDeletionMarkers2()
      throws Exception {
    DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);

    db.put("foo", "v1");
    db.compactMemTable();

    int last = DbConstants.MAX_MEM_COMPACT_LEVEL;

    db.put("a", "begin");
    db.put("z", "end");
    db.compactMemTable();

    db.delete("foo");

    assertEquals(asList("DEL", "v1"), db.allEntriesFor("foo"));
    db.compactMemTable();
    assertEquals(asList("DEL", "v1"), db.allEntriesFor("foo"));
    db.compactRange(last - 2, "", "z");

    assertEquals(asList("DEL", "v1"), db.allEntriesFor("foo"));
    db.compactRange(last - 1, "", "z");

    assertEquals(asList(), db.allEntriesFor("foo"));
  }

  @Test
  public void testEmptyDb()
      throws Exception {
    DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
    testDb(db);
  }

  @Test
  public void testSingleEntrySingle()
      throws Exception {
    DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);
    testDb(db, immutableEntry("name", "dain sundstrom"));
  }

  @Test
  public void testMultipleEntries()
      throws Exception {
    DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);

    List<Entry<String, String>> entries = asList(
        immutableEntry("beer/ale", "Lagunitas  Little Sumpin’ Sumpin’"),
        immutableEntry("beer/ipa", "Lagunitas IPA"),
        immutableEntry("beer/stout", "Lagunitas Imperial Stout"),
        immutableEntry("scotch/light", "Oban 14"),
        immutableEntry("scotch/medium", "Highland Park"),
        immutableEntry("scotch/strong", "Lagavulin"));

    testDb(db, entries);
  }

  @Test
  public void testMultiPassMultipleEntries()
      throws Exception {
    DbStringWrapper db = new DbStringWrapper(new Options(), databaseDir);

    List<Entry<String, String>> entries = asList(
        immutableEntry("beer/ale", "Lagunitas  Little Sumpin’ Sumpin’"),
        immutableEntry("beer/ipa", "Lagunitas IPA"),
        immutableEntry("beer/stout", "Lagunitas Imperial Stout"),
        immutableEntry("scotch/light", "Oban 14"),
        immutableEntry("scotch/medium", "Highland Park"),
        immutableEntry("scotch/strong", "Lagavulin"));

    for (int i = 1; i < entries.size(); i++) {
      testDb(db, entries);
    }
  }

//  @Test
//  public void testCantCreateDirectoryReturnMessage() throws Exception {
//    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
//        () -> new DbStringWrapper(new Options(), new File(DOES_NOT_EXIST_FILENAME)));
//    assertTrue(ex.getMessage().matches("Database directory '" + DOES_NOT_EXIST_FILENAME_PATTERN + "'.*"));
//  }

  @Test
  public void testDBDirectoryIsFileRetrunMessage()
      throws Exception {
    File databaseFile = new File(databaseDir + "/imafile");
    assertTrue(databaseFile.createNewFile());
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> new DbStringWrapper(new Options(), databaseFile));
    assertTrue(ex.getMessage().matches("Database directory.*is not a directory"));
  }

  @Test
  public void testSymbolicLinkForFileWithoutParent() {
    assertFalse(FileUtils.isSymbolicLink(new File("db")));
  }

  @Test
  public void testSymbolicLinkForFileWithParent() {
    assertFalse(FileUtils.isSymbolicLink(new File(DOES_NOT_EXIST_FILENAME, "db")));
  }

  @Test
  public void testCustomComparator()
      throws Exception {
    DbStringWrapper db = new DbStringWrapper(new Options().comparator(new ReverseDBComparator()), databaseDir);

    List<Entry<String, String>> entries = asList(
        immutableEntry("scotch/strong", "Lagavulin"),
        immutableEntry("scotch/medium", "Highland Park"),
        immutableEntry("scotch/light", "Oban 14"),
        immutableEntry("beer/stout", "Lagunitas Imperial Stout"),
        immutableEntry("beer/ipa", "Lagunitas IPA"),
        immutableEntry("beer/ale", "Lagunitas  Little Sumpin’ Sumpin’")
    );

    for (Entry<String, String> entry : entries) {
      db.put(entry.getKey(), entry.getValue());
    }

    try (StringDbCursor cursor = db.cursor()) {
      cursor.seekToFirst();
      assertSequence(cursor, entries);
    }
  }

  @SafeVarargs
  private final void testDb(DbStringWrapper db, Entry<String, String>... entries)
      throws IOException {
    testDb(db, asList(entries));
  }

  private void testDb(DbStringWrapper db, List<Entry<String, String>> entries)
      throws IOException {
    for (Entry<String, String> entry : entries) {
      db.put(entry.getKey(), entry.getValue());
    }

    for (Entry<String, String> entry : entries) {
      String actual = db.get(entry.getKey());
      assertEquals(entry.getValue(), actual, "Key: " + entry.getKey());
    }

    try (StringDbCursor cursor = db.cursor()) {
      cursor.seekToFirst();
      assertSequence(cursor, entries);
    }

    try (StringDbCursor cursor = db.cursor()) {
      cursor.seekToFirst();
      assertSequence(cursor, entries);
    }

    for (Entry<String, String> entry : entries) {
      List<Entry<String, String>> nextEntries = entries.subList(entries.indexOf(entry), entries.size());

      try (StringDbCursor cursor = db.cursor()) {
        cursor.seek(entry.getKey());
        assertSequence(cursor, nextEntries);
      }

      try (StringDbCursor cursor = db.cursor()) {
        cursor.seek(beforeString(entry));
        assertSequence(cursor, nextEntries);
      }

      try (StringDbCursor cursor = db.cursor()) {
        cursor.seek(afterString(entry));
        assertSequence(cursor, nextEntries.subList(1, nextEntries.size()));
      }
    }

    byte[] endKey = new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
    try (StringDbCursor cursor = db.cursor()) {
      cursor.seek(new String(endKey, UTF_8));
      assertFalse(cursor.isValid());
    }
  }

  @BeforeEach
  public void setUp()
      throws Exception {
    databaseDir = FileUtils.createTempDir("leveldb");
  }

  @AfterEach
  public void tearDown()
      throws Exception {
    for (DbStringWrapper db : opened) {
      db.close();
    }
    opened.clear();
    FileUtils.deleteRecursively(databaseDir);
  }

  private void assertBetween(long actual, int smallest, int greatest) {
    if (!between(actual, smallest, greatest)) {
      fail(String.format("Expected: %s to be between %s and %s", actual, smallest, greatest));
    }
  }

  private void assertInvalid(StringDbCursor cursor) {
    assertFalse(cursor.isValid());
    assertThrows(NoSuchElementException.class, cursor::key);
    assertThrows(NoSuchElementException.class, cursor::value);
    assertThrows(NoSuchElementException.class, cursor::next);
  }

  @SafeVarargs
  private final void assertSequence(StringDbCursor cursor, Entry<String, String>... entries) {
    assertSequence(cursor, asList(entries));
  }

  private void assertSequence(StringDbCursor cursor, List<Entry<String, String>> entries) {
    for (Entry<String, String> entry : entries) {
      assertTrue(cursor.isValid(), "Expected cursor to be valid for entry: " + entry);
      assertEquals(entry.getKey(), cursor.key(), "Key mismatch");
      assertEquals(entry.getValue(), cursor.value(), "Value mismatch");
      cursor.next();
    }
    assertFalse(cursor.isValid(), "Expected cursor to be invalid after consuming all entries");
  }

  static byte[] toByteArray(String value) {
    return value.getBytes(UTF_8);
  }

  private static String randomString(Random random, int length) {
    char[] chars = new char[length];
    for (int i = 0; i < chars.length; i++) {
      chars[i] = (char) ((int) ' ' + random.nextInt(95));
    }
    return new String(chars);
  }

  private static String longString(int length, char character) {
    char[] chars = new char[length];
    Arrays.fill(chars, character);
    return new String(chars);
  }

  public static String key(int i) {
    return String.format("key%06d", i);
  }

  private boolean between(long size, long left, long right) {
    return left <= size && size <= right;
  }

  private void fillLevels(DbStringWrapper db, String smallest, String largest) {
    for (int level = 0; level < NUM_LEVELS; level++) {
      db.put(smallest, "begin");
      db.put(largest, "end");
      db.compactMemTable();
    }
  }

  private final ArrayList<DbStringWrapper> opened = new ArrayList<>();

  private static class ReverseDBComparator
      implements DBComparator {
    @Override
    public String name() {
      return "test";
    }

    @Override
    public int compare(byte[] sliceA, byte[] sliceB) {
      return -(UnsignedBytes.lexicographicalComparator().compare(sliceA, sliceB));
    }

    @Override
    public byte[] findShortestSeparator(byte[] start, byte[] limit) {
      int sharedBytes = calculateSharedBytes(start, limit);

      if (sharedBytes < Math.min(start.length, limit.length)) {
        int lastSharedByte = start[sharedBytes];
        if (lastSharedByte < 0xff && lastSharedByte + 1 < limit[sharedBytes]) {
          byte[] result = Arrays.copyOf(start, sharedBytes + 1);
          result[sharedBytes] = (byte) (lastSharedByte + 1);

          assertTrue(compare(result, limit) < 0, "start must be less than last limit");
          return result;
        }
      }
      return start;
    }

    @Override
    public byte[] findShortSuccessor(byte[] key) {
      for (int i = 0; i < key.length; i++) {
        int b = key[i];
        if (b != 0xff) {
          byte[] result = Arrays.copyOf(key, i + 1);
          result[i] = (byte) (b + 1);
          return result;
        }
      }
      return key;
    }

    private int calculateSharedBytes(byte[] leftKey, byte[] rightKey) {
      int sharedKeyBytes = 0;

      if (leftKey != null && rightKey != null) {
        int minSharedKeyBytes = Ints.min(leftKey.length, rightKey.length);
        while (sharedKeyBytes < minSharedKeyBytes && leftKey[sharedKeyBytes] == rightKey[sharedKeyBytes]) {
          sharedKeyBytes++;
        }
      }

      return sharedKeyBytes;
    }
  }

  private class DbStringWrapper {
    private final Options options;
    private final File databaseDir;
    private LDbImpl db;

    private DbStringWrapper(Options options, File databaseDir)
        throws IOException {
      this.options = options.verifyChecksums(true).createIfMissing(true).errorIfExists(true);
      this.databaseDir = databaseDir;
      this.db = new LDbImpl(options, databaseDir);
      opened.add(this);
    }

    public String get(String key) {
      byte[] slice = db.get(toByteArray(key));
      if (slice == null) {
        return null;
      }
      return new String(slice, UTF_8);
    }

    public String get(String key, Snapshot snapshot) {
      byte[] slice = db.get(toByteArray(key), new ReadOptions().snapshot(snapshot));
      if (slice == null) {
        return null;
      }
      return new String(slice, UTF_8);
    }

    public void put(String key, String value) {
      db.put(toByteArray(key), toByteArray(value));
    }

    public void delete(String key) {
      db.delete(toByteArray(key));
    }

    public StringDbCursor cursor() {
      return new StringDbCursor(db.newSnapshotCursor());
    }

    public Snapshot getSnapshot() {
      return db.getSnapshot();
    }

    public void close() {
      db.close();
    }

    public void compactMemTable() {
      db.flushMemTable();
    }

    public void compactRange(int level, String start, String limit) {
      db.compactRange(level, Slices.copiedBuffer(start, UTF_8), Slices.copiedBuffer(limit, UTF_8));
    }

    public void compact(String start, String limit) {
      db.flushMemTable();
      int maxLevelWithFiles = 1;
      for (int level = 0; level < maxLevelWithFiles; level++) {
        db.compactRange(level, Slices.copiedBuffer("", UTF_8), Slices.copiedBuffer("~", UTF_8));
      }
    }

    public long size(String start, String limit) {
      return db.getApproximateSizes(new Range(toByteArray(start), toByteArray(limit)));
    }

    public void reopen()
        throws IOException {
      reopen(options);
    }

    public void reopen(Options options)
        throws IOException {
      db.close();
      db = new LDbImpl(options.verifyChecksums(true).createIfMissing(false).errorIfExists(false), databaseDir);
    }

    private List<String> allEntriesFor(String userKey) {
      ImmutableList.Builder<String> result = ImmutableList.builder();
      RawCursor iterator = db.newRawCursor();
      iterator.seekToFirst();
      while (iterator.isValid()) {
        String entryKey = iterator.key().getUserKey().toString(UTF_8);
        if (entryKey.equals(userKey)) {
          if (iterator.key().getValueType() == ValueType.VALUE) {
            result.add(iterator.value().toString(UTF_8));
          } else {
            result.add("DEL");
          }
        }
        iterator.next();
      }
      return result.build();
    }

    public int numberOfFilesInLevel(int last) {
      return db.numberOfFilesInLevel(last);
    }
  }

  private static class StringDbCursor implements AutoCloseable {
    private final SnapshotCursor cursor;

    private StringDbCursor(SnapshotCursor cursor) {
      this.cursor = cursor;
    }

    public boolean isValid() {
      return cursor.isValid();
    }

    public void seekToFirst() {
      cursor.seekToFirst();
    }

    public void seek(String targetKey) {
      cursor.seek(targetKey.getBytes(UTF_8));
    }

    public void next() {
      cursor.next();
    }

    public String key() {
      return new String(cursor.key(), UTF_8);
    }

    public String value() {
      return new String(cursor.value(), UTF_8);
    }

    public Entry<String, String> entry() {
      return immutableEntry(key(), value());
    }

    @Override
    public void close() {
      cursor.close();
    }
  }
}
