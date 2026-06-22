import jdk.jfr.ValueDescriptor;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ADB JFR 轻量热点解析器。
 *
 * <p>本脚本用于没有 {@code jfr} CLI 的本地环境。它依赖 JDK 11+
 * {@code jdk.jfr.consumer} API，聚合 mixed benchmark 里最关心的 allocation
 * 与 execution sample 信息，输出给 PowerShell 包装脚本继续归档。</p>
 */
public final class AdbJfrHotspots {
  private static final String[] FOCUS_PATTERNS = {
      "java.lang.reflect.Proxy",
      "AdbSimpleResultSet",
      "AdbPreparedStatementProxy",
      "AdbJdbcProxy",
      "TxnMap2.getVisible",
      "DefaultVisibleRowResolver",
      "RowValue.decodeValue",
      "RowCodec",
      "ADB_COMMIT",
      "TxnManager.commit",
      "TxnManager.commitLocalDirect",
      "LdbStore.writeBatch",
      "LDbImpl.writeWriteBatch",
      "WriteBatch",
  };

  private final Map<String, Stat> eventCounts = new HashMap<>();
  private final Map<String, Stat> allocationsByClass = new HashMap<>();
  private final Map<String, Stat> allocationsByTopFrame = new HashMap<>();
  private final Map<String, Stat> allocationsByStack = new HashMap<>();
  private final Map<String, Stat> allocationFocus = new HashMap<>();
  private final Map<String, Stat> allocationFocusByClass = new HashMap<>();
  private final Map<String, Stat> allocationFocusByStack = new HashMap<>();
  private final Map<String, Stat> executionFocus = new HashMap<>();
  private final Map<String, Stat> executionTopFrames = new HashMap<>();

  /**
   * 解析指定 JFR 文件并写出热点报告。
   *
   * @param args 第一个参数是 JFR 文件，第二个可选参数是输出目录
   * @throws IOException 读取 JFR 或写报告失败时抛出
   */
  public static void main(String[] args) throws IOException {
    if (args.length < 1 || args.length > 2) {
      throw new IllegalArgumentException("Usage: java AdbJfrHotspots.java <file.jfr> [outputDir]");
    }
    Path jfrFile = Paths.get(args[0]).toAbsolutePath().normalize();
    Path outputDir = args.length == 2
        ? Paths.get(args[1]).toAbsolutePath().normalize()
        : jfrFile.getParent().resolve("hotspots");
    Files.createDirectories(outputDir);

    AdbJfrHotspots parser = new AdbJfrHotspots();
    parser.parse(jfrFile);
    parser.writeReports(outputDir);

    System.out.println("Summary: " + outputDir.resolve("summary.txt"));
    System.out.println("Allocation events: " + outputDir.resolve("allocation-events.txt"));
    System.out.println("Execution samples: " + outputDir.resolve("execution-samples.txt"));
    System.out.println("ADB focus matches: " + outputDir.resolve("adb-focus.txt"));
  }

  private void parse(Path jfrFile) throws IOException {
    try (RecordingFile recording = new RecordingFile(jfrFile)) {
      while (recording.hasMoreEvents()) {
        RecordedEvent event = recording.readEvent();
        String eventName = event.getEventType().getName();
        eventCounts.computeIfAbsent(eventName, Stat::new).add(1);
        if (eventName.startsWith("jdk.ObjectAllocation")) {
          recordAllocation(event);
        } else if ("jdk.ExecutionSample".equals(eventName)) {
          recordExecutionSample(event);
        }
      }
    }
  }

  private void recordAllocation(RecordedEvent event) {
    String className = className(event);
    long bytes = longField(event, "allocationSize", 0L);
    allocationsByClass.computeIfAbsent(className, Stat::new).add(bytes, 1);

    String stack = stackTrace(event);
    String topFrame = topFrame(event);
    if (!topFrame.isEmpty()) {
      allocationsByTopFrame.computeIfAbsent(topFrame + " <- " + className, Stat::new)
          .add(bytes, 1);
    }
    String stackKey = stackKey(className, stack);
    if (!stackKey.isEmpty()) {
      allocationsByStack.computeIfAbsent(stackKey, Stat::new).add(bytes, 1);
    }
    recordFocus(allocationFocus, className + "\n" + stack, bytes);
    recordFocusByClass(className, stack, bytes);
    recordFocusByStack(className, stack, bytes);
  }

