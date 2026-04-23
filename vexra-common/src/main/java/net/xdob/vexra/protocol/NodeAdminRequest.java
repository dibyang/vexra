package net.xdob.vexra.protocol;

import net.xdob.vexra.util.JavaUtils;

public final class NodeAdminRequest extends RaftClientRequest {

  public abstract static class Op {

  }


	public static final class Suspend extends Op {
		private Suspend() {

		}

		@Override
		public String toString() {
			return JavaUtils.getClassSimpleName(getClass()) + ":" ;
		}

	}

	public static final class Resume extends Op {
		private Resume() {

		}

		@Override
		public String toString() {
			return JavaUtils.getClassSimpleName(getClass()) + ":" ;
		}

	}



	public static final class Status extends Op {
		private Status() {

		}

		@Override
		public String toString() {
			return JavaUtils.getClassSimpleName(getClass()) + ":" ;
		}

	}


	public static NodeAdminRequest newSuspend(ClientId clientId,
																						RaftPeerId serverId, RaftGroupId groupId, long callId, long timeoutMs) {
		return new NodeAdminRequest(clientId,
				serverId, groupId, callId, timeoutMs, new Suspend());
	}

	public static NodeAdminRequest newResume(ClientId clientId,
																					 RaftPeerId serverId, RaftGroupId groupId, long callId, long timeoutMs) {
		return new NodeAdminRequest(clientId,
				serverId, groupId, callId, timeoutMs, new Resume());
	}

	public static NodeAdminRequest newStatus(ClientId clientId,
																					 RaftPeerId serverId, RaftGroupId groupId, long callId, long timeoutMs) {
		return new NodeAdminRequest(clientId,
				serverId, groupId, callId, timeoutMs, new Status());
	}


  private final Op op;

  public NodeAdminRequest(ClientId clientId,
													RaftPeerId serverId, RaftGroupId groupId, long callId, long timeoutMs, Op op) {
    super(clientId, serverId, groupId, callId, readRequestType(), timeoutMs);
    this.op = op;
  }

  public Resume getResume() {
    return op instanceof Resume ? (Resume)op: null;
  }

	public Suspend getSuspend() {
		return op instanceof Suspend ? (Suspend)op: null;
	}

	public Status getStatus() {
		return op instanceof Status ? (Status)op: null;
	}


  @Override
  public String toString() {
    return super.toString() + ", " + op;
  }
}
