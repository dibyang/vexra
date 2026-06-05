package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.DataKey;

/**
 * 鍙琛岃В鏋愬櫒鎺ュ彛锛岀敤浜庤幏鍙栦簨鍔″彲瑙佺殑鏁版嵁琛?
 */
public interface VisibleRowResolver {
  /**
   * 鑾峰彇浜嬪姟鍙鐨勬渶鏂扮増鏈暟鎹
   *
   * @param txn 褰撳墠浜嬪姟涓婁笅鏂?
   * @param dataKey 鏁版嵁閿?
   * @return 浜嬪姟鍙鐨勮鍊硷紝濡傛灉涓嶅瓨鍦ㄥ垯杩斿洖null
   */
  RowValue getVisible(Transaction2 txn, DataKey dataKey);
}