  private void recordExecutionSample(RecordedEvent event) {
    String stack = stackTrace(event);
    String topFrame = topFrame(event);
    if (!topFrame.isEmpty()) {
      executionTopFrames.computeIfAbsent(topFrame, Stat::new).add(1);
    }
    recordFocus(executionFocus, stack, 1);
  }

  private void recordFocus(Map<String, Stat> target, String text, long value) {
    String lower = text.toLowerCase(Locale.ROOT);
    for (String pattern : FOCUS_PATTERNS) {
      if (lower.contains(pattern.toLowerCase(Locale.ROOT))) {
        target.computeIfAbsent(pattern, Stat::new).add(value, 1);
      }
    }
  }

  private void recordFocusByClass(String className, String stack, long bytes) {
    String lower = (className + "\n" + stack).toLowerCase(Locale.ROOT);
    for (String pattern : FOCUS_PATTERNS) {
      if (lower.contains(pattern.toLowerCase(Locale.ROOT))) {
        String key = pattern + " <- " + className;
        allocationFocusByClass.computeIfAbsent(key, Stat::new).add(bytes, 1);
      }
    }
  }

  private void recordFocusByStack(String className, String stack, long bytes) {
    String stackKey = stackKey(className, stack);
    if (stackKey.isEmpty()) {
      return;
    }
    String lower = stackKey.toLowerCase(Locale.ROOT);
    for (String pattern : FOCUS_PATTERNS) {
      if (lower.contains(pattern.toLowerCase(Locale.ROOT))) {
        String key = pattern + "\n" + stackKey;
        allocationFocusByStack.computeIfAbsent(key, Stat::new).add(bytes, 1);
      }
    }
  }

  private void writeReports(Path outputDir) throws IOException {
    try (BufferedWriter out = writer(outputDir.resolve("summary.txt"))) {
      out.write("# Event counts\n");
      writeTop(out, eventCounts, 100, false);
      out.write("\n# Top allocation classes\n");
      writeTop(out, allocationsByClass, 80, true);
      out.write("\n# Top allocation top frames\n");
      writeTop(out, allocationsByTopFrame, 80, true);
    }

    try (BufferedWriter out = writer(outputDir.resolve("allocation-events.txt"))) {
      out.write("# Top allocation classes by bytes\n");
      writeTop(out, allocationsByClass, 200, true);
      out.write("\n# Top allocation top frames\n");
      writeTop(out, allocationsByTopFrame, 200, true);
      out.write("\n# Top allocation stack traces\n");
      writeTopMultiline(out, allocationsByStack, 80, true);
      out.write("\n# Focus allocation matches\n");
      writeTop(out, allocationFocus, 100, true);
      out.write("\n# Focus allocation classes\n");
      writeTop(out, allocationFocusByClass, 200, true);
      out.write("\n# Focus allocation stack traces\n");
      writeTopMultiline(out, allocationFocusByStack, 80, true);
    }

    try (BufferedWriter out = writer(outputDir.resolve("execution-samples.txt"))) {
      out.write("# Top execution frames\n");
      writeTop(out, executionTopFrames, 200, false);
      out.write("\n# Focus execution matches\n");
      writeTop(out, executionFocus, 100, false);
    }

    try (BufferedWriter out = writer(outputDir.resolve("adb-focus.txt"))) {
      out.write("# Focus allocation matches\n");
      writeTop(out, allocationFocus, FOCUS_PATTERNS.length, true);
      out.write("\n# Focus allocation classes\n");
      writeTop(out, allocationFocusByClass, 80, true);
      out.write("\n# Focus allocation stack traces\n");
      writeTopMultiline(out, allocationFocusByStack, 40, true);
      out.write("\n# Focus execution matches\n");
      writeTop(out, executionFocus, FOCUS_PATTERNS.length, false);
    }
  }

  private static BufferedWriter writer(Path file) throws IOException {
    return Files.newBufferedWriter(file, StandardCharsets.UTF_8);
  }

