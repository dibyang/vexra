package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.impl.LDBFactory;
import net.xdob.vexra.ldb.util.FileUtils;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import static net.xdob.vexra.ldb.impl.LDBFactory.asString;
import static net.xdob.vexra.ldb.impl.LDBFactory.bytes;
import static org.testng.Assert.assertTrue;

/**
 * Test the implemenation via the org.iq80.leveldb API.
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
public class ApiTest {
  private final File databaseDir = FileUtils.createTempDir("leveldb");

  public void assertEquals(byte[] arg1, byte[] arg2) {
    assertTrue(Arrays.equals(arg1, arg2), asString(arg1) + " != " + asString(arg2));
  }

  private final DBFactory factory = LDBFactory.factory;

  File getTestDirectory(String name)
      throws IOException {
    File rc = new File(databaseDir, name);
    factory.destroy(rc, new Options().createIfMissing(true));
    rc.mkdirs();
    return rc;
  }

  @Test
  public void testCompaction()
      throws IOException, DBException {
    Options options = new Options().createIfMissing(true).compressionType(CompressionType.NONE);

    File path = getTestDirectory("testCompaction");
    LDB db = factory.open(path, options);

    System.out.println("Adding");
    for (int i = 0; i < 1000 * 1000; i++) {
      if (i % 100000 == 0) {
        System.out.println("  at: " + i);
      }
      db.put(bytes("key" + i), bytes("value" + i));
    }

    db.close();
    db = factory.open(path, options);

    System.out.println("Deleting");
    for (int i = 0; i < 1000 * 1000; i++) {
      if (i % 100000 == 0) {
        System.out.println("  at: " + i);
      }
      db.delete(bytes("key" + i));
    }

    db.close();
    db = factory.open(path, options);

    System.out.println("Adding");
    for (int i = 0; i < 1000 * 1000; i++) {
      if (i % 100000 == 0) {
        System.out.println("  at: " + i);
      }
      db.put(bytes("key" + i), bytes("value" + i));
    }

    db.close();
  }
}
