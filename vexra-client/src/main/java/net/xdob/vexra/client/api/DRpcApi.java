package net.xdob.vexra.client.api;

import net.xdob.vexra.protocol.BeanTarget;
import net.xdob.vexra.util.function.SerialFunction;

public interface DRpcApi {
  <T,R> R invokeRpc(SerialFunction<T,R> fun);
  <T,R> R invokeRpc(Class<T> clazz, SerialFunction<T,R> fun) ;
  <T,R> R invokeRpc(BeanTarget<T> target, SerialFunction<T,R> fun) ;
}
