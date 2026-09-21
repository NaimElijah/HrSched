package io.github.naimelijah.hrsched.access.adapter.in.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.naimelijah.hrsched.access.application.port.in.BootstrapOperatorCommand;
import io.github.naimelijah.hrsched.access.application.port.in.BootstrapOperatorUseCase;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * What the startup runner does with the configuration it is given, and what it says about it.
 *
 * <p>Half-configured is the case worth having: a deployment that set a name and forgot a password
 * would otherwise start, create nothing, and look exactly like one that meant to create nothing.
 * Refusing is how the two stay distinguishable.
 *
 * <p>The logging assertion goes through Logback's own appender rather than by capturing standard
 * output, because this application logs structured JSON through Logback and the fields are attached
 * as key-value pairs rather than written into the message — capturing the stream would test the
 * encoder instead of what this class chose to say.
 *
 * <p>Every name and password here is invented for the test.
 */
class OperatorBootstrapTest {

  private static final String OPERATOR_NAME = "operator@example.com";
  private static final String A_PASSWORD = "an invented password for a test";

  private final RecordingBootstrap useCase = new RecordingBootstrap();

  private ch.qos.logback.classic.Logger logger;
  private ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void captureTheLog() {
    logger =
        ((LoggerContext) LoggerFactory.getILoggerFactory())
            .getLogger(OperatorBootstrap.class.getName());
    captured = new ListAppender<>();
    captured.start();
    logger.addAppender(captured);
  }

  @AfterEach
  void releaseTheLog() {
    logger.detachAppender(captured);
  }

  @Test
  void GivenNoBootstrapConfiguration_WhenRun_ThenNothingIsCreated() throws Exception {
    new OperatorBootstrap(useCase, new BootstrapOperatorProperties(null, null)).run(null);

    assertThat(useCase.asked()).isEmpty();
  }

  @Test
  void GivenOnlyASignInName_WhenRun_ThenItFails() {
    OperatorBootstrap bootstrap =
        new OperatorBootstrap(useCase, new BootstrapOperatorProperties(OPERATOR_NAME, null));

    assertThatThrownBy(() -> bootstrap.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("hrsched.bootstrap.operator.password");
  }

  @Test
  void GivenOnlyAPassword_WhenRun_ThenItFails() {
    OperatorBootstrap bootstrap =
        new OperatorBootstrap(useCase, new BootstrapOperatorProperties(null, A_PASSWORD));

    assertThatThrownBy(() -> bootstrap.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("hrsched.bootstrap.operator.sign-in-name");
  }

  @Test
  void GivenBothHalves_WhenRun_ThenTheOperatorIsEnsured() throws Exception {
    new OperatorBootstrap(useCase, configured()).run(null);

    assertThat(useCase.asked())
        .containsExactly(new BootstrapOperatorCommand(OPERATOR_NAME, A_PASSWORD));
  }

  @Test
  void GivenABootstrap_WhenRun_ThenNeitherTheNameNorThePasswordIsLogged() throws Exception {
    new OperatorBootstrap(useCase, configured()).run(null);

    assertThat(captured.list).isNotEmpty();
    assertThat(everythingLogged()).doesNotContain(OPERATOR_NAME).doesNotContain(A_PASSWORD);
  }

  @Test
  void GivenTheOperatorAlreadyExists_WhenRun_ThenItStillSaysSoWithoutNamingAnybody()
      throws Exception {
    useCase.willReport(false);

    new OperatorBootstrap(useCase, configured()).run(null);

    assertThat(captured.list).isNotEmpty();
    assertThat(everythingLogged()).doesNotContain(OPERATOR_NAME).doesNotContain(A_PASSWORD);
  }

  private static BootstrapOperatorProperties configured() {
    return new BootstrapOperatorProperties(OPERATOR_NAME, A_PASSWORD);
  }

  /** Every message and every attached field, as one string to search. */
  private String everythingLogged() {
    return captured.list.stream()
        .map(
            event ->
                event.getFormattedMessage()
                    + " "
                    + (event.getKeyValuePairs() == null ? "" : event.getKeyValuePairs()))
        .reduce("", (all, line) -> all + "\n" + line);
  }

  /** Remembers what it was asked to ensure, and answers whatever the test told it to. */
  private static final class RecordingBootstrap implements BootstrapOperatorUseCase {

    private final List<BootstrapOperatorCommand> asked = new ArrayList<>();
    private boolean created = true;

    void willReport(boolean created) {
      this.created = created;
    }

    List<BootstrapOperatorCommand> asked() {
      return List.copyOf(asked);
    }

    @Override
    public boolean ensureOperator(BootstrapOperatorCommand command) {
      asked.add(command);
      return created;
    }
  }
}
