package net.xdob.vexra.adb.ha2;

import com.google.protobuf.ByteString;
import net.xdob.vexra.adb.db.*;
import net.xdob.vexra.adb.*;
import net.xdob.vexra.proto.adb.*;
import org.h2.api.ErrorCode;
import org.h2.message.DbException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * RocksStore 的委托封装类。
 *
 * 说明：
 * 1. 采用组合而不是继承，避免再次打开/管理底层 RocksDB。
 * 2. 所有实例方法都直接委托给 delegate。
 * 3. static 方法（如 encodeLong/decodeLong）不属于实例，不需要委托。
 */
public class RaftStore implements DbStore {
  static final Logger LOG = LoggerFactory.getLogger(RaftStore.class);

  public static final String ADB = "adb";
  private final Properties props;
  private final RClient client;
  private final String dbName;

  public RaftStore(String dbName, Properties props) {
    this.props =  Objects.requireNonNull(props, "properties");
    this.dbName = Objects.requireNonNull(dbName, "dbName");
    this.client = new RaftRClient(props);
    //this.client = new LocalRClient(dbName);
  }

  byte[] get(CF cf, byte[] key) throws SQLException {
    Get.Builder get = Get.newBuilder().setCfValue(cf.getCfId())
        .setKey(ByteString.copyFrom(key));
    ReadRequest.Builder request = ReadRequest.newBuilder()
        .setDbName(dbName)
        .setGet( get);
    ReadResponse response = client.sendReadRequest(request.build());
    GetResult getResult = response.getGetResult();
    if(getResult.getFound()) {
      return getResult.getValue().toByteArray();
    }else {
      return null;
    }
  }

  @Override
  public byte[] get(byte[] key) throws SQLException {
    return get(CF.DEFAULT, key);
  }


  void put(CF cf, byte[] key, byte[] value) throws SQLException{
    Batch.Builder batch = Batch.newBuilder();
    batch.addEntries(WriteEntry.newBuilder()
        .setCfValue(cf.ordinal())
        .setOp(OpProto.OP_PUT)
        .setKey(ByteString.copyFrom(key))
        .setValue( ByteString.copyFrom(value)));
    WriteRequest.Builder request = WriteRequest.newBuilder()
        .setDbName(dbName)
        .setBatch(batch);
    WriteResponse response = client.sendWriteRequest(request.build());
    if(!response.getSuccess()){
      throw DbException.get(ErrorCode.IO_EXCEPTION_1, "write failed");
    }
  }


  @Override
  public void put(byte[] key, byte[] value) throws SQLException {
    put(CF.DEFAULT, key, value);
  }


  long addLong(CF cf, byte[] key, long operand) throws SQLException {
    AllocateSegment allocateSegment = AllocateSegment.newBuilder()
        .setCfValue(cf.getCfId())
        .setKey(ByteString.copyFrom(key))
        .setStep(operand)
        .build();

    WriteRequest.Builder request = WriteRequest.newBuilder()
        .setDbName(dbName)
        .setAllocateSegment(allocateSegment);
    WriteResponse response = client.sendWriteRequest(request.build());
    if(!response.getSuccess()){
      throw DbException.get(ErrorCode.IO_EXCEPTION_1, "write failed");
    }
    return response.getSegment().getEndExclusive();
  }

  @Override
  public long addLong(byte[] key, long operand) throws SQLException {
    return addLong(CF.DEFAULT, key, operand);
  }

  Optional<Long> getLong(CF cf, byte[] key) throws SQLException {
    return decodeLong(get(cf, key));
  }

  @Override
  public Optional<Long> getLong(byte[] key) throws SQLException {
    return getLong(CF.DEFAULT, key);
  }

  @Override
  public void putLong(byte[] key, long value) throws SQLException {
    put(CF.DEFAULT, key, encodeLong(value));
  }

  void delete(CF cf, byte[] key) throws SQLException{
    Batch.Builder batch = Batch.newBuilder();
    batch.addEntries(WriteEntry.newBuilder()
        .setCfValue(cf.getCfId())
        .setOp(OpProto.OP_DELETE)
        .setKey(ByteString.copyFrom(key)));
    WriteRequest.Builder request = WriteRequest.newBuilder()
        .setDbName(dbName)
        .setBatch(batch);
    WriteResponse response = client.sendWriteRequest(request.build());
    if(!response.getSuccess()){
      throw DbException.get(ErrorCode.IO_EXCEPTION_1, "write failed");
    }
  }

  @Override
  public void delete(byte[] key) throws SQLException {
    delete(CF.DEFAULT, key);
  }

  void deleteRange(CF cf, byte[] startKey, byte[] endKey) throws SQLException{
    Batch.Builder batch = Batch.newBuilder();
    batch.addEntries(WriteEntry.newBuilder()
        .setCfValue(cf.getCfId())
        .setOp(OpProto.OP_DELETE_RANGE)
        .setKey(ByteString.copyFrom(startKey))
        .setValue(ByteString.copyFrom(endKey)));
    WriteRequest.Builder request = WriteRequest.newBuilder()
        .setDbName(dbName)
        .setBatch(batch);
    WriteResponse response = client.sendWriteRequest(request.build());
    if(!response.getSuccess()){
      throw DbException.get(ErrorCode.IO_EXCEPTION_1, "write failed");
    }
  }

