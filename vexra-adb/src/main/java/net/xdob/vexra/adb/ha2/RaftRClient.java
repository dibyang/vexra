package net.xdob.vexra.adb.ha2;

import net.xdob.vexra.client.RaftClient;
import net.xdob.vexra.conf.Parameters;
import net.xdob.vexra.conf.RaftProperties;
import net.xdob.vexra.proto.adb.ReadRequest;
import net.xdob.vexra.proto.adb.ReadResponse;
import net.xdob.vexra.proto.adb.WriteRequest;
import net.xdob.vexra.proto.adb.WriteResponse;
import net.xdob.vexra.proto.sm.WrapReplyProto;
import net.xdob.vexra.proto.sm.WrapRequestProto;
import net.xdob.vexra.protocol.*;
import net.xdob.vexra.retry.RetryPolicies;
import net.xdob.vexra.retry.RetryPolicy;
import net.xdob.vexra.util.Finder;
import net.xdob.vexra.util.Proto2Util;
import net.xdob.vexra.util.TimeDuration;
import net.xdob.vexra.util.Types2;
import org.h2.api.ErrorCode;
import org.h2.message.DbException;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 基于 Raft client 的 ADB RClient 实现。
 *
 * <p>该实现把 ADB proto 包装为状态机请求发送到 Raft 集群。`HA2.NODES`
 * 同时兼容 `node@host` 和 `node@host:port` 两种写法：前者继续使用
 * `HA2.PORT`，后者用于同机多节点测试和部署中为每个节点声明独立端口。</p>
 */
public class RaftRClient implements RClient{

  public static final int DEFAULT_PORT = 7800;
  private final RaftClient client;

  public RaftRClient(Properties props) {
    RaftProperties raftProperties = new RaftProperties();
    RaftGroupId groupId = RaftGroupId.valueOf(props.getProperty("HA2.GROUP"));
    String nodes = props.getProperty("HA2.NODES");

    int port = Types2.cast(props.get("HA2.PORT"), Integer.class)
        .filter(i -> i > 0).orElse(DEFAULT_PORT);
    int vport = Types2.cast(props.get("HA2.VPORT"), Integer.class)
        .filter(i -> i > 0).orElse(port + 4);
    List<RaftPeer> peers = new ArrayList<>();
    List<RaftPeer> nodeList = parsePeers(nodes, port);
    peers.addAll(nodeList);
    if(nodeList.size()%2==0){
      for (RaftPeer node : nodeList) {
        String address = node.getAddress();
        peers.add(RaftPeer.newBuilder()
            .setId("vn_"+node.getId())
            .setAddress(Finder.c(address).last().head(":").getValue(), vport)
            .build());
      }
    }
    final RaftGroup raftGroup = RaftGroup.valueOf(groupId, peers);
    RaftClient.Builder builder =
        RaftClient.newBuilder().setProperties(raftProperties);
    builder.setRaftGroup(raftGroup);
    RetryPolicy retryPolicy = RetryPolicies.retryUpToMaximumCountWithFixedSleep(8,
        TimeDuration.valueOf(500, TimeUnit.MILLISECONDS));
    builder.setRetryPolicy(retryPolicy);
    builder.setParameters(new Parameters());
    client = builder.build();
  }

  /**
   * 解析 ADB HA2 节点列表。
   *
   * @param peers 节点列表，格式为 `node@host` 或 `node@host:port`
   * @param port 未在节点地址中显式声明端口时使用的默认端口
   * @return 可交给 RaftClient 使用的 peer 列表
   */
  static List<RaftPeer> parsePeers(String peers,int port) {
    return Stream.of(peers.split(",")).map(address -> {
          String[] addressParts = address.split("@");
          if (addressParts.length < 2) {
            throw new IllegalArgumentException(
                "Raft peers " + peers + " is not a legitimate format. "
                    + "(format: name:host)");
          }

          String id = addressParts[0];
          RaftPeer.Builder builder = RaftPeer.newBuilder();
          builder.setId(id);
          String peerAddress = addressParts[1];
          if (peerAddress.contains(":")) {
            builder.setAddress(peerAddress);
          } else {
            builder.setAddress(peerAddress, port);
          }
          return builder.build();
        }).filter(e->!e.isVirtual())
        .collect(Collectors.toList());
  }

