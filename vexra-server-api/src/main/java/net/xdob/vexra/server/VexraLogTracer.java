package net.xdob.vexra.server;

import net.xdob.vexra.util.LogTracer;

public interface VexraLogTracer {
	LogTracer failed = LogTracer.c("failed");
	LogTracer tx = LogTracer.c("tx");
	LogTracer install_snapshot = LogTracer.c("install_snapshot");
	LogTracer ignore_ex = LogTracer.c("ignore_ex");
	LogTracer jdbc_req = LogTracer.c("jdbc_req");
	LogTracer client_req = LogTracer.c("client_req");
	LogTracer append = LogTracer.c("append");
	LogTracer write = LogTracer.c("write");
	LogTracer heart = LogTracer.c("heart");
}
