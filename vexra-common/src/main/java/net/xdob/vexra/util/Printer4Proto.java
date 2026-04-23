package net.xdob.vexra.util;

import com.google.protobuf.MessageOrBuilder;

import java.util.function.Consumer;


public class Printer4Proto {

	public static void printJson(MessageOrBuilder message, Consumer<String> consumer) {
		consumer.accept(toJson( message));
	}

  /**
   * protobuf 转 json
   * @deprecated
   * @see ProtoPrinter#toJson(MessageOrBuilder)
   */
  @Deprecated
	public static String toJson(MessageOrBuilder message) {
		return ProtoPrinter.toJson( message);
	}

	public static void printText(MessageOrBuilder message, Consumer<String> consumer) {
		String text = ProtoPrinter.toText( message);
		consumer.accept(text);
	}

}