  @Override
  public ReadResponse sendReadRequest(ReadRequest request) throws SQLException {
    try {
      return sendReadRequestAsync(request).join();
    } catch (CompletionException e) {
      Throwable cause = unwrapCompletionException(e);
      if (cause instanceof SQLException) {
        throw (SQLException) cause;
      }
      throw new SQLException(cause.getMessage(), cause);
    }
  }

  @Override
  public CompletableFuture<ReadResponse> sendReadRequestAsync(ReadRequest request) {
    final WrapRequestProto wrap = WrapRequestProto.newBuilder()
        .setType(RaftStore.ADB)
        .setXid("")
        .setReadRequest(request)
        .build();

    return client.async()
        .sendReadOnlyUnordered(Message.valueOf(wrap))
        .handle((reply, t) -> {
          if (t != null) {
            Throwable cause = unwrapCompletionException(t);
            if (cause instanceof SQLException) {
              throw new CompletionException(cause);
            }
            if (cause instanceof IOException) {
              throw new CompletionException(DbException.get(ErrorCode.IO_EXCEPTION_1, cause));
            }
            throw new CompletionException(new SQLException(cause.getMessage(), cause));
          }

          try {
            return parseReadReply(reply);
          } catch (SQLException e) {
            throw new CompletionException(e);
          } catch (IOException e) {
            throw new CompletionException(DbException.get(ErrorCode.IO_EXCEPTION_1, e));
          }
        });
  }

  private ReadResponse parseReadReply(RaftClientReply reply) throws IOException, SQLException {
    if (reply == null) {
      throw new SQLException("Raft read reply is null");
    }
    if (!reply.isSuccess()) {
      throw new SQLException("Raft read failed: reply is not successful");
    }

    WrapReplyProto replyProto = WrapReplyProto.parseFrom(reply.getMessage().getContent());
    if (replyProto.hasEx()) {
      Throwable throwable = Proto2Util.toThrowable(replyProto.getEx(), Throwable.class);
      if (throwable instanceof SQLException) {
        throw (SQLException) throwable;
      }
      throw new SQLException(throwable.getMessage(), throwable);
    }
    return replyProto.getReadResponse();
  }


  @Override
  public WriteResponse sendWriteRequest(WriteRequest request) throws SQLException {
    try {
      return sendWriteRequestAsync(request).join();
    } catch (CompletionException e) {
      Throwable cause = unwrapCompletionException(e);
      if (cause instanceof SQLException) {
        throw (SQLException) cause;
      }
      throw new SQLException(cause.getMessage(), cause);
    }
  }


  @Override
  public CompletableFuture<WriteResponse> sendWriteRequestAsync(WriteRequest request) {
    final WrapRequestProto wrap = WrapRequestProto.newBuilder()
        .setType(RaftStore.ADB)
        .setXid("")
        .setWriteRequest(request)
        .build();

    return client.async()
        .sendUnordered(Message.valueOf(wrap))
        .handle((reply, t) -> {
          if (t != null) {
            Throwable cause = unwrapCompletionException(t);
            if (cause instanceof SQLException) {
              throw new CompletionException(cause);
            }
            if (cause instanceof IOException) {
              throw new CompletionException(DbException.get(ErrorCode.IO_EXCEPTION_1, cause));
            }
            throw new CompletionException(new SQLException(cause.getMessage(), cause));
          }

          try {
            return parseWriteReply(reply);
          } catch (SQLException e) {
            throw new CompletionException(e);
          } catch (IOException e) {
            throw new CompletionException(DbException.get(ErrorCode.IO_EXCEPTION_1, e));
          }
        });
  }

  private WriteResponse parseWriteReply(RaftClientReply reply) throws IOException, SQLException {
    if (reply == null) {
      throw new SQLException("Raft reply is null");
    }
    if (!reply.isSuccess()) {
      throw new SQLException("Raft write failed: reply is not successful");
    }

    WrapReplyProto replyProto = WrapReplyProto.parseFrom(reply.getMessage().getContent());
    if (replyProto.hasEx()) {
      Throwable throwable = Proto2Util.toThrowable(replyProto.getEx(), Throwable.class);
      if (throwable instanceof SQLException) {
        throw (SQLException) throwable;
      }
      throw new SQLException(throwable.getMessage(), throwable);
    }
    return replyProto.getWriteResponse();
  }

  private Throwable unwrapCompletionException(Throwable t) {
    if (t instanceof CompletionException && t.getCause() != null) {
      return unwrapCompletionException(t.getCause());
    }
    return t;
  }


  @Override
  public void close() throws IOException {
    client.close();
  }
}
