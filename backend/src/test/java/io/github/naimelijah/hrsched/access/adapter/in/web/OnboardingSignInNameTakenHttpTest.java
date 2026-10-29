package io.github.naimelijah.hrsched.access.adapter.in.web;

import static io.github.naimelijah.hrsched.shared.testsupport.TestTenants.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.access.LoggedText;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import io.github.naimelijah.hrsched.shared.testsupport.TestTenants;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The operator refused an onboarding because the first Admin's sign-in name is already held.
 *
 * <p>The same refusal as an Admin's taken name ({@code SignInNameTakenRefusalHttpTest}), reached
 * through the other route that creates an account from a submitted name. It is recorded the same
 * way: counted, and logged at WARN with who asked - here the operator - and never the name asked
 * for, which is a person's.
 *
 * <p>The log is read from the root logger rather than one class's, so the test holds wherever the
 * line is written from.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 *
 * <p>Every name here is invented for the test.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class OnboardingSignInNameTakenHttpTest {

  private static final String DENIED = "hrsched.access.denied";
  private static final String KIND = "sign_in_name_taken";

  /**
   * What the WARN line's message must say, so the line is this refusal's and not merely any warning
   * that happens to carry the operator.
   */
  private static final String NAMES_THE_REFUSAL = "sign-in name already in use";

  @Autowired private MockMvc mockMvc;

  private Logger root;
  private ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void captureTheLog() {
    root = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME);
    captured = new ListAppender<>();
    captured.start();
    root.addAppender(captured);
  }

  @AfterEach
  void releaseTheLog() {
    root.detachAppender(captured);
  }

  @Test
  void GivenAFirstAdminNameAlreadyHeld_WhenTheOperatorOnboards_ThenTheRefusalIsCounted()
      throws Exception {
    String taken = aNameAlreadyHeld();
    double before = refusalsOfKind(KIND);

    MockHttpServletResponse response = onboard(taken);

    assertThat(response.getStatus()).describedAs("precondition: refused").isEqualTo(409);
    assertThat(refusalsOfKind(KIND))
        .describedAs("%s{kind=%s} after one refusal", DENIED, KIND)
        .isEqualTo(before + 1);
  }

  @Test
  void GivenAFirstAdminNameAlreadyHeld_WhenTheOperatorOnboards_ThenAWarningNamesTheOperator()
      throws Exception {
    String taken = aNameAlreadyHeld();
    String operator = whoAmI(TestTenants.operatorToken(mockMvc));
    captured.list.clear();

    onboard(taken);

    assertThat(captured.list)
        .describedAs(
            "a WARN line saying \"%s\" and carrying the operator %s, among: %s",
            NAMES_THE_REFUSAL, operator, everything())
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains(NAMES_THE_REFUSAL);
              assertThat(String.valueOf(event.getKeyValuePairs())).contains(operator);
            });
  }

  @Test
  void GivenAFirstAdminNameAlreadyHeld_WhenTheOperatorOnboards_ThenNothingLoggedContainsTheName()
      throws Exception {
    String taken = aNameAlreadyHeld();
    String token = TestTenants.operatorToken(mockMvc);
    captured.list.clear();

    onboard(taken, token);

    assertThat(everything()).doesNotContain(taken);
  }

  /**
   * @return the first Admin sign-in name of an organisation just onboarded
   */
  private String aNameAlreadyHeld() throws Exception {
    String taken = "first-admin-%s@example.com".formatted(UUID.randomUUID());
    assertThat(onboard(taken).getStatus())
        .describedAs("precondition: the first onboarding takes the name")
        .isEqualTo(201);
    return taken;
  }

  private MockHttpServletResponse onboard(String firstAdmin) throws Exception {
    return onboard(firstAdmin, TestTenants.operatorToken(mockMvc));
  }

  private MockHttpServletResponse onboard(String firstAdmin, String operatorToken)
      throws Exception {
    return mockMvc
        .perform(
            with(post("/api/organisations"), operatorToken)
                .content(
                    TestTenants.onboarding("Acme %s".formatted(UUID.randomUUID()), firstAdmin)))
        .andReturn()
        .getResponse();
  }

  /**
   * @return the account identity the token names, read back from the session endpoint
   */
  private String whoAmI(String token) throws Exception {
    String body =
        mockMvc
            .perform(with(get("/api/auth/session"), token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.userId");
  }

  private double refusalsOfKind(String kind) {
    Counter counter =
        mockMvc
            .getDispatcherServlet()
            .getWebApplicationContext()
            .getBean(MeterRegistry.class)
            .find(DENIED)
            .tag("kind", kind)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  /** Everything the captured events carry, as one string to search: see {@link LoggedText}. */
  private String everything() {
    return LoggedText.of(captured.list);
  }
}
