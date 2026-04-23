package net.xdob.vexra.server.storage;

public interface StorageHealth {
	/**
	 * 通过读写检测存储是否可用健康
	 */
	HealthResult checkHealth();
}
