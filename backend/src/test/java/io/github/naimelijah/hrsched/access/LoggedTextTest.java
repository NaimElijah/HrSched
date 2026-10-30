package io.github.naimelijah.hrsched.access;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.MarkerFactory;

/**
 * That {@link LoggedText} searches the parts of a line that are easiest to lose: the MDC it was
 * logged with, which the appender {@link LoggedText#capturing} hands out has to keep, and a marker.
 *
 * <p>Every "nothing logged contains this" test in the access context relies on both, and they
 * cannot notice either failing: a search of an empty MDC finds no leak, which is what they assert.
 * The logger here is not additive, so no other appender, the console above all, reads the MDC first
 * and hides whether this one did.
 *
 * <p>The key, the values and the marker's name are invented for the test.
 */
class LoggedTextTest {

  private static final String KEY = "an-invented-key";
  private static final String VALUE = "an invented value only the MDC carries";
  private static final String MARKER = "an-invented-marker-only-the-marker-carries";

  private Logger logger;
  private ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void captureALoggerOfItsOwn() {
    logger =
        ((LoggerContext) LoggerFactory.getILoggerFactory())
            .getLogger(LoggedTextTest.class.getName());
    logger.setAdditive(false);
    captured = LoggedText.capturing();
    captured.start();
    logger.addAppender(captured);
  }

  @AfterEach
  void releaseIt() {
    logger.detachAppender(captured);
    logger.setAdditive(true);
  }

  @Test
  void GivenAnMdcValueClearedOnceTheLineIsLogged_WhenSearching_ThenItIsStillFound() {
    try (MDC.MDCCloseable ignored = MDC.putCloseable(KEY, VALUE)) {
      logger.info("A line logged while the MDC held a value");
    }

    assertThat(LoggedText.of(captured.list))
        .describedAs("the MDC as it stood when the line was logged, not as it stands now")
        .contains(VALUE);
  }

  /** The encoder ships a marker as a tag, so a value carried that way is searched as well. */
  @Test
  void GivenALineCarryingAMarker_WhenSearching_ThenTheMarkerIsFound() {
    logger.info(MarkerFactory.getDetachedMarker(MARKER), "A line logged with a marker");

    assertThat(LoggedText.of(captured.list)).contains(MARKER);
  }
}
