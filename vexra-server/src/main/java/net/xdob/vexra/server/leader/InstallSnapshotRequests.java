package net.xdob.vexra.server.leader;

import net.xdob.vexra.proto.raft.FileChunkProto;
import net.xdob.vexra.proto.raft.InstallSnapshotRequestProto;
import net.xdob.vexra.proto.raft.InstallSnapshotRequestProto.SnapshotChunkProto;
import net.xdob.vexra.protocol.RaftPeerId;
import net.xdob.vexra.server.Division;
import net.xdob.vexra.server.storage.FileChunkReader;
import net.xdob.vexra.server.storage.FileInfo;
import net.xdob.vexra.server.storage.RaftStorageDirectory;
import net.xdob.vexra.statemachine.SnapshotInfo;
import net.xdob.vexra.util.JavaUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Function;


/**
 * 用于发送快照的 {@link InstallSnapshotRequestProto} 的 {@link Iterable}。
 * <p>
 * 快照通过一个或多个请求发送，其中
 * 一个快照有一个或多个文件，以及
 * 一个文件通过一个或多个数据块发送。
 * 请求的数量等于每个文件的数据块数量之和。
 */
class InstallSnapshotRequests implements Iterable<InstallSnapshotRequestProto> {
	static final Logger LOG = LoggerFactory.getLogger(InstallSnapshotRequests.class);
  private final Division server;
  private final RaftPeerId followerId;
  private final Function<FileInfo, Path> getRelativePath;

  /** The snapshot to be sent. */
  private final SnapshotInfo snapshot;
  /** A fixed id for all the requests. */
  private final String requestId;

  /** Maximum chunk size. */
  private final int snapshotChunkMaxSize;
  /** The total size of snapshot files. */
  private final long totalSize;
  /** The total number of snapshot files. */
  private final int numFiles;


  InstallSnapshotRequests(Division server, RaftPeerId followerId,
                          String requestId, SnapshotInfo snapshot, int snapshotChunkMaxSize) {
    this.server = server;
    this.followerId = followerId;
    this.requestId = requestId;
    this.snapshot = snapshot;
    this.snapshotChunkMaxSize = snapshotChunkMaxSize;
    final List<FileInfo> files = snapshot.getFiles();
    this.totalSize = files.stream().mapToLong(FileInfo::getFileSize).reduce(Long::sum).orElseThrow(
            () -> new IllegalStateException("Failed to compute total size for snapshot " + snapshot));
    this.numFiles = files.size();
    final File snapshotDir = server.getStateMachine().getStateMachineStorage().getSnapshotDir();
    final Function<Path, Path> relativize;
    if (snapshotDir != null) {
      final Path dir = snapshotDir.toPath();
      // add STATE_MACHINE_DIR_NAME for compatibility.
      relativize = p -> new File(RaftStorageDirectory.STATE_MACHINE_DIR_NAME, dir.relativize(p).toString()).toPath();
    } else {
      final Path dir = server.getRaftStorage().getStorageDir().getRoot().toPath();
      relativize = dir::relativize;
    }
    this.getRelativePath = info -> Optional.of(info.getPath())
        .filter(Path::isAbsolute)
        .map(relativize)
        .orElseGet(info::getPath);
  }

  @Override
  public Iterator<InstallSnapshotRequestProto> iterator() {
    return new Iter();
  }

  private class Iter implements Iterator<InstallSnapshotRequestProto> {

    /** The index of the current request. */
    private int requestIndex = 0;
    /** The index of the current file. */
    private int fileIndex = 0;
    /** The current file. */
    private FileChunkReader current;

    @Override
    public boolean hasNext() {
      return fileIndex < numFiles;
    }

    @Override
    public InstallSnapshotRequestProto next() {
      if (!hasNext()) {
        throw new NoSuchElementException("fileIndex = " + fileIndex + " >= numFiles = " + numFiles);
      }

      final FileInfo info = snapshot.getFiles().get(fileIndex);
      try {
				LOG.info("Reading chunk for file {} size={}", info, info.getFileSize());
        if (current == null) {
          current = new FileChunkReader(info, getRelativePath.apply(info));
        }
        final FileChunkProto chunk = current.readFileChunk(snapshotChunkMaxSize);
        if (chunk.getDone()) {
          current.close();
          current = null;
          fileIndex++;
        }

        final boolean done = fileIndex == numFiles && chunk.getDone();
        return newInstallSnapshotRequest(chunk, done);
      } catch (IOException e) {
        if (current != null) {
          try {
            current.close();
            current = null;
          } catch (IOException ignored) {
          }
        }
        throw new IllegalStateException("Failed to iterate installSnapshot requests: " + this, e);
      }
    }

    private InstallSnapshotRequestProto newInstallSnapshotRequest(FileChunkProto chunk, boolean done) {
      synchronized (server) {
        final SnapshotChunkProto.Builder b = LeaderProtoUtils.toSnapshotChunkProtoBuilder(
            requestId, requestIndex++, snapshot.getTermIndex(), chunk, totalSize, done);
        return LeaderProtoUtils.toInstallSnapshotRequestProto(server, followerId, b);
      }
    }
  }

  @Override
  public String toString() {
    return server.getId() + "->" + followerId + JavaUtils.getClassSimpleName(getClass())
        + ": requestId=" + requestId
        + ", snapshot=" + snapshot;
  }
}
