package net.xdob.vexra.server.protocol;

import net.xdob.vexra.proto.raft.LogEntryProto;
import net.xdob.vexra.proto.raft.TermIndexProto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * server-api 中 TermIndex 值对象回归测试。
 *
 * TermIndex 是 Raft 日志定位的基础值对象，这里只验证本地比较、相等和 proto 往返。
 */
class TermIndexTest {
  /**
   * 验证比较顺序先按 term，再按 index。
   */
  @Test
  void shouldCompareByTermThenIndex() {
    TermIndex low = TermIndex.valueOf(1, 10);
    TermIndex highIndex = TermIndex.valueOf(1, 11);
    TermIndex highTerm = TermIndex.valueOf(2, 0);

    assertTrue(low.compareTo(highIndex) < 0);
    assertTrue(highIndex.compareTo(highTerm) < 0);
    assertEquals(0, TermIndex.valueOf(1, 10).compareTo(low));
  }

  /**
   * 验证 proto 和 LogEntry 输入都能还原相同 TermIndex，null 输入保持 null。
   */
  @Test
  void shouldRoundTripThroughProto() {
    TermIndex original = TermIndex.valueOf(3, 7);
    TermIndexProto proto = original.toProto();
    LogEntryProto entry = LogEntryProto.newBuilder()
        .setTerm(3)
        .setIndex(7)
        .build();

    assertEquals(original, TermIndex.valueOf(proto));
    assertEquals(original, TermIndex.valueOf(entry));
    assertNull(TermIndex.valueOf((TermIndexProto) null));
    assertNull(TermIndex.valueOf((LogEntryProto) null));
  }

  /**
   * 验证负 index 使用占位符输出，避免 INITIAL_VALUE 日志文本误导排查。
   */
  @Test
  void shouldRenderNegativeIndexAsPlaceholder() {
    assertEquals("(t:0, i:~)", TermIndex.INITIAL_VALUE.toString());
    assertEquals(TermIndex.valueOf(1, 2), TermIndex.valueOf(1, 2));
    assertEquals(TermIndex.valueOf(1, 2).hashCode(), TermIndex.valueOf(1, 2).hashCode());
  }
}
