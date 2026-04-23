package net.xdob.vexra.server.storage;

import com.google.common.base.Stopwatch;
import net.xdob.vexra.server.VexraLogTracer;
import net.xdob.vexra.util.AsyncFileHandler;
import net.xdob.vexra.util.Concurrents3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

public class DefaultStorageHealth implements StorageHealth{
  final Logger LOG = LoggerFactory.getLogger(DefaultStorageHealth.class);
	static final String CHECK_NAME = ".check";
	private static final String JVM_NAME = ManagementFactory.getRuntimeMXBean().getName();

	private final List<Path> storageDirs = new ArrayList<>(2);
  private final String id;
  private final ExecutorService executor;


	public DefaultStorageHealth(String id, Path... storageDirs) {
		this(id, Arrays.asList(storageDirs));
	}

	public DefaultStorageHealth(String id,Iterable<Path> storageDirs) {
		this.id = id;
    for (Path storageDir : storageDirs) {
			this.storageDirs.add(storageDir);
		}
    executor = Concurrents3.newSingleThreadExecutor("storage-check@"+id);
	}


	@Override
	public HealthResult checkHealth() {
		HealthResult result = new HealthResult();
		result.setResult(isHealth());
		result.setResult(isHealth());
		return result;
	}

	private boolean isHealth() {
		CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
			boolean health = true;
			for (Path storageDir : storageDirs) {
				if (!checkStorageHealth(storageDir)) {
					health = false;
				}
			}
			return health;
		});
		try {
			return future.get(6000, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			LOG.warn("Failed to check health for {}", e.toString());
		}
		return false;
	}

	private boolean checkStorageHealth(Path storageDir) {
		boolean health = false;
		Stopwatch started = Stopwatch.createStarted();
		try {
			if (!checkStoreFree(storageDir)) {
				health = this.checkStoreReadWrite(storageDir);
			}
		} catch (Exception e) {
			if(VexraLogTracer.ignore_ex.isTrace()){
				LOG.warn("Failed to check storage health, path={}", storageDir, e);
			}else {
				LOG.warn("Failed to check storage health for {}, path={}", e.toString(), storageDir);
			}
		}
		long elapsed = started.elapsed(TimeUnit.MILLISECONDS);
		if(elapsed > 2500) {
			health = false;
			LOG.warn("Storage dir health check failed, elapsed={}ms path={}", elapsed, storageDir);
		}

		return health;
	}

	/**
	 * 检测存储空间是否可读写
	 */
	private boolean checkStoreReadWrite(Path storageDir) {
		File checkFile = storageDir.resolve(id + CHECK_NAME).toFile();
    AsyncFileHandler asyncFileHandler = AsyncFileHandler.of(checkFile);
    byte[] bytes = JVM_NAME.getBytes(StandardCharsets.UTF_8);
    CompletableFuture<Integer> future = asyncFileHandler
        .asyncWrite(bytes, executor);
    try{
      return future.get(2000, TimeUnit.MILLISECONDS) == bytes.length;
    }  catch (Exception e) {
			if(VexraLogTracer.ignore_ex.isTrace()) {
				LOG.warn("Storage dir health check failed. path={}", storageDir, e);
			}else {
				LOG.warn("Storage dir health check failed for {}, path={}", e.toString(), storageDir);
			}
		}
		return false;
	}


	private boolean checkStoreFree(Path path) {
		double free = getStoreFree(path);
		if(free<0.05){
			LOG.info("Storage is no enough space, free={}%, path={}", free, path);
			return true;
		}
		return false;
	}

	/**
	 * 获取存储空间剩余率
	 */
	private double getStoreFree(Path path) {
		try {
			if(!path.toFile().exists()){
				path = path.getParent();
			}
			FileStore store = Files.getFileStore(path);
			long freeSpace = store.getUsableSpace();
			long totalSpace = store.getTotalSpace();
			return ((int)((freeSpace * 100) / totalSpace))/100.0;
		} catch (Exception e) {
			LOG.warn("Failed to get store free for {}", path);
		}
		return 0;
	}
}
