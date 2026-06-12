package net.xdob.vexra.adb;

import com.google.protobuf.ByteString;
import net.xdob.vexra.adb.db.*;
import net.xdob.vexra.adb.db.Meta;
import net.xdob.vexra.adb.key.DataKey;
import net.xdob.vexra.proto.adb.*;
import net.xdob.vexra.proto.sm.WrapReplyProto;
import net.xdob.vexra.proto.sm.WrapRequestProto;
import net.xdob.vexra.protocol.RaftGroupId;
import net.xdob.vexra.protocol.RaftPeerId;
import net.xdob.vexra.server.RaftServer;
import net.xdob.vexra.server.protocol.TermIndex;
import net.xdob.vexra.server.storage.FileInfo;
import net.xdob.vexra.server.storage.RaftStorage;
import net.xdob.vexra.statemachine.SnapshotInfo;
import net.xdob.vexra.statemachine.TransactionContext;
import net.xdob.vexra.statemachine.impl.FileListStateMachineStorage;
import net.xdob.vexra.statemachine.impl.SMPlugin;
import net.xdob.vexra.statemachine.impl.SMPluginContext;
import net.xdob.vexra.util.Proto2Util;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.*;

public class AdbSMPlugin implements SMPlugin {

  public static final String ADB = "adb";
  private final RaftPeerId peerId;
  private SMPluginContext context;
  private DbStore store;
  private Path dbPath;

  public AdbSMPlugin(RaftPeerId peerId) {
      this.peerId = peerId;
  }

  @Override
  public String getId() {
    return ADB;
  }

  @Override
  public void initialize(RaftServer server, RaftGroupId groupId, RaftPeerId peerId, RaftStorage raftStorage) throws IOException {
    //初始化数据库
    LOG.info("build db cache dir groupId={}, peerId={}", groupId, peerId);
    this.dbPath = Paths.get(raftStorage.getDirCache().getPath(),  groupId.getId(), "db", peerId.getId());
    if(!dbPath.toFile().exists()){
      dbPath.toFile().mkdirs();
    }
    this.store = DbStoreEngine.getOrCreate(DbStoreType.LDB, dbPath.toString(), new Properties());
  }

  @Override
  public void setSMPluginContext(SMPluginContext context) {
    this.context = context;
  }

  @Override
  public void startTransaction(TransactionContext transactionContext, WrapRequestProto request) throws SQLException {
    if(!request.hasWriteRequest()) {
      throw new SQLException("write request is null");
    }
  }

  @Override
  public void reinitialize() throws IOException {

  }

  @Override
  public void query(WrapRequestProto request, WrapReplyProto.Builder reply) {
    ReadRequest readRequest = request.getReadRequest();
    ReadResponse.Builder builder = ReadResponse.newBuilder();

    try {
      builder.setSuccess(true);

      if (readRequest.hasGet()) {
        GetResult.Builder getResult = GetResult.newBuilder();
        Get get = readRequest.getGet();
        byte cfId = CF.of(get.getCf().getNumber()).getCfId();
        byte[] value = store.get(cfId, get.getKey().toByteArray());
        if (value != null) {
          getResult.setFound(true);
          getResult.setValue(ByteString.copyFrom(value));
        } else {
          getResult.setFound(false);
        }
        builder.setGetResult(getResult.build());

      } else if (readRequest.hasExists()) {
        ExistsResult.Builder existsResult = ExistsResult.newBuilder();
        Exists exists = readRequest.getExists();
        byte cfId = CF.of(exists.getCf().getNumber()).getCfId();
        byte[] value = store.get(cfId, exists.getKey().toByteArray());
        existsResult.setExists(value != null);
        builder.setExistsResult(existsResult.build());

      } else if (readRequest.hasScan()) {
        Scan scan = readRequest.getScan();
        byte cfId = CF.of(scan.getCf().getNumber()).getCfId();

        ScanDirection direction = fromProtoDirection(scan.getDirection());
        int limit = scan.getLimit() > 0 ? scan.getLimit() : 256;

        byte[] startKey = scan.getStartKey().isEmpty() ? null : scan.getStartKey().toByteArray();
        byte[] endKey = scan.getEndKey().isEmpty() ? null : scan.getEndKey().toByteArray();
        byte[] resumeKey = scan.getResumeKey().isEmpty() ? null : scan.getResumeKey().toByteArray();

        ScanResult.Builder scanResult = ScanResult.newBuilder();

        byte[] lowerInclusive;
        byte[] upperExclusive;
        boolean skipFirstIfEqualsResume = false;

        if (direction == ScanDirection.FORWARD) {
          // 正向：
          // 首扫 [startKey, endKey)
          // 续扫 [resumeKey, endKey) 然后跳过等于 resumeKey 的第一条
          lowerInclusive = (resumeKey != null) ? resumeKey : startKey;
          upperExclusive = endKey;
          skipFirstIfEqualsResume = resumeKey != null;
        } else {
          // 反向：
          // 首扫 [startKey, endKey)
          // 续扫 [startKey, resumeKey) ，这样天然排除 resumeKey
          lowerInclusive = startKey;
          upperExclusive = (resumeKey != null) ? resumeKey : endKey;
        }

        byte[] lastKey = null;
        boolean hasMore = false;
        int count = 0;

        try (VersionScanSource scanSource = store.openVersionScanSource(cfId, direction)) {
          scanSource.seekToRangeStart(lowerInclusive, upperExclusive);

          if (skipFirstIfEqualsResume && scanSource.isValid()) {
            byte[] k = scanSource.key();
            if (KeyCodec.equals(k, resumeKey)) {
              scanSource.advance();
            }
          }

          while (scanSource.isValid() && count < limit) {
            byte[] k = scanSource.key();
            byte[] v = scanSource.value();

            scanResult.addEntries(
                KvPair.newBuilder()
                    .setKey(ByteString.copyFrom(k))
                    .setValue(ByteString.copyFrom(v))
                    .build());

            lastKey = k;
            count++;

            scanSource.advance();
          }

          if (scanSource.isValid()) {
            hasMore = true;
          }
        }

        scanResult.setHasMore(hasMore);
        if (hasMore && lastKey != null) {
          scanResult.setResumeKey(ByteString.copyFrom(lastKey));
        }

        builder.setScanResult(scanResult.build());
      } else if (readRequest.hasRegionScan()) {
        builder.setRegionScanResult(AdbRegionScanReader.scan(store,
            readRequest.getRegionScan()));
      } else {
        builder.setSuccess(false);
        builder.setEx(Proto2Util.toThrowable2Proto(
            new SQLException("Unsupported read request")));
      }

    } catch (Exception e) {
      builder.setSuccess(false);
      builder.setEx(Proto2Util.toThrowable2Proto(e));
    }

    reply.setReadResponse(builder.build());
  }

