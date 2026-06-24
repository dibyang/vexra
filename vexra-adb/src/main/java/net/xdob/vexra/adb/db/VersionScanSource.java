package net.xdob.vexra.adb.db;

import net.xdob.vexra.ldb.util.Slice;

/**
 * 鐗堟湰鎵弿婧愭帴鍙ｏ紝鎻愪緵鏁版嵁鐗堟湰鎵弿鐨勫姛鑳?
 * 鏀寔鑼冨洿鏌ヨ銆佽凯浠ｉ亶鍘嗙瓑鎿嶄綔
 */
public interface VersionScanSource extends AutoCloseable {
  /**
   * 鑾峰彇鎵弿鏂瑰悜
   *
   * @return 鎵弿鏂瑰悜鏋氫妇鍊?
   */
  ScanDirection direction();
  /**
   * 瀹氫綅鍒版寚瀹氳寖鍥寸殑璧峰浣嶇疆
   *
   * @param lowerInclusive 鑼冨洿涓嬬晫锛堝寘鍚級
   * @param upperExclusive 鑼冨洿涓婄晫锛堜笉鍖呭惈锛?
   */
  void seekToRangeStart(byte[] lowerInclusive, byte[] upperExclusive);
  /**
   * 定位到闭区间上界范围的起始位置。
   *
   * <p>默认实现把闭区间上界转换为后继独占上界，供不直接支持 closed seek 的存储实现复用。
   * 支持原生闭区间 seek 的实现可以覆盖该方法，避免生成额外边界 key。</p>
   *
   * @param lowerInclusive 范围下界，包含
   * @param upperInclusive 范围上界，包含；null 表示无上界
   */
  default void seekToRangeClosed(byte[] lowerInclusive, byte[] upperInclusive) {
    seekToRangeStart(lowerInclusive, KeyCodec.prefixEnd(upperInclusive));
  }
  /**
   * 妫€鏌ュ綋鍓嶈凯浠ｅ櫒浣嶇疆鏄惁鏈夋晥
   *
   * @return 濡傛灉褰撳墠浣嶇疆鏈夋晥杩斿洖true锛屽惁鍒欒繑鍥瀎alse
   */
  boolean isValid();
  /**
   * 鑾峰彇褰撳墠浣嶇疆鐨勯敭
   *
   * @return 褰撳墠閿殑瀛楄妭鏁扮粍锛屽鏋滀綅缃棤鏁堝垯杩斿洖null
   */
  byte[] key();
  /**
   * 返回当前 key 的低分配视图。
   *
   * <p>默认实现基于 {@link #key()} 构造视图，旧存储实现可以保持兼容；LDB 这类支持
   * view 的实现会覆盖该方法，避免每次扫描都复制 key 字节。</p>
   *
   * @return 当前 key 的只读视图，游标无效时返回 null
   */
  default Slice keyView() {
    byte[] key = key();
    return key == null ? null : new Slice(key);
  }
  /**
   * 鑾峰彇褰撳墠浣嶇疆鐨勫€?
   *
   * @return 褰撳墠鍊肩殑瀛楄妭鏁扮粍锛屽鏋滀綅缃棤鏁堝垯杩斿洖null
   */
  byte[] value();
  /**
   * 返回当前 value 的低分配视图。
   *
   * <p>返回值只保证在游标移动或关闭前有效。默认实现保持旧 byte[] 语义，支持 view
   * 的存储实现应覆盖该方法。</p>
   *
   * @return 当前 value 的只读视图，游标无效时返回 null
   */
  default Slice valueView() {
    byte[] value = value();
    return value == null ? null : new Slice(value);
  }
  /**
   * 判断当前 key 是否以指定前缀开头。
   *
   * @param prefix 前缀字节
   * @return 当前 key 命中前缀时返回 true
   */
  default boolean keyStartsWith(byte[] prefix) {
    Slice key = keyView();
    if (key == null || prefix == null || prefix.length > key.length()) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (key.getByte(i) != prefix[i]) {
        return false;
      }
    }
    return true;
  }
  /**
   * 判断当前 key 是否仍位于独占上界之前。
   *
   * @param upperExclusive 独占上界，null 表示无上界
   * @return 当前 key 未越过上界时返回 true
   */
  default boolean isKeyBefore(byte[] upperExclusive) {
    if (upperExclusive == null) {
      return true;
    }
    Slice key = keyView();
    return key != null && key.compareTo(new Slice(upperExclusive)) < 0;
  }
  /**
   * 从当前位置开始统计剩余物理记录数，并把游标推进到失效位置。
   *
   * <p>该方法只表示当前扫描范围内的 KV 条目数量，不等同于 ADB 的 MVCC 可见逻辑行数。
   * 默认实现逐条 {@link #advance()}，支持底层低分配计数的实现应覆盖该方法。</p>
   *
   * @return 从当前位置到当前扫描边界结束的物理记录数
   */
  default long countRemaining() {
    long count = 0L;
    while (isValid()) {
      count++;
      advance();
    }
    return count;
  }
  /**
   * 灏嗚凯浠ｅ櫒绉诲姩鍒颁笅涓€涓綅缃?
   * 鏍规嵁鎵弿鏂瑰悜鍐冲畾鏄悜鍓嶈繕鏄悜鍚庣Щ鍔?
   */
  void advance();
}
