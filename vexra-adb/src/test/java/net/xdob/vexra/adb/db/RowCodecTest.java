package net.xdob.vexra.adb.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.h2.value.Value;
import org.h2.value.ValueBigint;
import org.h2.value.ValueNull;
import org.h2.value.ValueRow;
import org.h2.value.ValueVarchar;
import org.junit.jupiter.api.Test;

class RowCodecTest {

  @Test
  void decodeColumnReturnsOnlySelectedValue() {
    Value[] values = new Value[]{
        ValueBigint.get(7L),
        ValueVarchar.get("selected"),
        ValueVarchar.get("tail")
    };
    byte[] payload = RowCodec.encode(ValueRow.get(values));

    assertEquals("selected", RowCodec.decodeColumn(payload, 1).getString());
    assertEquals(7L, RowCodec.decodeColumn(payload, 0).getLong());
    assertSame(ValueNull.INSTANCE, RowCodec.decodeColumn(payload, 4));
  }

  @Test
  void decodeColumnReadsPayloadSubRangeWithoutCopy() {
    Value[] values = new Value[]{
        ValueBigint.get(9L),
        ValueVarchar.get("from-row-value"),
        ValueVarchar.get("tail")
    };
    RowValue rowValue = new RowValue();
    rowValue.payload = RowCodec.encode(ValueRow.get(values));
    byte[] encoded = RowValue.encodeValue(rowValue, 12L);

    assertEquals("from-row-value", RowCodec.decodeColumn(encoded,
        RowValue.payloadOffset(), RowValue.payloadLength(encoded), 1)
        .getString());
    assertEquals(9L, RowCodec.decodeColumn(encoded, RowValue.payloadOffset(),
        RowValue.payloadLength(encoded), 0).getLong());
    assertSame(ValueNull.INSTANCE, RowCodec.decodeColumn(encoded,
        RowValue.payloadOffset(), RowValue.payloadLength(encoded), 4));
  }
}
