package net.xdob.vexra.statemachine.impl;


import net.xdob.vexra.protocol.RaftGroupMemberId;

public interface LeaderChangedListener {
  default void notifyLeaderChanged(boolean isLeader){

  }
  default void changeToCandidate(RaftGroupMemberId groupMemberId) {
  }
}
