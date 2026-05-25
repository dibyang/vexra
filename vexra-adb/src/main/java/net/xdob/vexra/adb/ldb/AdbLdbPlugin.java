package net.xdob.vexra.adb.ldb;

import net.xdob.vexra.adb.db.CF;
import net.xdob.vexra.ldb.DBException;
import net.xdob.vexra.ldb.LdbColumnFamily;
import net.xdob.vexra.ldb.LdbPlugin;
import net.xdob.vexra.ldb.LdbPluginContext;
import net.xdob.vexra.ldb.Options;

import java.util.List;

/**
 * ADB 使用 LDB 的插件入口，负责声明 ADB 需要的列族并保存受控的 LDB 上下文。
 *
 * 该插件不直接持有底层 LDB 实例，避免越过 LDB 生命周期管理。
 */
public class AdbLdbPlugin implements LdbPlugin {
  private final LdbColumnFamily defaultColumnFamily = LdbColumnFamily.DEFAULT;
  private final LdbColumnFamily metaColumnFamily = new AdbColumnFamily(CF.META);
  private final LdbColumnFamily txnColumnFamily = new AdbColumnFamily(CF.TXN);
  private LdbPluginContext context;

  /**
   * 注册 ADB 所需的 META 和 TXN 列族。
   */
  @Override
  public void configure(Options options) throws DBException {
    addColumnFamilyIfAbsent(options, metaColumnFamily);
    addColumnFamilyIfAbsent(options, txnColumnFamily);
  }

  /**
   * 保存 LDB 打开后的受控上下文，供后续 ADB 扩展能力使用。
   */
  @Override
  public void onOpen(LdbPluginContext context) throws DBException {
    this.context = context;
  }

  public LdbColumnFamily getDefaultColumnFamily() {
    return defaultColumnFamily;
  }

  public LdbColumnFamily getMetaColumnFamily() {
    return metaColumnFamily;
  }

  public LdbColumnFamily getTxnColumnFamily() {
    return txnColumnFamily;
  }

  public LdbPluginContext getContext() {
    return context;
  }

  private static void addColumnFamilyIfAbsent(Options options, LdbColumnFamily columnFamily) {
    List<LdbColumnFamily> families = options.getColumnFamilies();
    for (LdbColumnFamily family : families) {
      if (family.getId() == columnFamily.getId()) {
        return;
      }
    }
    options.addColumnFamily(columnFamily);
  }

  private static class AdbColumnFamily implements LdbColumnFamily {
    private final CF cf;

    private AdbColumnFamily(CF cf) {
      this.cf = cf;
    }

    @Override
    public int getId() {
      return cf.getCfId();
    }

    @Override
    public String getName() {
      return cf.name();
    }
  }
}
