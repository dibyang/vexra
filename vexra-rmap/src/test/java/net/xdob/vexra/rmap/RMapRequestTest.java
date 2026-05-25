package net.xdob.vexra.rmap;

import net.xdob.vexra.client.RaftClient;
import net.xdob.vexra.protocol.SerialSupport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * rmap 客户端请求封装回归测试。
 *
 * 测试通过内存 DContext 捕获请求，不启动 Raft 或网络；重点约束 RMap 对 put/get/clear
 * 等操作生成的请求结构和函数副作用。
 */
class RMapRequestTest {
  /**
   * 验证 RMap 构造会发出 create 请求，普通 put/get/remove 会带上 map 名称和 key。
   */
  @Test
  void shouldBuildRequestsForBasicOperations() throws Exception {
    RecordingContext context = new RecordingContext();
    RMap<String> map = new RMap<>(context, "users");

    assertEquals(PutMethod.create, context.lastPut.getMethod());
    assertEquals("users", context.lastPut.getName());

    map.put("u1", "alice");
    assertEquals(PutMethod.put, context.lastPut.getMethod());
    assertEquals("u1", context.lastPut.getKey());
    assertEquals("alice", context.lastPut.getData());

    context.nextGetData = "alice";
    assertEquals("alice", map.get("u1"));
    assertEquals(GetMethod.get, context.lastGet.getMethod());
    assertEquals("u1", context.lastGet.getKey());

    context.nextPutData = "alice";
    assertEquals("alice", map.remove("u1"));
    assertEquals(PutMethod.remove, context.lastPut.getMethod());
  }

  /**
   * 验证 putAll、replaceAll 和 clear 附带的函数能正确修改目标缓存。
   */
  @Test
  void shouldApplyBulkUpdateFunctionsToCacheObject() throws Exception {
    RecordingContext context = new RecordingContext();
    RMap<String> map = new RMap<>(context, "cache");
    CacheObject cache = CacheObject.newMap("cache");
    cache.getMap().put("old", "value");

    Map<String, String> extra = new HashMap<>();
    extra.put("a", "1");
    extra.put("b", "2");

    map.putAll(extra);
    context.lastPut.<Object>getFun().apply(cache, context.lastPut.getData());
    assertEquals(3, cache.size());

    Map<String, String> replacement = new HashMap<>();
    replacement.put("only", "new");
    map.replaceAll(replacement);
    context.lastPut.<Object>getFun().apply(cache, context.lastPut.getData());
    assertEquals(1, cache.size());
    assertEquals("new", cache.getMap().get("only"));

    map.clear();
    context.lastPut.<Object>getFun().apply(cache, null);
    assertEquals(0, cache.size());
  }

  /**
   * 验证 CacheInfo 以名称作为身份，size 只作为快照信息。
   */
  @Test
  void shouldUseCacheNameAsCacheInfoIdentity() {
    CacheObject cache = CacheObject.newMap("orders");
    cache.getMap().put("o1", 1);
    CacheInfo info = cache.toCacheInfo();

    assertEquals("orders", info.getName());
    assertEquals(1, info.getSize());
    assertEquals(new CacheInfo("orders", 99), info);
    assertNotEquals(new CacheInfo("other", 1), info);
  }

  /**
   * 记录最近一次请求的内存上下文。
   */
  private static class RecordingContext implements DContext {
    private PutRequest lastPut;
    private GetRequest lastGet;
    private Object nextPutData;
    private Object nextGetData;

    @Override
    public RaftClient getClient() {
      return null;
    }

    @Override
    public SerialSupport getFasts() {
      return null;
    }

    @Override
    public PutReply sendPutRequest(PutRequest putRequest) throws IOException {
      this.lastPut = putRequest;
      return new PutReply().setData(nextPutData);
    }

    @Override
    public GetReply sendGetRequest(GetRequest getRequest) throws IOException {
      this.lastGet = getRequest;
      return new GetReply().setSize(7).setData(nextGetData);
    }
  }
}
