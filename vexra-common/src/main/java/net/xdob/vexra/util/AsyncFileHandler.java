package net.xdob.vexra.util;

import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 面向对象的文件异步读写器（CompletableFuture实现）
 * 一个实例绑定一个文件，直接调用实例方法完成异步读/写
 */
public class AsyncFileHandler {
  static final Logger LOG = LoggerFactory.getLogger(AsyncFileHandler.class);

  // 静态常量：文件打开模式（避免重复创建，提升性能）
  private static final Set<StandardOpenOption> TRUNCATE_WRITE_OPTIONS = Collections.unmodifiableSet(
      Sets.newHashSet(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
  );
  private static final Set<StandardOpenOption> APPEND_WRITE_OPTIONS = Collections.unmodifiableSet(
      Sets.newHashSet(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
  );
  private static final Set<StandardOpenOption> READ_OPTIONS = Collections.unmodifiableSet(
      Sets.newHashSet(StandardOpenOption.READ)
  );

  // 绑定的文件路径
  private final Path path;

  /**
   * 构造器：绑定文件路径
   * @param filePath 目标文件的字符串路径
   */
  private AsyncFileHandler(String filePath) {
    this.path = Paths.get(filePath);
  }

  /**
   * 构造器：绑定文件路径（支持Path类型）
   * @param path 目标文件的Path对象
   */
  private AsyncFileHandler(Path path) {
    this.path = path;
  }

  private AsyncFileHandler(File file) {
    this.path = file.toPath();
  }

  public static AsyncFileHandler of(String filePath) {
    return new AsyncFileHandler(filePath);
  }

  public static AsyncFileHandler of(Path path) {
    return new AsyncFileHandler(path);
  }

  public static AsyncFileHandler of(File file) {
    return new AsyncFileHandler(file);
  }

  // ------------------------------ 异步写入方法 ------------------------------
  /**
   * 异步写入（默认：覆盖原有内容，从0位置开始）
   * @param data 要写入的字节数据
   * @return CompletableFuture<Integer> 写入的字节数
   */
  public CompletableFuture<Integer> asyncWrite(byte[] data) {
    return asyncWrite(data, 0, false);
  }

  /**
   * 异步写入（支持指定位置，默认覆盖）
   * @param data 要写入的字节数据
   * @param position 写入位置（覆盖模式有效，追加模式无效）
   * @return CompletableFuture<Integer> 写入的字节数
   */
  public CompletableFuture<Integer> asyncWrite(byte[] data, long position) {
    return asyncWrite(data, position, false);
  }

  /**
   * 异步写入（全参数版：支持指定位置+覆盖/追加）
   * @param data 要写入的字节数据
   * @param executor 线程池，用于执行异步操作
   * @return CompletableFuture<Integer> 写入的字节数
   */
  public CompletableFuture<Integer> asyncWrite(byte[] data, ExecutorService executor) {
    return asyncWrite(data, 0, false, executor);
  }

  /**
   * 异步写入（全参数版：支持指定位置+覆盖/追加）
   * @param data 要写入的字节数据
   * @param position 写入位置（追加模式下此参数无效，固定从文件末尾写入）
   * @param executor 线程池，用于执行异步操作
   * @return CompletableFuture<Integer> 写入的字节数
   */
  public CompletableFuture<Integer> asyncWrite(byte[] data, long position, ExecutorService executor) {
    return asyncWrite(data, position, false, executor);
  }

  /**
   * 异步写入（全参数版：支持指定位置+覆盖/追加）
   * @param data 要写入的字节数据
   * @param position 写入位置（追加模式下此参数无效，固定从文件末尾写入）
   * @param append 是否追加写入（true=追加，false=覆盖）
   * @return CompletableFuture<Integer> 写入的字节数
   */
  public CompletableFuture<Integer> asyncWrite(byte[] data, long position, boolean append) {
    return this.asyncWrite(data, position, append, null);
  }

  /**
   * 异步写入（全参数版：支持指定位置+覆盖/追加）
   * @param data 要写入的字节数据
   * @param position 写入位置（追加模式下此参数无效，固定从文件末尾写入）
   * @param append 是否追加写入（true=追加，false=覆盖）
   * @return CompletableFuture<Integer> 写入的字节数
   */
  public CompletableFuture<Integer> asyncWrite(byte[] data, long position, boolean append, ExecutorService executor) {
    CompletableFuture<Integer> future = new CompletableFuture<>();
    ByteBuffer buffer = ByteBuffer.wrap(data);
    try {
      // 按需打开通道：根据追加/覆盖选择模式，每次操作独立创建通道
      AsynchronousFileChannel afc = AsynchronousFileChannel.open(
          this.path,
          append ? APPEND_WRITE_OPTIONS : TRUNCATE_WRITE_OPTIONS,
          executor
      );
      // 桥接AFC回调到CompletableFuture，携带通道用于关闭
      afc.write(buffer, position, afc, new CompletionHandler<Integer, AsynchronousFileChannel>() {
        @Override
        public void completed(Integer writeBytes, AsynchronousFileChannel channel) {
          future.complete(writeBytes);
          closeChannel(channel); // 操作成功，自动关闭通道
        }

        @Override
        public void failed(Throwable ex, AsynchronousFileChannel channel) {
          future.completeExceptionally(ex);
          closeChannel(channel); // 操作失败，也必须关闭通道
        }
      });
    } catch (IOException ex) {
      // 通道打开失败，直接完成异常Future
      future.completeExceptionally(ex);
    }
    return future;
  }

  // ------------------------------ 异步读取方法 ------------------------------
  /**
   * 异步读取（默认：从0位置开始，缓冲区4096字节<适配文件系统块大小>）
   * @return CompletableFuture<byte[]> 读取到的字节数据（空数组=读到文件末尾）
   */
  public CompletableFuture<byte[]> asyncRead() {
    return asyncRead(0, 4096);
  }

  /**
   * 异步读取（支持指定位置，默认缓冲区4096字节）
   * @param position 读取位置（0为文件开头）
   * @return CompletableFuture<byte[]> 读取到的字节数据
   */
  public CompletableFuture<byte[]> asyncRead(long position) {
    return asyncRead(position, 4096);
  }

  /**
   * 异步读取（全参数版：支持指定位置+自定义缓冲区大小）
   * @param position 读取位置（0为文件开头）
   * @param bufferSize 读取缓冲区大小（建议4096/8192，避免过小导致读取不完整）
   * @return CompletableFuture<byte[]> 读取到的字节数据（空数组=读到文件末尾）
   */
  public CompletableFuture<byte[]> asyncRead(long position, int bufferSize){
    return asyncRead(position, bufferSize, null);
  }

  /**
   * 异步读取（全参数版：支持指定位置+自定义缓冲区大小）
   * @param executor 线程池，用于执行异步操作
   * @return CompletableFuture<byte[]> 读取到的字节数据（空数组=读到文件末尾）
   */
  public CompletableFuture<byte[]> asyncRead(ExecutorService executor) {
    return asyncRead(0, 4096, executor);
  }

  /**
   * 异步读取（全参数版：支持指定位置+自定义缓冲区大小）
   * @param position 读取位置（0为文件开头）
   * @param executor 线程池，用于执行异步操作
   * @return CompletableFuture<byte[]> 读取到的字节数据（空数组=读到文件末尾）
   */

  public CompletableFuture<byte[]> asyncRead(long position, ExecutorService executor) {
    return asyncRead(position, 4096, executor);
  }

  /**
   * 异步读取（全参数版：支持指定位置+自定义缓冲区大小）
   * @param position 读取位置（0为文件开头）
   * @param bufferSize 读取缓冲区大小（建议4096/8192，避免过小导致读取不完整）
   * @param executor 线程池，用于执行异步操作
   * @return CompletableFuture<byte[]> 读取到的字节数据（空数组=读到文件末尾）
   */
  public CompletableFuture<byte[]> asyncRead(long position, int bufferSize, ExecutorService executor) {
    CompletableFuture<byte[]> future = new CompletableFuture<>();
    ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
    try {
      // 按需打开只读通道
      AsynchronousFileChannel afc = AsynchronousFileChannel.open(this.path, READ_OPTIONS, executor);
      // 桥接回调，携带「通道+缓冲区」用于提取数据和关闭通道
      afc.read(buffer, position, new ChannelBufferPair(afc, buffer),
          new CompletionHandler<Integer, ChannelBufferPair>() {
            @Override
            public void completed(Integer readBytes, ChannelBufferPair pair) {
              if (readBytes == -1) {
                future.complete(new byte[0]); // 读到末尾返回空数组
              } else {
                pair.buffer.flip(); // 缓冲区切换为读模式
                byte[] result = new byte[readBytes];
                pair.buffer.get(result); // 提取有效字节数据
                future.complete(result);
              }
              closeChannel(pair.channel);
            }

            @Override
            public void failed(Throwable ex, ChannelBufferPair pair) {
              future.completeExceptionally(ex);
              closeChannel(pair.channel);
            }
          });
    } catch (IOException ex) {
      future.completeExceptionally(ex);
    }
    return future;
  }

  // ------------------------------ 私有工具方法 ------------------------------
  /**
   * 静默关闭AFC通道，捕获异常（避免回调中抛出未捕获异常）
   */
  private void closeChannel(AsynchronousFileChannel channel) {
    if (channel != null) {
      try {
        channel.close();
      } catch (IOException ex) {
        LOG.warn("Failed to close AsynchronousFileChannel path={}", this.path, ex);
      }
    }
  }

  /**
   * 内部辅助类：封装AFC通道+ByteBuffer（避免使用第三方Pair，纯JDK实现）
   */
  private static class ChannelBufferPair {
    final AsynchronousFileChannel channel;
    final ByteBuffer buffer;

    ChannelBufferPair(AsynchronousFileChannel channel, ByteBuffer buffer) {
      this.channel = channel;
      this.buffer = buffer;
    }
  }
}
