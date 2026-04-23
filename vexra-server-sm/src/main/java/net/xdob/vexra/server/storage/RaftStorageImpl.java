package net.xdob.vexra.server.storage;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicReference;

import net.xdob.vexra.proto.raft.LogEntryProto;
import net.xdob.vexra.protocol.RaftPeerId;
import net.xdob.vexra.server.RaftServer;
import net.xdob.vexra.server.RaftConfiguration;
import net.xdob.vexra.server.config.CorruptionPolicy;
import net.xdob.vexra.server.raftlog.LogProtoUtils;
import net.xdob.vexra.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/** The storage of a {@link RaftServer}. */
public class RaftStorageImpl implements RaftStorage {
  final Logger LOG = LoggerFactory.getLogger(RaftStorageImpl.class);
	private static final String CHECK_NAME = ".check";
	private static final String JVM_NAME = ManagementFactory.getRuntimeMXBean().getName();

	private final RaftStorageDirectoryImpl storageDir;
  private final StartupOption startupOption;
  private final CorruptionPolicy logCorruptionPolicy;
  private volatile StorageState state = StorageState.UNINITIALIZED;
  private final MetaFile metaFile = new MetaFile();
  private final File dirCache;
  private final RaftPeerId peerId;

  RaftStorageImpl(File dir, SizeInBytes freeSpaceMin, StartupOption option, CorruptionPolicy logCorruptionPolicy, File dirCache, RaftPeerId peerId) {

    this.storageDir = new RaftStorageDirectoryImpl(dir, freeSpaceMin);
    this.logCorruptionPolicy = Optional.ofNullable(logCorruptionPolicy).orElseGet(CorruptionPolicy::getDefault);
    this.startupOption = option;
    this.dirCache = dirCache;
    this.peerId = peerId;
    LOG.debug("newRaftStorage: {}, freeSpaceMin={}, option={}, logCorruptionPolicy={}, peerId={}",
        dir, freeSpaceMin, option, logCorruptionPolicy, peerId);
  }

  @Override
  public RaftPeerId getPeerId() {
    return peerId;
  }

  public File getDirCache() {
    return dirCache;
  }

  @Override
  public void initialize() throws IOException {
    try {
      if (startupOption == StartupOption.FORMAT) {
        if (storageDir.analyzeStorage(false) == StorageState.NON_EXISTENT) {
          throw new IOException("Cannot format " + storageDir);
        }
        storageDir.lock();
        format();
        state = storageDir.analyzeStorage(false);
      } else {
        // metaFile is initialized here
        state = analyzeAndRecoverStorage(true);
      }
    } catch (Throwable t) {
      unlockOnFailure(storageDir);
      throw t;
    }

    if (state != StorageState.NORMAL) {
      unlockOnFailure(storageDir);
      throw new IOException("Failed to load " + storageDir + ": " + state);
    }
  }

	void unlockOnFailure(RaftStorageDirectoryImpl dir) {
    try {
      dir.unlock();
    } catch (Throwable t) {
      LOG.warn("Failed to unlock " + dir, t);
    }
  }

  StorageState getState() {
    return state;
  }

  public CorruptionPolicy getLogCorruptionPolicy() {
    return logCorruptionPolicy;
  }

  private void format() throws IOException {
    storageDir.clearDirectory();
    metaFile.set(storageDir.getMetaFile()).persist(RaftStorageMetadata.getDefault());
    LOG.info("Storage directory {} has been successfully formatted.", storageDir.getRoot());
  }

  private void cleanMetaTmpFile() throws IOException {
    FileUtils.deleteIfExists(storageDir.getMetaTmpFile());
  }

  private StorageState analyzeAndRecoverStorage(boolean toLock) throws IOException {
    StorageState storageState = storageDir.analyzeStorage(toLock);
    // Existence of raft-meta.tmp means the change of votedFor/term has not
    // been committed. Thus we should delete the tmp file.
    if (storageState != StorageState.NON_EXISTENT) {
      cleanMetaTmpFile();
    }
    if (storageState == StorageState.NORMAL) {
      final File f = storageDir.getMetaFile();
      if (!f.exists()) {
        throw new FileNotFoundException("Metadata file " + f + " does not exists.");
      }
      final RaftStorageMetadata metadata = metaFile.set(f).getMetadata();
      LOG.info("Read {} from {}", metadata, f);
      return StorageState.NORMAL;
    } else if (storageState == StorageState.NOT_FORMATTED &&
        storageDir.isCurrentEmpty()) {
      format();
      return StorageState.NORMAL;
    } else {
      return storageState;
    }
  }

  @Override
  public RaftStorageDirectory getStorageDir() {
    return storageDir;
  }

  @Override
  public void close() throws IOException {
    storageDir.unlock();
  }

  @Override
  public RaftStorageMetadataFile getMetadataFile() {
    return metaFile.get();
  }

  public void writeRaftConfiguration(LogEntryProto conf) {
    File confFile = storageDir.getMetaConfFile();
    try (OutputStream fio = new AtomicFileOutputStream(confFile)) {
      conf.writeTo(fio);
    } catch (Exception e) {
      LOG.error("Failed writing configuration to file:" + confFile, e);
    }
  }

  public RaftConfiguration readRaftConfiguration() {
    File confFile = storageDir.getMetaConfFile();
    if (!confFile.exists()) {
      return null;
    } else {
      try (InputStream fio = FileUtils.newInputStream(confFile)) {
        LogEntryProto confProto = LogEntryProto.newBuilder().mergeFrom(fio).build();
        return LogProtoUtils.toRaftConfiguration(confProto);
      } catch (Exception e) {
        LOG.error("Failed reading configuration from file:" + confFile, e);
        return null;
      }
    }
  }

  @Override
  public String toString() {
    return JavaUtils.getClassSimpleName(getClass()) + ":" + getStorageDir();
  }

  static class MetaFile {
    private final AtomicReference<RaftStorageMetadataFileImpl> ref = new AtomicReference<>();

    RaftStorageMetadataFile get() {
      return ref.get();
    }

    RaftStorageMetadataFile set(File file) {
      final RaftStorageMetadataFileImpl impl = new RaftStorageMetadataFileImpl(file);
      ref.set(impl);
      return impl;
    }
  }
}
