package net.xdob.vexra.adb.ha2;

import net.xdob.vexra.RaftConfigKeys;
import net.xdob.vexra.adb.AdbStateMachine;
import net.xdob.vexra.conf.Parameters;
import net.xdob.vexra.conf.RaftProperties;
import net.xdob.vexra.grpc.GrpcConfigKeys;
import net.xdob.vexra.rpc.SupportedRpcType;
import net.xdob.vexra.server.RaftServer;
import net.xdob.vexra.server.config.RaftServerConfigKeys;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;

/**
 * ADB region node 的产品级 JVM 入口。
 *
 * <p>该入口负责把命令行参数转换为 {@link AdbRegionNodeConfig}，构造真实 {@link RaftServer}，
 * 启动 ADB 状态机，并通过可选 ready/stop 文件对接本地 smoke 或外部进程管理器。它不负责服务发现、
 * TLS 证书加载和容器/systemd 模板，这些继续由部署系统完成。</p>
 */
public final class AdbRegionNodeMain {
  /** ADB region node 默认 main class 名称，供部署计划生成启动命令使用。 */
  public static final String MAIN_CLASS = AdbRegionNodeMain.class.getName();

  private AdbRegionNodeMain() {
  }

  /**
   * 启动 ADB region node 进程。
   *
   * @param args `--key value` 形式的启动参数
   */
  public static void main(String[] args) {
    try {
      run(AdbRegionNodeConfig.parse(args));
    } catch (Throwable t) {
      t.printStackTrace(System.err);
      System.err.flush();
      System.exit(1);
    }
  }

  /**
   * 按配置构造并启动 RaftServer，随后等待外部关闭信号。
   *
   * @param config region node 启动配置
   * @throws Exception 启动、ready 文件写入或等待关闭过程中发生异常时抛出
   */
  public static void run(AdbRegionNodeConfig config) throws Exception {
    RaftServer server = newServer(config);
    try {
      server.start();
      writeReadyFile(config.getReadyFile(), config.getNodeId());
      waitForShutdownSignal(config.getStopFile());
    } finally {
      server.close();
    }
  }

  /**
   * 创建未启动的 ADB RaftServer。
   *
   * @param config region node 启动配置
   * @return 未启动的 RaftServer，调用方负责 start 和 close
   * @throws IOException server 构造失败时抛出
   */
  public static RaftServer newServer(AdbRegionNodeConfig config)
      throws IOException {
    RaftProperties properties = new RaftProperties();
    RaftConfigKeys.Rpc.setType(properties, SupportedRpcType.GRPC);
    GrpcConfigKeys.Server.setHost(properties, config.getHost());
    GrpcConfigKeys.Server.setPort(properties, config.getPort());
    RaftServerConfigKeys.setStorageDir(properties,
        Collections.singletonList(config.getStorageDir()));
    RaftServerConfigKeys.setCacheDir(properties, config.getCacheDir());

    return RaftServer.newBuilder()
        .setServerId(config.getSelfPeer().getId())
        .setGroup(config.raftGroup())
        .setProperties(properties)
        .setParameters(new Parameters())
        .setStateMachineRegistry(gid -> new AdbStateMachine(gid,
            config.getSelfPeer().getId()))
        .build();
  }

  private static void writeReadyFile(Path readyFile, String nodeId)
      throws IOException {
    if (readyFile == null) {
      return;
    }
    Path parent = readyFile.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.write(readyFile, Collections.singletonList("ready " + nodeId),
        StandardCharsets.UTF_8, StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
  }

  private static void waitForShutdownSignal(Path stopFile)
      throws InterruptedException {
    if (stopFile == null) {
      while (true) {
        Thread.sleep(1000L);
      }
    }
    while (!Files.exists(stopFile)) {
      Thread.sleep(200L);
    }
  }
}
