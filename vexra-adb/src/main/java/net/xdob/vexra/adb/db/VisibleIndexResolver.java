package net.xdob.vexra.adb.db;

/**
 * 鍙绱㈠紩瑙ｆ瀽鍣ㄦ帴鍙ｏ紝鐢ㄤ簬鑾峰彇浜嬪姟鍙鐨勭储寮曟暟鎹?
 */
public interface VisibleIndexResolver {
  /**
   * 鑾峰彇浜嬪姟鍙鐨勭储寮曟暟鎹?
   *
   * @param txn 褰撳墠浜嬪姟涓婁笅鏂?
   * @param logicalPrefix 閫昏緫鍓嶇紑瀛楄妭鏁扮粍
   * @return 浜嬪姟鍙鐨勭储寮曡鍊硷紝濡傛灉涓嶅瓨鍦ㄥ垯杩斿洖null
   */
  RowValue getVisibleIndex(Transaction2 txn, byte[] logicalPrefix);
}