  private static ScanDirection fromProtoDirection(Direction direction) {
    if (Objects.requireNonNull(direction) == Direction.DIR_REVERSE) {
      return ScanDirection.REVERSE;
    }
    return ScanDirection.FORWARD;
  }


  @Override
  public void applyTransaction(TermIndex termIndex, WrapRequestProto request, WrapReplyProto.Builder reply) {
    WriteRequest writeRequest = request.getWriteRequest();
    WriteResponse.Builder builder = WriteResponse.newBuilder();
    try {
      if(writeRequest.hasBatch()){
        store.writeBatch(b -> {
          Batch batch = writeRequest.getBatch();
          for (WriteEntry entry : batch.getEntriesList()) {
            byte cfId = CF.of(entry.getCf().getNumber()).getCfId();
            switch (entry.getOp()){
              case OP_PUT:
                b.put(cfId, entry.getKey().toByteArray(), entry.getValue().toByteArray());
                break;
              case OP_DELETE:
                b.delete(cfId, entry.getKey().toByteArray());
                break;
              case OP_DELETE_RANGE:
                b.deleteRange(cfId, entry.getKey().toByteArray(), entry.getValue().toByteArray());
                break;

              default:
                throw new RuntimeException("unknown op " + entry.getOp());
            }
          }
        });
        builder.setSuccess(true);
      } else if (writeRequest.hasAllocateSegment()) {
        AllocateSegment allocateSegment = writeRequest.getAllocateSegment();
        byte cfId = CF.of(allocateSegment.getCf().getNumber()).getCfId();
        long endExclusive = store.addLong(cfId, allocateSegment.getKey().toByteArray(), allocateSegment.getStep());
        long start = endExclusive - allocateSegment.getStep();
        Segment segment = Segment.newBuilder()
          .setEndExclusive(endExclusive)
          .setStart(start)
          .build();
        builder.setSuccess(true);
        builder.setSegment( segment);
      } else if (writeRequest.hasCommit()) {
        Commit commit = writeRequest.getCommit();
        List<Meta> metas = new ArrayList<>();
        for (MetaProto meta : commit.getMetasList()) {
          metas.add(new Meta().setKey(meta.getKey().toByteArray())
              .setValue(meta.getValue().toByteArray()));
        }
        store.commitAsync(commit.getTxnId(), commit.getCommitTs(), metas)
            .join();
        builder.setSuccess(true);
      } else if (writeRequest.hasPrewrite()) {
        Prewrite prewrite = writeRequest.getPrewrite();
        List<AdbRegionMutation> mutations = new ArrayList<>();
        for (PrewriteMutation mutation : prewrite.getMutationsList()) {
          RowValue value = RowValue.decodeValue(
              mutation.getValue().toByteArray());
          if (value == null) {
            throw new SQLException("prewrite mutation value is empty");
          }
          value.deleted = mutation.getDeleted();
          mutations.add(new AdbRegionMutation(
              DataKey.fromBytes(mutation.getKey().toByteArray()), value));
        }
        AdbPrewriteApplicator.prewrite(store, prewrite.getTxnId(),
            prewrite.getStartTs(), mutations);
        builder.setSuccess(true);
      } else if (writeRequest.hasRollback()) {
        Rollback rollback = writeRequest.getRollback();
        store.rollbackAsync(rollback.getTxnId()).join();
        builder.setSuccess(true);
      }
    } catch (SQLException e) {
      builder.setSuccess(false);
      builder.setEx(Proto2Util.toThrowable2Proto(e));
    }
    reply.setWriteResponse(builder.build());
  }


  @Override
  public List<FileInfo> takeSnapshot(FileListStateMachineStorage storage, TermIndex last) throws IOException {
    return SMPlugin.super.takeSnapshot(storage, last);
  }

  @Override
  public void finishSnapshot(FileListStateMachineStorage storage, TermIndex last, List<FileInfo> files) throws IOException {
    SMPlugin.super.finishSnapshot(storage, last, files);
  }

  @Override
  public void restoreFromSnapshot(SnapshotInfo snapshot) throws IOException {
    SMPlugin.super.restoreFromSnapshot(snapshot);
  }

  @Override
  public void close() throws IOException {
    if (store != null) {
      store.close();
      store = null;
    }
  }
}
