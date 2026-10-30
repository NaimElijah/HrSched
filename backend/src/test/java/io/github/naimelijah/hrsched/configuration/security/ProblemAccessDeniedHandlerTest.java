package io.github.naimelijah.hrsched.configuration.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Specification of what the security chain's refusal of a signed-in caller writes to the log.
 *
 * <p>Every refusal log in this application names whoever was refused under {@code actor}, and
 * {@code account} means the account acted on. The chain's refusal must follow the same key, or a
 * query for everything one caller was refused misses the refusals the chain made, and reads the
 * caller as the target.
 *
 * <p>Driven directly rather than over HTTP, because what is asserted is the line Logback receives,
 * not how Spring renders it. The account identifier is invented for the test.
 */
class ProblemAccessDeniedHandlerTest {

  /** Invented: the identifier the token adapter would give as the refused caller's name. */
  private static final String INVENTED_ACCOUNT = UUID.randomUUID().toString();

  private final ProblemAccessDeniedHandler handler =
      new ProblemAccessDeniedHandler(JsonMapper.builder().build(), new SimpleMeterRegistry());

  private ch.qos.logback.classic.Logger logger;
  private ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void captureTheLog() {
    logger =
        ((LoggerContext) LoggerFactory.getILoggerFactory())
            .getLogger(ProblemAccessDeniedHandler.class.getName());
    captured = new ListAppender<>();
    captured.start();
    logger.addAppender(captured);
  }

  @AfterEach
  void releaseTheLogAndTheCaller() {
    logger.detachAppender(captured);
    SecurityContextHolder.clearContext();
  }

  @Test
  void GivenASignedInCallerTheChainRefuses_WhenHandled_ThenOneWarningNamesThemAsTheActor()
      throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken(INVENTED_ACCOUNT, null, "ROLE_OPERATOR"));

    handler.handle(
        new MockHttpServletRequest(),
        new MockHttpServletResponse(),
        new AccessDeniedException("refused"));

    assertThat(captured.list).hasSize(1);
    ILoggingEvent event = captured.list.getFirst();
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    assertThat(fieldsOf(event)).containsOnly(Map.entry("actor", INVENTED_ACCOUNT));
  }

  private static Map<String, Object> fieldsOf(ILoggingEvent event) {
    return event.getKeyValuePairs().stream()
        .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
  }
}
