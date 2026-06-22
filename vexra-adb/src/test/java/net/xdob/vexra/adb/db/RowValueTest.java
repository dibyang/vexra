package net.xdob.vexra.adb.db;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.h2.value.ValueVarchar;
import org.junit.jupiter.api.Test;

/**
 * RowValue 编解码回归测试。
 *
 * <p>RowValue 是 ADB MVCC version value 的磁盘编码边界。测试覆盖手工 offset
 * 解码和 metadata 轻量解码，防止性能优化改坏持久化字节格式。</p>
 */
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

  @Test
  void shouldDecodeValueAndMetadataFromEncodedBytes() {
    RowValue source = new RowValue();
    source.txnId = 7L;
    source.commitTs = 11L;
    source.deleted = false;
    source.payload = RowCodec.encode(ValueVarchar.get("payload"));
    byte[] encoded = RowValue.encodeValue(source);

    RowValue decoded = RowValue.decodeValue(encoded);
    RowValue.Metadata metadata = RowValue.decodeMetadata(encoded);

    assertEquals(7L, decoded.txnId);
    assertEquals(11L, decoded.commitTs);
    assertFalse(decoded.deleted);
    assertEquals("payload", RowCodec.decode(decoded.payload).getString());
    assertEquals(7L, metadata.txnId);
    assertEquals(11L, metadata.commitTs);
    assertFalse(metadata.deleted);
    assertEquals(source.payload.length, metadata.payloadLength);
    assertTrue(metadata.hasPayload());
  }

  @Test
  void shouldReuseEmptyPayloadForDeletedRows() {
    RowValue source = new RowValue();
    source.txnId = 9L;
    source.commitTs = 12L;
    source.deleted = true;
    source.payload = new byte[0];
    byte[] encoded = RowValue.encodeValue(source);

    RowValue first = RowValue.decodeValue(encoded);
    RowValue second = RowValue.decodeValue(encoded);
    RowValue.Metadata metadata = RowValue.decodeMetadata(encoded);

    assertTrue(first.deleted);
    assertSame(first.payload, second.payload);
    assertEquals(0, metadata.payloadLength);
    assertFalse(metadata.hasPayload());
  }
}
