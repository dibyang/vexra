package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.DataKey;
import net.xdob.vexra.adb.key.Key;
import net.xdob.vexra.adb.key.PrefixKey;

import java.sql.SQLException;

/**
 * 鐗堟湰瑙ｆ瀽鍣ㄦ帴鍙ｏ紝鐢ㄤ簬澶勭悊鏁版嵁搴撲腑鐨勫鐗堟湰骞跺彂鎺у埗锛圡VCC锛?
 * 鎻愪緵鑾峰彇宸叉彁浜ょ増鏈€佸彲瑙佺増鏈互鍙婅寖鍥存煡璇㈢殑鍔熻兘
 */
public interface VersionResolver {
  /**
   * 鑾峰彇鎸囧畾閿殑鏈€鏂板凡鎻愪氦鐗堟湰
   *
   * @param key 鏁版嵁閿?
   * @return 鏈€鏂板凡鎻愪氦鐨勮鍊硷紝濡傛灉涓嶅瓨鍦ㄥ垯杩斿洖null
   * @throws SQLException 鏁版嵁搴撹闂紓甯?
   */
  RowValue getLatestCommitted(Key key) throws SQLException;

  /**
   * 鑾峰彇浜嬪姟鍙鐨勬渶鏂扮増鏈暟鎹?
   *
   * @param txn 褰撳墠浜嬪姟涓婁笅鏂?
   * @param key 鏁版嵁閿?
   * @return 浜嬪姟鍙鐨勮鍊硷紝濡傛灉涓嶅瓨鍦ㄥ垯杩斿洖null
   */
  RowValue getVisible(Transaction2 txn, DataKey key) ;

  /**
   * 鑾峰彇鎸囧畾鏃堕棿鎴充箣鍓嶆渶鏂板凡鎻愪氦鐨勭増鏈?
   *
   * @param key 鏁版嵁閿?
   * @param startTs 璧峰鏃堕棿鎴?
   * @return 鎸囧畾鏃堕棿鎴冲墠鏈€鏂板凡鎻愪氦鐨勮鍊硷紝濡傛灉涓嶅瓨鍦ㄥ垯杩斿洖null
   * @throws SQLException 鏁版嵁搴撹闂紓甯?
   */
  RowValue getLatestCommittedBefore(DataKey key, long startTs) throws SQLException;

  /**
   * 鑾峰彇鎸囧畾鍓嶇紑閿寖鍥村唴鐨勭涓€涓敭鍊煎
   *
   * @param txn 褰撳墠浜嬪姟涓婁笅鏂?
   * @param prefixKey 鍓嶇紑閿紝鐢ㄤ簬鑼冨洿鏌ヨ
   * @return 鑼冨洿鍐呯殑绗竴涓鍊硷紝濡傛灉涓嶅瓨鍦ㄥ垯杩斿洖null
   * @throws SQLException 鏁版嵁搴撹闂紓甯?
   */
  RowValue first(Transaction2 txn, PrefixKey prefixKey) throws SQLException;

  /**
   * 鑾峰彇鎸囧畾鍓嶇紑閿寖鍥村唴鐨勬渶鍚庝竴涓敭鍊煎
   *
   * @param txn 褰撳墠浜嬪姟涓婁笅鏂?
   * @param prefixKey 鍓嶇紑閿紝鐢ㄤ簬鑼冨洿鏌ヨ
   * @return 鑼冨洿鍐呯殑鏈€鍚庝竴涓鍊硷紝濡傛灉涓嶅瓨鍦ㄥ垯杩斿洖null
   * @throws SQLException 鏁版嵁搴撹闂紓甯?
   */
  RowValue last(Transaction2 txn, PrefixKey prefixKey) throws SQLException;
}
