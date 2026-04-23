
package net.xdob.vexra.datastream;

import net.xdob.vexra.conf.Parameters;
import net.xdob.vexra.util.ReflectionUtils;

/**
 * 支持的DataStream类型
 * 暂时只支持NETTY
 */
public enum SupportedDataStreamType implements DataStreamType {
  DISABLED("net.xdob.vexra.client.DisabledDataStreamClientFactory",
      "net.xdob.vexra.server.DisabledDataStreamServerFactory"),
  NETTY("net.xdob.vexra.netty.NettyDataStreamFactory");

  private static final Class<?>[] ARG_CLASSES = {Parameters.class};

  public static SupportedDataStreamType valueOfIgnoreCase(String s) {
    return valueOf(s.toUpperCase());
  }

  private final String clientFactoryClassName;
  private final String serverFactoryClassName;

  SupportedDataStreamType(String clientFactoryClassName, String serverFactoryClassName) {
    this.clientFactoryClassName = clientFactoryClassName;
    this.serverFactoryClassName = serverFactoryClassName;
  }

  SupportedDataStreamType(String factoryClassName) {
    this(factoryClassName, factoryClassName);
  }

  @Override
  public DataStreamFactory newClientFactory(Parameters parameters) {
    final Class<? extends DataStreamFactory> clazz = ReflectionUtils.getClass(
        clientFactoryClassName, DataStreamFactory.class);
    return ReflectionUtils.newInstance(clazz, ARG_CLASSES, parameters);
  }

  @Override
  public DataStreamFactory newServerFactory(Parameters parameters) {
    final Class<? extends DataStreamFactory> clazz = ReflectionUtils.getClass(
        serverFactoryClassName, DataStreamFactory.class);
    return ReflectionUtils.newInstance(clazz, ARG_CLASSES, parameters);
  }
}
