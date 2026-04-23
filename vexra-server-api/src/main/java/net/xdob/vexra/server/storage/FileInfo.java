package net.xdob.vexra.server.storage;

import java.nio.file.Path;

import com.google.common.base.Strings;
import net.xdob.vexra.io.Digest;

/**
 * 描述了文件的元数据，主要用于存储与文件相关的路径、MD5 校验值和文件大小等信息。
 * 它是不可变的，这意味着一旦创建后，文件的元数据就不可更改。
 */
public class FileInfo {
  private final Path path;
  private Digest fileDigest;
  private final String module;

  public FileInfo(Path path, Digest fileDigest, String module) {
    this.path = path;
    this.fileDigest = fileDigest;
    this.module = Strings.nullToEmpty(module);
  }

//  public FileInfo(Path path, Digest fileDigest) {
//    this(path, fileDigest, "");
//  }

  @Override
  public String toString() {
    return path.toString();
  }

  /** @return the path of the file. */
  public Path getPath() {
    return path;
  }

  /** @return the file digest of the file. */
  public Digest getFileDigest() {
    return fileDigest;
  }

  public FileInfo setFileDigest(Digest fileDigest) {
    this.fileDigest = fileDigest;
    return this;
  }

	public boolean validate(Digest digest){
		return fileDigest!=null&&fileDigest.equals(digest);
	}


	/** @return the size of the file. */
  public long getFileSize() {
    return path.toFile().length();
  }

  /**
   * 模块名
   * @return 模块名
   */
  public String getModule() {
    return Strings.nullToEmpty(module);
  }
}
