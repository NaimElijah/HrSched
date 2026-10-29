package io.github.naimelijah.hrsched.access;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Everything captured log events carry to wherever the log is shipped, as one string to search.
 *
 * <p>The message alone is not enough. A field attached with {@code addKeyValue}, a value in the
 * MDC, and an exception with its message, its causes and its stack are each written out by the
 * encoder as well, and each is a place a sign-in name, a password or a token could leak. A search
 * that left one of them out would pass while the leak was there.
 *
 * <p>Kept in the access test sources, beside the tests that search a log for what access must never
 * write; it is test support for this context only.
 */
public final class LoggedText {

  private LoggedText() {}

  /**
   * @param events what a test's appender captured
   * @return every level, message, field, MDC entry and exception those events carry, one event per
   *     line
   */
  public static String of(List<ILoggingEvent> events) {
    return events.stream().map(LoggedText::of).collect(Collectors.joining("\n"));
  }

  private static String of(ILoggingEvent event) {
    StringBuilder text =
        new StringBuilder()
            .append(event.getLevel())
            .append(' ')
            .append(event.getFormattedMessage());
    if (event.getKeyValuePairs() != null) {
      text.append(' ').append(event.getKeyValuePairs());
    }
    text.append(' ').append(event.getMDCPropertyMap());
    if (event.getThrowableProxy() != null) {
      text.append(' ').append(ThrowableProxyUtil.asString(event.getThrowableProxy()));
    }
    return text.toString();
  }
}
