package net.xdob.vexra.rmap.function;

import net.xdob.vexra.rmap.CacheObject;

import java.io.Serializable;

@FunctionalInterface
public interface SerialPutFun<R> extends Serializable {
  R apply(CacheObject cache, Object data);
}
