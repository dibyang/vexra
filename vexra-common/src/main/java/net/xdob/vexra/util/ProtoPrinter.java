package net.xdob.vexra.util;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.TextFormat;
import com.google.protobuf.util.JsonFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProtoPrinter {
  static final Logger LOG = LoggerFactory.getLogger(ProtoPrinter.class);

  private static final JsonFormat.Printer jsonPrinter = JsonFormat.printer()
      .includingDefaultValueFields()
      .omittingInsignificantWhitespace();
  private static final TextFormat.Printer textPrinter = TextFormat.printer();

  public static String toJson(MessageOrBuilder message) {
    try {
      return jsonPrinter.print(message);
    } catch (InvalidProtocolBufferException e) {
      LOG.warn("toJson error", e);
    }
    return "";
  }

  public static String toText(MessageOrBuilder message) {
    return textPrinter.printToString(message);
  }
}