  private static void writeTop(BufferedWriter out, Map<String, Stat> stats,
      int limit, boolean includeBytes) throws IOException {
    List<Stat> rows = new ArrayList<>(stats.values());
    rows.sort(Comparator.comparingLong(Stat::primary).reversed()
        .thenComparing(Stat::name));
    int count = Math.min(limit, rows.size());
    for (int i = 0; i < count; i++) {
      Stat row = rows.get(i);
      if (includeBytes) {
        out.write(String.format(Locale.ROOT, "%12d bytes %8d events  %s%n",
            row.bytes, row.events, row.name));
      } else {
        out.write(String.format(Locale.ROOT, "%8d events  %s%n",
            row.events, row.name));
      }
    }
  }

  private static void writeTopMultiline(BufferedWriter out, Map<String, Stat> stats,
      int limit, boolean includeBytes) throws IOException {
    List<Stat> rows = new ArrayList<>(stats.values());
    rows.sort(Comparator.comparingLong(Stat::primary).reversed()
        .thenComparing(Stat::name));
    int count = Math.min(limit, rows.size());
    for (int i = 0; i < count; i++) {
      Stat row = rows.get(i);
      if (includeBytes) {
        out.write(String.format(Locale.ROOT, "%12d bytes %8d events%n",
            row.bytes, row.events));
      } else {
        out.write(String.format(Locale.ROOT, "%8d events%n", row.events));
      }
      out.write(row.name);
      if (!row.name.endsWith("\n")) {
        out.write('\n');
      }
      out.write('\n');
    }
  }

  private static String className(RecordedEvent event) {
    try {
      RecordedClass objectClass = event.getClass("objectClass");
      if (objectClass != null) {
        return objectClass.getName();
      }
    } catch (IllegalArgumentException ignored) {
      // 不同 JDK 的事件字段可能略有差异，无法读取时归入 unknown。
    }
    return "<unknown>";
  }

  private static long longField(RecordedEvent event, String field, long defaultValue) {
    for (ValueDescriptor descriptor : event.getFields()) {
      if (field.equals(descriptor.getName())) {
        try {
          return event.getLong(field);
        } catch (IllegalArgumentException ignored) {
          return defaultValue;
        }
      }
    }
    return defaultValue;
  }

  private static String stackTrace(RecordedEvent event) {
    RecordedStackTrace stackTrace = event.getStackTrace();
    if (stackTrace == null || stackTrace.getFrames().isEmpty()) {
      return "";
    }
    StringBuilder stack = new StringBuilder();
    for (RecordedFrame frame : stackTrace.getFrames()) {
      stack.append(format(frame)).append('\n');
    }
    return stack.toString();
  }

  private static String topFrame(RecordedEvent event) {
    RecordedStackTrace stackTrace = event.getStackTrace();
    if (stackTrace == null || stackTrace.getFrames().isEmpty()) {
      return "";
    }
    return format(stackTrace.getFrames().get(0));
  }

  private static String stackKey(String className, String stack) {
    if (stack == null || stack.isEmpty()) {
      return "";
    }
    String[] frames = stack.split("\\n");
    StringBuilder key = new StringBuilder();
    key.append(className).append('\n');
    int count = Math.min(12, frames.length);
    for (int i = 0; i < count; i++) {
      if (!frames[i].isEmpty()) {
        key.append("  at ").append(frames[i]).append('\n');
      }
    }
    return key.toString();
  }

  private static String format(RecordedFrame frame) {
    RecordedMethod method = frame.getMethod();
    if (method == null || method.getType() == null) {
      return "<unknown>";
    }
    return method.getType().getName() + "." + method.getName();
  }

  private static final class Stat {
    private final String name;
    private long bytes;
    private long events;

    private Stat(String name) {
      this.name = name;
    }

    private void add(long value) {
      add(value, value);
    }

    private void add(long bytes, long events) {
      this.bytes += Math.max(bytes, 0L);
      this.events += Math.max(events, 0L);
    }

    private long primary() {
      return bytes > 0 ? bytes : events;
    }

    private String name() {
      return name;
    }
  }
}
