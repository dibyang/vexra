package net.xdob.vexra.util;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.xdob.vexra.io.DigestCache;
import net.xdob.vexra.io.DigestService;
import net.xdob.vexra.io.MD5Hash;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class Md5DigestService implements DigestService<MD5Hash> {
	private final Cache<String, DigestCache> cache;

	public Md5DigestService() {
		// 最大容量：512个元素
		// 写入后10分钟过期
		this(512, 10, TimeUnit.MINUTES);
	}


	public Md5DigestService(long maxSize, long expireAfterWrite, TimeUnit timeUnit) {

		this.cache = CacheBuilder.newBuilder()
				.maximumSize(maxSize)
				.expireAfterWrite(expireAfterWrite, timeUnit)
				.recordStats()
				.build();
	}

	@Override
	public MD5Hash computeDigestForFile(File dataFile) throws IOException {
		String key = dataFile.getAbsolutePath();
		DigestCache digestCache = cache.getIfPresent(key);
		
		// 检查缓存是否有效
		if (digestCache != null) {
			if (digestCache.getFileSize() == dataFile.length()
					&& digestCache.getLastModified() == dataFile.lastModified()) {
				// 缓存有效，直接返回
				return (MD5Hash)digestCache.getDigest();
			} else {
				// 缓存失效，移除它
				cache.invalidate(key);
			}
		}
		
		// 缓存未命中或已失效
		// 计算MD5并存入缓存
		MD5Hash md5Hash = MD5FileUtil.computeDigestForFile(dataFile);
		if(md5Hash!=null) {
			DigestCache newDigestCache = new DigestCache(dataFile.getName(), dataFile.lastModified(), dataFile.length(), md5Hash);
			cache.put(key, newDigestCache);
		}
		return md5Hash;
	}

	@Override
	public MD5Hash computeAndSaveDigestForFile(File dataFile) {
		MD5Hash md5Hash = null;
		try {
			md5Hash = this.computeDigestForFile(dataFile);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to compute MD5 for file " + dataFile, e);
		}
		try {
			MD5FileUtil.saveDigestFile(dataFile, md5Hash);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to save MD5 " + md5Hash + " for file " + dataFile, e);
		}
		return md5Hash;
	}

	public MD5Hash readStoredDigestForFile(File dataFile) throws IOException {
		return MD5FileUtil.readStoredDigestForFile(dataFile);
	}

	@Override
	public void saveDigestFile(File dataFile, MD5Hash digest) throws IOException {
		MD5FileUtil.saveDigestFile(dataFile, digest);
	}

}