  @Override
  public void deleteRange(byte[] startKey, byte[] endKey) throws SQLException {
    deleteRange(CF.DEFAULT, startKey, endKey);
  }

  @Override
  public byte[] get(byte cfId, byte[] key) throws SQLException {
    return get(CF.of(cfId), key);
  }

  @Override
  public void put(byte cfId, byte[] key, byte[] value) throws SQLException {
    put(CF.of(cfId), key, value);
  }

  @Override
  public long addLong(byte cfId, byte[] key, long delta) throws SQLException {
    return addLong(CF.of(cfId), key, delta);
  }

  @Override
  public Optional<Long> getLong(byte cfId, byte[] key) throws SQLException {
    return getLong(CF.of(cfId), key);
  }

  @Override
  public void putLong(byte cfId, byte[] key, long value) throws SQLException {
    put(CF.of(cfId), key, encodeLong(value));
  }

  @Override
  public void delete(byte cfId, byte[] key) throws SQLException {
    delete(CF.of(cfId), key);
  }

  @Override
  public void deleteRange(byte cfId, byte[] startKey, byte[] endKey) throws SQLException {
    deleteRange(CF.of(cfId), startKey, endKey);
  }

  @Override
  public CompletableFuture<Void> writeBatchAsync(WriteBatchConsumer consumer) throws SQLException {
    AdbWriteBatch batch = new AdbWriteBatch(this);
    consumer.accept(batch);

    Batch.Builder batch2 = Batch.newBuilder();
    for (WriteEn entry : batch.getEntries()) {
      switch (entry.getOp()) {
        case PUT:
          batch2.addEntries(WriteEntry.newBuilder()
              .setCfValue(entry.getCfId())
              .setOp(OpProto.OP_PUT)
              .setKey(ByteString.copyFrom(entry.getKey()))
              .setValue(ByteString.copyFrom(entry.getValue())));
          break;

        case DELETE:
          batch2.addEntries(WriteEntry.newBuilder()
              .setCfValue(entry.getCfId())
              .setOp(OpProto.OP_DELETE)
              .setKey(ByteString.copyFrom(entry.getKey())));
          break;

        case DELETE_RANGE:
          batch2.addEntries(WriteEntry.newBuilder()
              .setCfValue(entry.getCfId())
              .setOp(OpProto.OP_DELETE_RANGE)
              .setKey(ByteString.copyFrom(entry.getKey()))
              .setValue(ByteString.copyFrom(entry.getValue())));
          break;

        default:
          throw new IllegalArgumentException("unsupported op: " + entry.getOp());
      }
    }

    WriteRequest request = WriteRequest.newBuilder()
        .setDbName(dbName)
        .setBatch(batch2)
        .build();

    return client.sendWriteRequestAsync(request)
        .thenAccept(response -> {
          if (!response.getSuccess()) {
            throw new CompletionException(
                DbException.get(ErrorCode.IO_EXCEPTION_1, "write failed"));
          }
        });
  }

  @Override
  public void rollback(long txnId) throws SQLException {
    rollbackAsync(txnId).join();
  }

  @Override
  public CompletableFuture<Void> rollbackAsync(long txnId) throws SQLException {
    Rollback rollback = Rollback.newBuilder()
        .setTxnId(txnId)
        .build();
    WriteRequest.Builder request = WriteRequest.newBuilder()
        .setDbName(dbName)
        .setRollback(rollback);
    return client.sendWriteRequestAsync(request.build())
        .thenAccept(response -> {
          if (!response.getSuccess()) {
            throw new CompletionException(
                DbException.get(ErrorCode.IO_EXCEPTION_1, "write failed"));
          }
        });
  }

  @Override
  public void writeBatch(WriteBatchConsumer consumer) throws SQLException {
    writeBatchAsync( consumer).join();
  }

  @Override
  public CompletableFuture<Void> commitAsync(long txnId, long commitTs, List<Meta> metas) throws SQLException {
    Commit.Builder builder = Commit.newBuilder()
        .setTxnId(txnId)
        .setCommitTs(commitTs);
    for (Meta meta : metas) {
      builder.addMetas(MetaProto.newBuilder()
          .setKey(ByteString.copyFrom(meta.getKey()))
          .setValue(ByteString.copyFrom(meta.getValue())));
    }

    WriteRequest.Builder request = WriteRequest.newBuilder()
        .setDbName(dbName)
        .setCommit(builder.build());
    return client.sendWriteRequestAsync(request.build())
        .thenAccept(response -> {
          if (!response.getSuccess()) {
            throw new CompletionException(
                DbException.get(ErrorCode.IO_EXCEPTION_1, "write failed"));
          }
        });
  }



  @Override
  public VersionScanSource openVersionScanSource(ScanDirection direction) {
    return new RaftVersionScanSource(this.dbName, this.client, CF.DEFAULT, direction);
  }

  @Override
  public VersionScanSource openVersionScanSource(byte cfId, ScanDirection direction) {
    return new RaftVersionScanSource(this.dbName, this.client, CF.of(cfId), direction);
  }

  @Override
  public void checkpoint(String targetDir) throws IOException {

  }

  @Override
  public void restore(String sourceDir) throws IOException {

  }


  @Override
  public void close() throws IOException {
    client.close();
  }
}
