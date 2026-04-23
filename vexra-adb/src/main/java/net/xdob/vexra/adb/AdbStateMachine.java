package net.xdob.vexra.adb;

import net.xdob.vexra.protocol.RaftGroupId;
import net.xdob.vexra.protocol.RaftPeerId;
import net.xdob.vexra.statemachine.impl.CompoundStateMachine;

public class AdbStateMachine extends CompoundStateMachine {
  public AdbStateMachine(RaftGroupId groupId, RaftPeerId peerId) {
    super(groupId, peerId);
    this.addSMPlugin(new AdbSMPlugin(peerId));
  }


}
