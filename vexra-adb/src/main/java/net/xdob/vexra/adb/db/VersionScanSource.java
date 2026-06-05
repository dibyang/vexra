package net.xdob.vexra.adb.db;

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
   * 鑾峰彇褰撳墠浣嶇疆鐨勫€?
   *
   * @return 褰撳墠鍊肩殑瀛楄妭鏁扮粍锛屽鏋滀綅缃棤鏁堝垯杩斿洖null
   */
  byte[] value();
  /**
   * 灏嗚凯浠ｅ櫒绉诲姩鍒颁笅涓€涓綅缃?
   * 鏍规嵁鎵弿鏂瑰悜鍐冲畾鏄悜鍓嶈繕鏄悜鍚庣Щ鍔?
   */
  void advance();
}
