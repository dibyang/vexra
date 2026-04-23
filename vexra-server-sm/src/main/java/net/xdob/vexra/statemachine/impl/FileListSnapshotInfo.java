package net.xdob.vexra.statemachine.impl;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import net.xdob.vexra.io.Digest;
import net.xdob.vexra.server.protocol.TermIndex;
import net.xdob.vexra.server.storage.FileInfo;
import net.xdob.vexra.statemachine.SnapshotInfo;
import net.xdob.vexra.util.JavaUtils;
import net.xdob.vexra.util.Md5DigestHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一个不可变的类，用于表示 Raft 状态机的快照信息。
 * <p>
 * 每个快照包含一个日志条目的 Term 和 Index 以及与该快照相关的文件列表。
 * 该类实现了 SnapshotInfo 接口，提供了获取 Term 和文件列表等功能。
 */
public class FileListSnapshotInfo implements SnapshotInfo {
	static final Logger LOG = LoggerFactory.getLogger(FileListSnapshotInfo.class);
  public static final String SUM = "sum";
  private final TermIndex termIndex;
  private final List<FileInfo> files;

  public FileListSnapshotInfo(List<FileInfo> files, TermIndex termIndex) {
    this.termIndex = termIndex;
    this.files = Collections.unmodifiableList(new ArrayList<>(files));
  }

  public FileListSnapshotInfo(List<FileInfo> files, long term, long index) {
    this(files, TermIndex.valueOf(term, index));
  }

	@Override
  public TermIndex getTermIndex() {
    return termIndex;
  }

  @Override
  public List<FileInfo> getFiles() {
    return files;
  }

  @Override
  public List<FileInfo> getFiles(String module) {
    return files.stream().filter(e-> Objects.equals(module,e.getModule()))
        .collect(Collectors.toList());
  }

  public Optional<FileInfo> getSumFile(){
    return getFiles(SUM).stream().findFirst();
  }

	@Override
	public boolean validate() {
		for (FileInfo file : getFiles()) {
			try {
				if(file.getFileDigest()!=null) {
					final Digest digest = Md5DigestHelper.md5.computeDigestForFile(file.getPath().toFile());
					if (!file.validate(digest)) {
						return false;
					}
				}else{
					return false;
				}
			} catch (IOException e) {
				LOG.warn("Digest valid failed for file {}", file.getPath(), e);
				return false;
			}
		}
		return true;
	}

	@Override
  public String toString() {
    return JavaUtils.getClassSimpleName(getClass()) + getTermIndex() + ":" + files;
  }
}
