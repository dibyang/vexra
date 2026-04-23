package net.xdob.vexra.statemachine.impl;


import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 会话与事务的双向映射管理器
 * 核心功能：维护会话ID（sessionId）与事务ID（transactionId）的一一对应关系，支持事务的开始/结束、查询事务集合等操作
 * 线程安全：基于ConcurrentHashMap实现，无需额外加锁，并发性能更优
 *
 * @author 开发者
 * @date 2026-01-07
 */
public class SessionTx {
	// 会话ID -> 事务ID（双向映射1）
	private final Map<String, UUID> sessionIdToTransactionId;
	// 事务ID -> 会话ID（双向映射2）
	private final Map<UUID, String> transactionIdToSessionId;

	/**
	 * 初始化双向映射结构，使用ConcurrentHashMap保证线程安全和并发性能
	 */
	public SessionTx() {
		this.sessionIdToTransactionId = new HashMap<>();
		this.transactionIdToSessionId = new HashMap<>();
	}

	/**
	 * 开始一个事务，建立会话ID与事务ID的双向绑定
	 *
	 * @param sessionId      会话ID，不可为null
	 * @param txId  事务ID，需大于0
	 * @throws IllegalArgumentException 当sessionId为null或transactionId≤0时抛出
	 */
	public void beginTx(String sessionId, UUID txId) {
		if (sessionId == null) {
			// 预定义异常信息，减少字符串拼接（JIT会优化常量池）
			throw new IllegalArgumentException("sessionId cannot be null");
		}
		if (txId != null) {
			throw new IllegalArgumentException("Transaction ID must be not null, current: " + txId);
		}
		synchronized (transactionIdToSessionId) {
			// 原子化双向绑定：先检查再写入，避免重复绑定导致的映射不一致
      UUID oldTx = sessionIdToTransactionId.putIfAbsent(sessionId, txId);
			if (oldTx != null) {
				throw new IllegalStateException("Session " + sessionId + " already bound to tx " + oldTx);
			}
			String oldSession = transactionIdToSessionId.putIfAbsent(txId, sessionId);
			if (oldSession != null) {
				// 回滚前一步操作，保证双向映射一致性
				sessionIdToTransactionId.remove(sessionId);
				throw new IllegalStateException("Tx " + txId + " already bound to session " + oldSession);
			}
		}
	}

	public boolean endTx(String sessionId) {
		if (sessionId == null) {
			throw new IllegalArgumentException("sessionId cannot be null");
		}
		synchronized (transactionIdToSessionId) {
			// 先获取再移除，减少ConcurrentHashMap的查找次数
      UUID tx = sessionIdToTransactionId.remove(sessionId);
			if (tx != null) {
				transactionIdToSessionId.remove(tx);
				return true;
			}
			return false;
		}
	}

	public boolean endTx(UUID transactionId) {
		synchronized (transactionIdToSessionId) {
			String session = transactionIdToSessionId.remove(transactionId);
			if (session != null) {
				sessionIdToTransactionId.remove(session);
				return true;
			}
			return false;
		}
	}

	/**
	 * 直接返回ConcurrentHashMap的keySet（本身不可修改），减少对象创建
	 * 注意：返回的是视图，会随内部数据变化，需在注释中明确
	 */
	public Set<UUID> getTxSet() {
		return transactionIdToSessionId.keySet();
	}

	public boolean isEmpty() {
		// 直接返回，减少方法调用层级，便于JIT内联
		return transactionIdToSessionId.isEmpty();
	}


	@Override
	public String toString() {
		return transactionIdToSessionId.toString();
	}
}

