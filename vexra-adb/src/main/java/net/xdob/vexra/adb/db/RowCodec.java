package net.xdob.vexra.adb.db;

import org.h2.result.DefaultRow;
import org.h2.result.Row;
import org.h2.result.SearchRow;
import org.h2.value.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.h2.value.Value.*;

public interface RowCodec {
  Logger LOG = LoggerFactory.getLogger(RowCodec.class);
  static byte[] safeEncode(Value value) {
    if (value == null) value = ValueNull.INSTANCE;
    int valueType = value.getValueType();
    switch (valueType) {
        case NULL:
          return DynamicByteBuffer.c()
              .putInt(valueType).toArray();
        case CHAR:
        case VARCHAR:
        case CLOB:
        case VARCHAR_IGNORECASE:
        case JSON:
          byte[] bytes = value.getString().getBytes(StandardCharsets.UTF_8);
          return DynamicByteBuffer.c()
              .putInt(valueType)
              .putInt(bytes.length)
              .put(bytes).toArray();
        case BINARY:
        case VARBINARY:
        case BLOB:
        case UUID:
        case JAVA_OBJECT:
          byte[] bytes2 = value.getBytes();
          return DynamicByteBuffer.c()
              .putInt(valueType)
              .putInt(bytes2.length)
              .put(bytes2).toArray();
        case BOOLEAN:
          return DynamicByteBuffer.c()
              .putInt (valueType)
              .putInt(value.getBoolean() ? 1 : 0)
              .toArray();
        case TINYINT:
        case SMALLINT:
        case INTEGER:
          return DynamicByteBuffer.c()
              .putInt(valueType).putInt(value.getInt()).toArray();
        case NUMERIC:
        case DECFLOAT:
          return forNum(value, valueType);
      case REAL:
          return DynamicByteBuffer.c()
              .putInt(valueType).putFloat(value.getFloat()).toArray();
        case DOUBLE:
          return DynamicByteBuffer.c()
              .putInt(valueType).putDouble(value.getDouble()).toArray();

        case BIGINT:
        case DATE:
        case TIME:
          return DynamicByteBuffer.c()
              .putInt(valueType).putLong(value.getLong())
              .toArray();
        case TIME_TZ:
          ValueTimeTimeZone timeZone = (ValueTimeTimeZone) value;
          return forTz(valueType, timeZone);
        case TIMESTAMP:
          ValueTimestamp timestamp = (ValueTimestamp) value;
          return forTs(valueType, timestamp);
        case TIMESTAMP_TZ:
          ValueTimestampTimeZone timestampTimeZone = (ValueTimestampTimeZone) value;
          return forTsTz(valueType, timestampTimeZone);
        case ENUM:
          ValueEnum enumValue = (ValueEnum) value;
          return forEnum(enumValue, valueType);
      case ARRAY:
        case ROW:
          ValueCollectionBase array = (ValueCollectionBase) value;
          return forRow(array, valueType);
      case INTERVAL_YEAR:
        case INTERVAL_MONTH:
        case INTERVAL_YEAR_TO_MONTH:
        case INTERVAL_DAY:
        case INTERVAL_HOUR:
        case INTERVAL_MINUTE:
        case INTERVAL_SECOND:
        case INTERVAL_DAY_TO_HOUR:
        case INTERVAL_DAY_TO_MINUTE:
        case INTERVAL_DAY_TO_SECOND:
        case INTERVAL_HOUR_TO_MINUTE:
        case INTERVAL_HOUR_TO_SECOND:
        case INTERVAL_MINUTE_TO_SECOND:
        case GEOMETRY:
        default:
          throw new UnsupportedOperationException("Unsupported type: " + value.getClass());
      }
  }

  static byte[] forTz(int valueType, ValueTimeTimeZone timeZone) {
    return DynamicByteBuffer.c()
        .putInt(valueType).putLong(timeZone.getNanos())
        .putInt(timeZone.getTimeZoneOffsetSeconds()).toArray();
  }

  static byte[] forTs(int valueType, ValueTimestamp timestamp) {
    return DynamicByteBuffer.c()
        .putInt(valueType).putLong(timestamp.getDateValue())
        .putLong(timestamp.getTimeNanos()).toArray();
  }

  static byte[] forTsTz(int valueType, ValueTimestampTimeZone timestampTimeZone) {
    return DynamicByteBuffer.c()
        .putInt(valueType).putLong(timestampTimeZone.getDateValue())
        .putLong(timestampTimeZone.getTimeNanos())
        .putInt(timestampTimeZone.getTimeZoneOffsetSeconds()).toArray();
  }

  static byte[] forEnum(ValueEnum enumValue, int valueType) {
    byte[] bytes3 = enumValue.getString().getBytes(StandardCharsets.UTF_8);
    return DynamicByteBuffer.c().putInt(valueType)
        .putInt(enumValue.getInt())
        .putInt(bytes3.length).put(bytes3).toArray();
  }

  static byte[] forRow(ValueCollectionBase array, int valueType) {
    Value[] values = array.getList();
    DynamicByteBuffer buf = new DynamicByteBuffer();
    buf.putInt(valueType);
    buf.putInt(values.length);
    for (Value v : values) {
      byte[] bytes1 = safeEncode(v);
      buf.putInt(bytes1.length);
      buf.put(bytes1);
    }
    return buf.toArray();
  }

