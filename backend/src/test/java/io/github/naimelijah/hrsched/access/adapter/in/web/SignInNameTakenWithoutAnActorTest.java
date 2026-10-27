package io.github.naimelijah.hrsched.access.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.naimelijah.hrsched.access.domain.model.SignInNameTakenException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * A taken sign-in name refused when no actor reached the handler.
 *
 * <p>Every route this handler covers that creates an account is authenticated, so today the actor
 * is always there. The handler must still not depend on it: an {@code @AuthenticationPrincipal}
 * resolves to null whenever the principal is not an {@code Actor}, and a refusal that then threw
 * would turn a 409 into a 500 and lose the record of the refusal along with it. The answer stays
 * the 409, and the line is still written, without an actor.
 *
 * <p>Driven without Spring, against the handler directly, because no request can reach it with a
 * null actor through the chain as configured.
 *
 * <p>The exception's cause is invented for the test.
 */
class SignInNameTakenWithoutAnActorTest {

  private static final String ACTOR = "actor";

  private final AccessExceptionHandler handler =
      new AccessExceptionHandler(new SimpleMeterRegistry());

  private ch.qos.logback.classic.Logger logger;
  private ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void captureTheLog() {
    logger =
        ((LoggerContext) LoggerFactory.getILoggerFactory())
            .getLogger(AccessExceptionHandler.class.getName());
    captured = new ListAppender<>();
    captured.start();
    logger.addAppender(captured);
  }

  @AfterEach
  void releaseTheLog() {
    logger.detachAppender(captured);
  }

  @Test
  void GivenNoActor_WhenASignInNameIsTaken_ThenTheAnswerIsStillTheConflict() {
    ProblemDetail problem = handler.handleSignInNameTaken(aTakenName(), null);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(problem.getTitle()).isEqualTo("Sign-in name taken");
    assertThat(problem.getDetail()).isEqualTo("That sign-in name is already in use.");
  }

  @Test
  void GivenNoActor_WhenASignInNameIsTaken_ThenAWarningIsWrittenWithoutAnActor() {
    handler.handleSignInNameTaken(aTakenName(), null);

    assertThat(captured.list)
        .describedAs("one WARN line, carrying no actor")
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(pairsOf(event))
                  .filteredOn(pair -> ACTOR.equals(pair.key))
                  .allSatisfy(pair -> assertThat(pair.value).isNull());
            });
  }

  private static SignInNameTakenException aTakenName() {
    return new SignInNameTakenException(new IllegalStateException("an invented collision"));
  }

  private static List<KeyValuePair> pairsOf(ILoggingEvent event) {
    return Objects.requireNonNullElse(event.getKeyValuePairs(), List.of());
  }
}
