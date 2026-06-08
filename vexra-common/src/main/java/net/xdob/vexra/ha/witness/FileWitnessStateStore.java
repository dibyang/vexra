package net.xdob.vexra.ha.witness;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Objects;
import java.util.Properties;

/**
 * 基于本地文件的 witness 仲裁状态持久化实现。
 *
 * <p>每个虚节点独立保存一个 properties 文件。文件名使用 URL-safe Base64 编码，
 * 避免虚节点标识被解释为路径。保存时先写临时文件，再尽量原子替换目标文件。</p>
 */
public final class FileWitnessStateStore implements WitnessStateStore {
  private static final String CURRENT_TERM = "currentTerm";
  private static final String VOTED_FOR = "votedFor";
  private static final String ACCEPTED_EPOCH = "acceptedEpoch";
  private static final String COMMIT_INDEX = "commitIndex";
  private static final String LEASE_OWNER = "leaseOwner";
  private static final String LEASE_EXPIRE_AT_MILLIS = "leaseExpireAtMillis";

  private final Path rootDir;

  /**
   * 创建文件 witness 状态存储。
   *
   * @param rootDir 状态文件根目录
   */
  public FileWitnessStateStore(Path rootDir) {
    this.rootDir = Objects.requireNonNull(rootDir, "rootDir == null");
  }

  @Override
  public WitnessState load(String virtualNodeId) throws IOException {
    WitnessState.empty(virtualNodeId);
    Path path = statePath(virtualNodeId);
    if (!Files.exists(path)) {
      return WitnessState.empty(virtualNodeId);
    }
    Properties properties = new Properties();
    try (InputStream input = Files.newInputStream(path)) {
      properties.load(input);
    }
    return new WitnessState(virtualNodeId,
        longProperty(properties, CURRENT_TERM),
        properties.getProperty(VOTED_FOR, ""),
        longProperty(properties, ACCEPTED_EPOCH),
        longProperty(properties, COMMIT_INDEX),
        properties.getProperty(LEASE_OWNER, ""),
        longProperty(properties, LEASE_EXPIRE_AT_MILLIS));
  }

  @Override
  public void save(WitnessState state) throws IOException {
    Objects.requireNonNull(state, "state == null");
    Files.createDirectories(rootDir);
    Path path = statePath(state.getVirtualNodeId());
    Path temp = Files.createTempFile(rootDir, "witness-", ".tmp");

    Properties properties = new Properties();
    properties.setProperty(CURRENT_TERM, Long.toString(state.getCurrentTerm()));
    properties.setProperty(VOTED_FOR, state.getVotedFor());
    properties.setProperty(ACCEPTED_EPOCH, Long.toString(state.getAcceptedEpoch()));
    properties.setProperty(COMMIT_INDEX, Long.toString(state.getCommitIndex()));
    properties.setProperty(LEASE_OWNER, state.getLeaseOwner());
    properties.setProperty(LEASE_EXPIRE_AT_MILLIS,
        Long.toString(state.getLeaseExpireAtMillis()));

    try (OutputStream output = Files.newOutputStream(temp)) {
      properties.store(output, "vexra witness state");
    }
    try {
      Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private Path statePath(String virtualNodeId) {
    String encoded = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(WitnessState.empty(virtualNodeId)
            .getVirtualNodeId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return rootDir.resolve(encoded + ".properties");
  }

  private static long longProperty(Properties properties, String key) {
    return Long.parseLong(properties.getProperty(key, "0"));
  }
}
