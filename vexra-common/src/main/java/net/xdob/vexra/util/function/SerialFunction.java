package net.xdob.vexra.util.function;

import java.io.Serializable;

@FunctionalInterface
public interface SerialFunction<INPUT, OUTPUT> extends Serializable {
  OUTPUT apply(INPUT input);
}
