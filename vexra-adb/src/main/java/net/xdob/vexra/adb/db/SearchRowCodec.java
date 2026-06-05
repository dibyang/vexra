package net.xdob.vexra.adb.db;

import org.h2.result.SearchRow;
import org.h2.table.IndexColumn;
import org.h2.util.DateTimeUtils;
import org.h2.value.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.h2.value.Value.*;
import static org.h2.value.Value.ARRAY;
import static org.h2.value.Value.BIGINT;
import static org.h2.value.Value.BINARY;
import static org.h2.value.Value.BLOB;
import static org.h2.value.Value.BOOLEAN;
import static org.h2.value.Value.CLOB;
import static org.h2.value.Value.DATE;
import static org.h2.value.Value.DECFLOAT;
import static org.h2.value.Value.DOUBLE;
import static org.h2.value.Value.ENUM;
import static org.h2.value.Value.GEOMETRY;
import static org.h2.value.Value.INTEGER;
import static org.h2.value.Value.INTERVAL_DAY;
import static org.h2.value.Value.INTERVAL_DAY_TO_HOUR;
import static org.h2.value.Value.INTERVAL_DAY_TO_MINUTE;
import static org.h2.value.Value.INTERVAL_DAY_TO_SECOND;
import static org.h2.value.Value.INTERVAL_HOUR;
import static org.h2.value.Value.INTERVAL_HOUR_TO_MINUTE;
import static org.h2.value.Value.INTERVAL_HOUR_TO_SECOND;
import static org.h2.value.Value.INTERVAL_MINUTE;
import static org.h2.value.Value.INTERVAL_MINUTE_TO_SECOND;
import static org.h2.value.Value.INTERVAL_MONTH;
import static org.h2.value.Value.INTERVAL_SECOND;
import static org.h2.value.Value.INTERVAL_YEAR;
import static org.h2.value.Value.INTERVAL_YEAR_TO_MONTH;
import static org.h2.value.Value.JAVA_OBJECT;
import static org.h2.value.Value.JSON;
import static org.h2.value.Value.NUMERIC;
import static org.h2.value.Value.REAL;
import static org.h2.value.Value.ROW;
import static org.h2.value.Value.SMALLINT;
import static org.h2.value.Value.TIME;
import static org.h2.value.Value.TIMESTAMP;
import static org.h2.value.Value.TIMESTAMP_TZ;
import static org.h2.value.Value.TIME_TZ;
import static org.h2.value.Value.TINYINT;
import static org.h2.value.Value.UUID;
import static org.h2.value.Value.VARBINARY;
import static org.h2.value.Value.VARCHAR_IGNORECASE;

public interface SearchRowCodec {
  static byte[] safeEncode(Value v) {
    if (v == null || v == ValueNull.INSTANCE) {
      return new byte[]{0}; // 绌哄€煎浐瀹氱紪鐮?
    }
    switch (v.getValueType()) {
      case CHAR:
      case VARCHAR:
      case CLOB:
      case VARCHAR_IGNORECASE:
      case NUMERIC:
      case JSON:
        byte[] bytes = v.getString().getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(bytes.length + 1)
            .put(bytes)
            .array();
      case BINARY:
      case VARBINARY:
      case BLOB:
      case UUID:
      case JAVA_OBJECT:
        byte[] bytes2 = v.getBytes();
        return ByteBuffer.allocate(bytes2.length + 1)
            .put(bytes2).array();
      case BOOLEAN:
        return new byte[]{(byte) (v.getBoolean() ? 1 : 0)};
      case TINYINT:
      case SMALLINT:
      case INTEGER:
        return forInt(v);
      case DECFLOAT:
        return forDecfloat(v);
      case REAL:
        return forReal(v);
      case DOUBLE:
        return forDouble(v);
      case BIGINT:
      case DATE:
      case TIME:
        return forLong(v);
      case TIME_TZ:
        return forTz((ValueTimeTimeZone) v);
      case TIMESTAMP:
        return forTs((ValueTimestamp) v);
      case TIMESTAMP_TZ:
        return forTsTz((ValueTimestampTimeZone) v);
      case ENUM:
        return forEnum((ValueEnum) v);
      case ARRAY:
      case ROW:
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
        throw new UnsupportedOperationException("Unsupported type: " + v.getClass());
    }
  }

  static byte[] forInt(Value v) {
    return ByteBuffer.allocate(4)
        .putInt(flipSign(v.getInt())).array();
  }

