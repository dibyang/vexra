package net.xdob.vexra.adb.db;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class RowValueTest {

  @Test
  void encodeValueWithCommitTsDoesNotMutateSource() {
    RowValue source = new RowValue();
    source.txnId = 11L;
    source.commitTs = 0L;
    source.deleted = false;
    source.payload = new byte[] {1, 2, 3};

    byte[] encoded = RowValue.encodeValue(source, 22L);
    RowValue decoded = RowValue.decodeValue(encoded);

    assertEquals(0L, source.commitTs);
    assertEquals(11L, decoded.txnId);
    assertEquals(22L, decoded.commitTs);
    assertFalse(decoded.deleted);
    assertArrayEquals(source.payload, decoded.payload);
  }
}
