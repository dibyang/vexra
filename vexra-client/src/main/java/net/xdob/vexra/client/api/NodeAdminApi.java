
package net.xdob.vexra.client.api;

import net.xdob.vexra.protocol.RaftClientReply;

import java.io.IOException;

/**
 * An API to support control server state
 */
public interface NodeAdminApi {

	RaftClientReply suspend(long timeoutMs) throws IOException;
	RaftClientReply resume(long timeoutMs) throws IOException;
	RaftClientReply status(long timeoutMs) throws IOException;
}