  static byte[] forTz(ValueTimeTimeZone v) {
    ValueTimeTimeZone tz = v;
    // 杞崲鍒?UTC 绾崇
    long utcNanos = tz.getNanos() - tz.getTimeZoneOffsetSeconds() * DateTimeUtils.NANOS_PER_SECOND;
    // 缈昏浆绗﹀彿浣嶏紝淇濊瘉鎺掑簭姝ｇ‘
    long sortable = utcNanos ^ Long.MIN_VALUE;
    return ByteBuffer.allocate(8).putLong(sortable).array();
  }

  static byte[] forTs(ValueTimestamp v) {
    ValueTimestamp ts = v;
    // 鐢?long[] 瀛樹袱涓?long锛歞ateValue + timeNanos
    ByteBuffer buf = ByteBuffer.allocate(16);
    buf.putLong(ts.getDateValue());
    buf.putLong(ts.getTimeNanos());
    return buf.array();
  }

  static byte[] forTsTz(ValueTimestampTimeZone v) {
    ValueTimestampTimeZone tsTz = v;
    // 杞垚 UTC 绾崇锛屽拰 compareTypeSafe 涓€鑷?
    long timeUtc = tsTz.getTimeNanos() - tsTz.getTimeZoneOffsetSeconds() * DateTimeUtils.NANOS_PER_SECOND;
    long dateValue = tsTz.getDateValue();
    if (timeUtc < 0) {
      timeUtc += DateTimeUtils.NANOS_PER_DAY;
      dateValue = DateTimeUtils.decrementDateValue(dateValue);
    } else if (timeUtc >= DateTimeUtils.NANOS_PER_DAY) {
      timeUtc -= DateTimeUtils.NANOS_PER_DAY;
      dateValue = DateTimeUtils.incrementDateValue(dateValue);
    }

    // 鍚堟垚涓€涓?long锛氶珮 32 浣?dateValue锛屼綆 32 浣?timeNanos锛堜篃鍙互鐢?16 瀛楄妭瀛樺畬鏁?long+long锛?
    ByteBuffer buf2 = ByteBuffer.allocate(16);
    buf2.putLong(dateValue);
    buf2.putLong(timeUtc);
    return buf2.array();
  }

  static byte[] forEnum(ValueEnum v) {
    ValueEnum enumValue = v;
    byte[] bytes3 = enumValue.getString().getBytes(StandardCharsets.UTF_8);
    return ByteBuffer.allocate(bytes3.length + 1)
        .putInt(bytes3.length).put(bytes3).array();
  }

  static byte[] forLong(Value v) {
    long sortable = flipSign(v.getLong());  // 缈昏浆绗﹀彿浣?
    return ByteBuffer.allocate(8).putLong(sortable).array();
  }

  static byte[] forDecfloat(Value v) {
    BigDecimal bd = v.getBigDecimal();
    int sign = bd.signum();
    BigInteger unscaled = bd.unscaledValue();
    byte[] unscaledBytes = unscaled.toByteArray();

    ByteBuffer buf = ByteBuffer.allocate(1 + unscaledBytes.length);
    buf.put((byte) (sign + 1)); // -1 -> 0, 0 -> 1, +1 -> 2
    buf.put(unscaledBytes);
    return buf.array();
  }

  static byte[] forDouble(Value v) {
    long bits = Double.doubleToRawLongBits(v.getDouble());
    long sortable;
    if (bits < 0) {
      sortable = ~bits;       // 璐熸暟鎸変綅鍙栧弽
    } else {
      sortable = bits ^ Long.MIN_VALUE; // 姝ｆ暟缈昏浆绗﹀彿浣?
    }
    return ByteBuffer.allocate(8)
        .putDouble(sortable).array();
  }

  static byte[] forReal(Value v) {
    float f = v.getFloat();
    int bits = Float.floatToRawIntBits(f);
    int sortable = bits < 0 ? ~bits : bits ^ Integer.MIN_VALUE;
    return ByteBuffer.allocate(4).putInt(sortable).array();
  }

  /**
   * 绗﹀彿浣嶇炕杞?
   */
  static long flipSign(long v) {
    return KeyCodec.flipSign(v);
  }
  /**
   * 绗﹀彿浣嶇炕杞?
   */
  static int flipSign(int v) {
    return KeyCodec.flipSign(v);
  }

  static byte[] encode(SearchRow row, IndexColumn[]  columns, boolean includeKey)  {
    DynamicByteBuffer out = new DynamicByteBuffer();
    for (IndexColumn column : columns) {
      Value value = row.getValue(column.column.getColumnId());
      if(value!=null){
        out.put(safeEncode(value));
      }
    }
    if(includeKey){
      long sortable = flipSign(row.getKey());  // 缈昏浆绗﹀彿浣?
      out.putLong(sortable);
    }
    return out.toArray();
  }
}
