package net.xdob.vexra.server.leader;

import java.util.Optional;

public interface LeaderStateSupport {
	Optional<LeaderState> getLeaderState();
}
