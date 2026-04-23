package net.xdob.vexra.adb.ha2;

import net.xdob.vexra.proto.adb.ReadRequest;
import net.xdob.vexra.proto.adb.ReadResponse;
import net.xdob.vexra.proto.adb.WriteRequest;
import net.xdob.vexra.proto.adb.WriteResponse;

import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;

public interface RClient extends AutoCloseable {
  ReadResponse sendReadRequest(ReadRequest request) throws SQLException;

  WriteResponse sendWriteRequest(WriteRequest request) throws SQLException;

  CompletableFuture<ReadResponse> sendReadRequestAsync(ReadRequest request);

  CompletableFuture<WriteResponse> sendWriteRequestAsync(WriteRequest request);
  void close() throws IOException;
}
