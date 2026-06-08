package net.xdob.vexra.ha.witness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Witness 投票和最小持久化状态回归测试。
 *
 * <p>这些测试验证 witness 重启后不会丢失 term/vote/epoch/commitIndex，
 * 从配置和状态层降低重复投票风险。</p>
 */
class WitnessStateTest {
  @TempDir
  Path tempDir;

  /**
   * 验证同任期重复给同一候选人投票是幂等的，但不能改投其他候选人。
   */
  @Test
  void shouldGrantVoteIdempotentlyInSameTerm() {
    WitnessState state = WitnessState.empty("vn-1").grantVote("node-a", 3);

    assertTrue(state.canGrantVote("node-a", 3));
    assertFalse(state.canGrantVote("node-b", 3));
    assertThrows(IllegalArgumentException.class,
        () -> state.grantVote("node-b", 3));
  }

  /**
   * 验证更高任期可以重新投票，较低任期会被拒绝。
   */
  @Test
  void shouldAllowHigherTermAndRejectOlderTerm() {
    WitnessState state = WitnessState.empty("vn-1")
        .grantVote("node-a", 3)
        .grantVote("node-b", 4);

    assertEquals(4, state.getCurrentTerm());
    assertEquals("node-b", state.getVotedFor());
    assertFalse(state.canGrantVote("node-a", 3));
  }

  /**
   * 验证 epoch 不允许回退，commitIndex 只能前进。
   */
  @Test
  void shouldRejectEpochRegressionAndKeepCommitIndexMonotonic() {
    WitnessState state = WitnessState.empty("vn-1")
        .acceptEpoch(5)
        .observeCommitIndex(10)
        .observeCommitIndex(8);

    assertEquals(5, state.getAcceptedEpoch());
    assertEquals(10, state.getCommitIndex());
    assertThrows(IllegalArgumentException.class, () -> state.acceptEpoch(4));
  }

  /**
   * 验证文件持久化 store 在重建后仍能读取 term、vote、epoch 和 commitIndex。
   */
  @Test
  void shouldPersistWitnessStateAcrossStoreRecreation() throws Exception {
    FileWitnessStateStore store = new FileWitnessStateStore(tempDir);
    WitnessStateManager manager = new WitnessStateManager(store);

    manager.grantVote("vn-1", "node-a", 7);
    manager.acceptEpoch("vn-1", 3);
    manager.observeCommitIndex("vn-1", 11);

    FileWitnessStateStore reloadedStore = new FileWitnessStateStore(tempDir);
    WitnessState reloaded = reloadedStore.load("vn-1");

    assertEquals(7, reloaded.getCurrentTerm());
    assertEquals("node-a", reloaded.getVotedFor());
    assertEquals(3, reloaded.getAcceptedEpoch());
    assertEquals(11, reloaded.getCommitIndex());
    assertFalse(reloaded.canGrantVote("node-b", 7));
  }
}
