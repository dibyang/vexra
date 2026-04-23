package net.xdob.vexra.server.storage;

import java.util.concurrent.atomic.AtomicInteger;

public class HealthResult {
	private final AtomicInteger success = new AtomicInteger(0);
	private final AtomicInteger failure = new AtomicInteger(0);

	public HealthResult setResult(boolean success) {
		if(success){
			this.success.incrementAndGet();
		} else {
			this.failure.incrementAndGet();
		}
		return this;
	}

	public boolean allSuccess() {
		return success.get() > 0 && failure.get() == 0;
	}

	public boolean allFailure() {
		return success.get() == 0 && failure.get() > 0;
	}

	public boolean anySuccess() {
		return success.get() > 0;
	}

	public boolean anyFailure() {
		return failure.get() > 0;
	}

	public boolean moreSuccessThanFailure() {
		return success.get() > failure.get();
	}

	public boolean moreFailureThanSuccess() {
		return success.get() < failure.get();
	}
}
