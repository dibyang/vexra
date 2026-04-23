package net.xdob.vexra.util;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;

public class AutoCloseableByteBuffer implements AutoCloseable{
	private ByteBuffer buffer;

	public AutoCloseableByteBuffer(ByteBuffer buffer) {
		this.buffer = buffer;
	}

	public ByteBuffer getBuffer() {
		return buffer;
	}

	@Override
	public void close() {
		cleanDirectBuffer( buffer);
		buffer = null;
	}

	public static AutoCloseableByteBuffer allocate(int capacity){
		return new AutoCloseableByteBuffer(ByteBuffer.allocateDirect(capacity));
	}

	/**
	 * 手动释放直接缓冲区
	 */
	public static void cleanDirectBuffer(ByteBuffer buffer) {
		if (buffer != null && buffer.isDirect()) {
			try {
				// 方法1: 使用Cleaner (Java 8)
				Method cleanerMethod = buffer.getClass().getMethod("cleaner");
				cleanerMethod.setAccessible(true);
				Object cleaner = cleanerMethod.invoke(buffer);
				if (cleaner != null) {
					Method cleanMethod = cleaner.getClass().getMethod("clean");
					cleanMethod.invoke(cleaner);
				}
			} catch (Exception e) {
				// 如果反射失败，尝试其他方法
				try {
					// 方法2: 使用System.gc()提示（不太可靠）
					buffer = null;
					System.gc();
				} catch (Exception ex) {
					// 忽略异常
				}
			}
		}
	}
}
