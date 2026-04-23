package net.xdob.vexra.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class LogTracer {

  final Logger LOG = LoggerFactory.getLogger(this.getClass());
  private final String name;
  private final File traceFile;
  private Boolean trace;
	private volatile long lastModified;
	private final Set<String> values= ConcurrentHashMap.newKeySet();

  public LogTracer(String name) {
    this.name = name;
    this.traceFile = Paths.get("/etc/vexra/trace", name).toFile();
  }

  public String getName() {
    return name;
  }

  public boolean isTrace(){
    if(trace==null||!trace.equals(this.traceFile.exists())){
      trace = this.traceFile.exists();
      LOG.info("trace {}={}", this.getName(), trace);
    }
    return trace;
  }

	public void trace(Runnable trace){
		if(isTrace() && trace!=null){
			trace.run();
		}
	}

	public void trace(String name, Runnable trace){
		if(isTrace(name) && trace!=null){
			trace.run();
		}
	}

	public boolean isTrace(String name){
		if(this.traceFile.exists()){
			if(this.traceFile.lastModified()!= lastModified){
				if(this.traceFile.length()<SizeInBytes.ONE_MB.getSize()){
					try {
						List<String> strings = Files.readAllLines(this.traceFile.toPath());
						values.clear();
						values.addAll(strings);
					}catch (Exception e){
						LOG.warn("read trace file error", e);
					}
				}else {
					LOG.warn("trace file size > 1MB");
				}
				lastModified = this.traceFile.lastModified();
				LOG.info("trace {}={}", this.getName(), values);
			}
		}
		return values.contains(name);
	}

	public <T> T first(Class<T> tClass, T defValue){
		return values.stream()
				.map(value->Types.cast(value, tClass))
				.findFirst()
				.orElse( defValue);
	}

  public static LogTracer c(String name){
    return new LogTracer(name);
  }

}
