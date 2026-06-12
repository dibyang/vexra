package net.xdob.vexra.adb.ha2;

import com.google.protobuf.ByteString;
import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.db.*;
import net.xdob.vexra.proto.adb.*;
import net.xdob.vexra.util.Proto2Util;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class LocalRClient implements RClient{
  private final String dbName;
  private final DbStore store;

  public LocalRClient(String dbName) {
    this.dbName = dbName;
    this.store = DbStoreEngine.getOrCreate(DbStoreType.LDB, this.dbName, null);
  }

  @Override
  public ReadResponse sendReadRequest(ReadRequest readRequest) throws SQLException {
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

    return builder.build();
  }

  private ScanDirection fromProtoDirection(Direction direction) {
    if (Objects.requireNonNull(direction) == Direction.DIR_REVERSE) {
      return ScanDirection.REVERSE;
    }
    return ScanDirection.FORWARD;
  }

  @Override
  public WriteResponse sendWriteRequest(WriteRequest writeRequest) throws SQLException {
    WriteResponse.Builder builder = WriteResponse.newBuilder();
    try {
      if(writeRequest.hasBatch()){
        this.store.writeBatch(b -> {
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
      } else if (writeRequest.hasPrewrite()) {
        Prewrite prewrite = writeRequest.getPrewrite();
        java.util.List<AdbRegionMutation> mutations = new java.util.ArrayList<>();
        for (PrewriteMutation mutation : prewrite.getMutationsList()) {
          RowValue value = RowValue.decodeValue(mutation.getValue()
              .toByteArray());
          if (value == null) {
            throw new SQLException("prewrite mutation value is empty");
          }
          value.deleted = mutation.getDeleted();
          mutations.add(new AdbRegionMutation(
              net.xdob.vexra.adb.key.DataKey.fromBytes(
                  mutation.getKey().toByteArray()), value));
        }
        AdbPrewriteApplicator.prewrite(store, prewrite.getTxnId(),
            prewrite.getStartTs(), mutations, toLocks(prewrite));
        builder.setSuccess(true);
      } else if (writeRequest.hasCommit()) {
        Commit commit = writeRequest.getCommit();
        java.util.List<Meta> metas = new java.util.ArrayList<>();
        for (MetaProto meta : commit.getMetasList()) {
          metas.add(Meta.of(meta.getKey().toByteArray(),
              meta.getValue().toByteArray()));
        }
        store.commitAsync(commit.getTxnId(), commit.getCommitTs(), metas)
            .join();
        builder.setSuccess(true);
      } else if (writeRequest.hasRollback()) {
        store.rollbackAsync(writeRequest.getRollback().getTxnId()).join();
        builder.setSuccess(true);
      } else {
        builder.setSuccess(false);
        builder.setEx(Proto2Util.toThrowable2Proto(
            new SQLException("Unsupported write request")));
      }
    } catch (Exception e) {
        builder.setSuccess(false);
        builder.setEx(Proto2Util.toThrowable2Proto(e));
    }
    return builder.build();
  }

  private static java.util.List<AdbTxnLock> toLocks(Prewrite prewrite) {
    java.util.List<AdbTxnLock> locks = new java.util.ArrayList<>();
    if (prewrite.getLocksCount() > 0) {
      for (PrewriteLock lock : prewrite.getLocksList()) {
        locks.add(new AdbTxnLock(lock.getTxnId(), lock.getKey().toByteArray(),
            lock.getPrimaryKey().toByteArray(), lock.getStartTs(),
            lock.getRegionId(), lock.getTtlMillis()));
      }
      return locks;
    }
    for (PrewriteMutation mutation : prewrite.getMutationsList()) {
      byte[] key = mutation.getKey().toByteArray();
      byte[] primaryKey = prewrite.getPrimaryKey().isEmpty()
          ? key : prewrite.getPrimaryKey().toByteArray();
      String regionId = prewrite.getPrimaryRegionId().isEmpty()
          ? "unknown" : prewrite.getPrimaryRegionId();
      locks.add(new AdbTxnLock(prewrite.getTxnId(), key, primaryKey,
          prewrite.getStartTs(), regionId, prewrite.getLockTtlMillis()));
    }
    return locks;
  }

  @Override
  public CompletableFuture<ReadResponse> sendReadRequestAsync(ReadRequest request) {
    try {
      return CompletableFuture.completedFuture(sendReadRequest(request));
    } catch (SQLException e) {
      CompletableFuture<ReadResponse> failed = new CompletableFuture<>();
      failed.completeExceptionally(e);
      return failed;
    }
  }

  @Override
  public CompletableFuture<WriteResponse> sendWriteRequestAsync(WriteRequest request) {
    try {
      return CompletableFuture.completedFuture(sendWriteRequest(request));
    } catch (SQLException e) {
      CompletableFuture<WriteResponse> failed = new CompletableFuture<>();
      failed.completeExceptionally(e);
      return failed;
    }
  }

  @Override
  public void close() throws IOException {
    store.close();
  }
}
