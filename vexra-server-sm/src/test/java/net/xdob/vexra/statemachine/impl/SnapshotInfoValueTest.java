package net.xdob.vexra.statemachine.impl;

import net.xdob.vexra.server.protocol.TermIndex;
import net.xdob.vexra.server.storage.FileInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * server-sm 快照信息值对象回归测试。
 *
 * 测试只覆盖快照文件列表、模块过滤和无 digest 校验失败路径，不启动状态机或 Raft。
 */
class SnapshotInfoValueTest {
  @TempDir
  Path tempDir;

  /**
   * 验证文件列表快照会复制输入列表，并能按 module 过滤。
   */
  @Test
  void shouldExposeImmutableFilesAndFilterByModule() {
    FileInfo data = new FileInfo(tempDir.resolve("data.snapshot"), null, "data");
    FileInfo sum = new FileInfo(tempDir.resolve("sum.md5"), null, FileListSnapshotInfo.SUM);
    FileListSnapshotInfo info = new FileListSnapshotInfo(Arrays.asList(data, sum), 2L, 9L);

    assertEquals(TermIndex.valueOf(2, 9), info.getTermIndex());
    assertEquals(2, info.getFiles().size());
    assertEquals(1, info.getFiles("data").size());
    assertEquals(data, info.getFiles("data").get(0));
    assertTrue(info.getSumFile().isPresent());
    assertThrows(UnsupportedOperationException.class, () -> info.getFiles().add(data));
  }

  /**
   * 验证单文件快照的 getFile 返回唯一文件。
   */
  @Test
  void shouldReturnOnlyFileForSingleFileSnapshot() {
    FileInfo file = new FileInfo(tempDir.resolve("snapshot.bin"), null, "state");
    SingleFileSnapshotInfo info = new SingleFileSnapshotInfo(file, 3L, 11L);

    assertSame(file, info.getFile());
    assertEquals(TermIndex.valueOf(3, 11), info.getTermIndex());
  }

  /**
   * 验证缺少 digest 的文件不会被误判为有效快照。
   */
  @Test
  void shouldFailValidationWhenDigestIsMissing() {
    FileInfo file = new FileInfo(tempDir.resolve("snapshot.bin"), null, "state");
    FileListSnapshotInfo info = new FileListSnapshotInfo(Arrays.asList(file), 1L, 1L);

    assertFalse(info.validate());
  }
}