  static byte[] forNum(Value value, int valueType) {
    BigDecimal bd = value.getBigDecimal();
    int scale = bd.scale();
    BigInteger unscaled = bd.unscaledValue();
    byte[] unscaledBytes = unscaled.toByteArray();
    DynamicByteBuffer buf0 = DynamicByteBuffer.c();
    buf0.putInt(valueType);
    buf0.putInt(unscaledBytes.length);
    buf0.put(unscaledBytes);
    buf0.putInt(scale);
    return buf0.toArray();
  }


  static Value safeDecode(ByteBuffer buf) {
    if (buf == null) {
      return ValueNull.INSTANCE;
    }
    int type = buf.getInt();
    switch (type) {
      case NULL:
        return ValueNull.INSTANCE;
      case CHAR:
      case VARCHAR:
      case CLOB:
      case VARCHAR_IGNORECASE:
      case JSON:
        int len = buf.getInt();
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return ValueVarchar.get(new String(bytes, StandardCharsets.UTF_8))
            .convertTo( type);
      case BINARY:
      case VARBINARY:
      case BLOB:
      case UUID:
      case JAVA_OBJECT:
        int len2 = buf.getInt();
        byte[] bytes2 = new byte[len2];
        buf.get(bytes2);
        return ValueBinary.get(bytes2).convertTo( type);
      case BOOLEAN:
        return ValueBoolean.get(buf.getInt() != 0);
      case TINYINT:
      case SMALLINT:
      case INTEGER:
        return ValueInteger.get(buf.getInt()).convertTo( type);
      case NUMERIC:
      case DECFLOAT:
        return toNum(buf).convertTo( type);
      case REAL:
        return ValueReal.get(buf.getFloat());
      case DOUBLE:
        return ValueDouble.get(buf.getDouble());
      case BIGINT:
      case DATE:
      case TIME:
        return ValueBigint.get(buf.getLong()).convertTo( type);
      case TIME_TZ:
        return ValueTimeTimeZone.fromNanos(buf.getLong(), buf.getInt());
      case TIMESTAMP:
        return ValueTimestamp.fromDateValueAndNanos(buf.getLong(), buf.getLong());
      case TIMESTAMP_TZ:
        return ValueTimestampTimeZone.fromDateValueAndNanos(buf.getLong(), buf.getLong(), buf.getInt());
      case ENUM:
        return toEnum(buf);
      case ARRAY:
      case ROW:
        return toValueRow(buf).convertTo( type);
      case INTERVAL_YEAR:
      case INTERVAL_MONTH:
      case INTERVAL_YEAR_TO_MONTH:
      case INTERVAL_DAY:
      case INTERVAL_HOUR:
      case INTERVAL_MINUTE:
      case INTERVAL_SECOND:
      case INTERVAL_DAY_TO_HOUR:
      case INTERVAL_DAY_TO_MINUTE:
      case INTERVAL_DAY_TO_SECOND:
      case INTERVAL_HOUR_TO_MINUTE:
      case INTERVAL_HOUR_TO_SECOND:
      case INTERVAL_MINUTE_TO_SECOND:
      case GEOMETRY:
      default:
        throw new UnsupportedOperationException("Unsupported type: " + type);
    }
  }

  static ValueRow toValueRow(ByteBuffer buf) {
    int size = buf.getInt();
    Value[] values = new Value[size];
    for (int i = 0; i < size; i++) {
      int len = buf.getInt();
      byte[] bytes = new byte[len];
      buf.get(bytes);
      values[i] = safeDecode(ByteBuffer.wrap(bytes));
    }
    return ValueRow.get(values);
  }

  static ValueEnumBase toEnum(ByteBuffer buf) {
    int c = buf.getInt();
    int len = buf.getInt();
    byte[] bytes = new byte[len];
    buf.get(bytes);
    return ValueEnum.get(new String(bytes, StandardCharsets.UTF_8), c);
  }

  static ValueNumeric toNum(ByteBuffer buf) {
    int size = buf.getInt();
    byte[] unscaledBytes = new byte[size];
    buf.get(unscaledBytes);
    int scale = buf.getInt();
    BigInteger unscaled = new BigInteger(unscaledBytes);
    BigDecimal bd = new BigDecimal(unscaled, scale);
    return ValueNumeric.get(bd);
  }


  static byte[] encode(Value value) {
    if(value== null)value = ValueNull.INSTANCE;
    DynamicByteBuffer buf = new DynamicByteBuffer();
    if(value instanceof SearchRow) {
      SearchRow row = (SearchRow) value;
      int columnCount = row.getColumnCount();
      Value[] values = new Value[columnCount];
      for (int i = 0; i < columnCount; i++) {
        values[i] = row.getValue(i);
      }
      value = ValueRow.get(values);
    }
    buf.put(safeEncode(value));
    return buf.toArray();
  }

  static Value decode(byte[] bytes){
    ByteBuffer buf = ByteBuffer.wrap(bytes);
    return safeDecode(buf);
  }

  static Row decode(long rowId, byte[] bytes){
    if (bytes == null) {
      throw new IllegalArgumentException("decode bytes is null ");
    }
    if (bytes.length == 0) {
      throw new IllegalArgumentException("decode bytes is empty");
    }
    Value value = decode(bytes);
    if (value instanceof ValueRow) {
      Value[] values = ((ValueRow) value).getList();
      DefaultRow row = new DefaultRow(values);
      row.setKey(rowId);
      return row;
    }
    DefaultRow row = new DefaultRow(new Value[]{value});
    row.setKey(rowId);
    return row;
  }

}
