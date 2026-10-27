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
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
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
 * An Admin refused an account because its sign-in name is already held in another organisation.
 *
 * <p>Uniqueness is platform-wide, so this refusal is the one place an Admin's request collides with
 * data they cannot see. Trying name after name is a way to learn who holds an account on the
 * platform, and the requirements' Auditability rule records every refusal: so this one is counted
 * and logged, like a cross-tenant attempt. What is logged names who asked, never the name asked for
 * - that name is a person's.
 *
 * <p>The caller's answer is pinned too, because recording a refusal must not change what the caller
 * is told.
 *
 * <p>The log is read from the root logger rather than one class's, so the test holds wherever the
 * line is written from.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 *
 * <p>Every password and sign-in name here is invented for the test.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class SignInNameTakenRefusalHttpTest {

  /** Invented, and above the configured minimum. */
  private static final String A_PASSWORD = "an invented temporary password";

  private static final String DENIED = "hrsched.access.denied";
  private static final String KIND = "sign_in_name_taken";

  /**
   * What the WARN line's message must say, so the line is this refusal's and not merely any warning
   * that happens to carry the actor.
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
  void GivenANameHeldInAnotherOrganisation_WhenAnAdminCreatesAnAccount_ThenTheAnswerIsUnchanged()
      throws Exception {
    String taken = aNameHeldElsewhere();

    MockHttpServletResponse response = create(admin(anOrganisation()), taken);

    assertThat(response.getStatus()).isEqualTo(409);
    String body = response.getContentAsString();
    assertThat(JsonPath.<String>read(body, "$.title")).isEqualTo("Sign-in name taken");
    assertThat(JsonPath.<String>read(body, "$.detail"))
        .isEqualTo("That sign-in name is already in use.");
    assertThat(body).doesNotContain(taken);
  }

  @Test
  void GivenANameHeldInAnotherOrganisation_WhenAnAdminCreatesAnAccount_ThenTheRefusalIsCounted()
      throws Exception {
    String taken = aNameHeldElsewhere();
    String admin = admin(anOrganisation());
    double before = refusalsOfKind(KIND);

    create(admin, taken);

    assertThat(refusalsOfKind(KIND))
        .describedAs("%s{kind=%s} after one refusal", DENIED, KIND)
        .isEqualTo(before + 1);
  }

  @Test
  void
      GivenANameHeldInAnotherOrganisation_WhenAnAdminCreatesAnAccount_ThenAWarningNamesTheActingAccount()
          throws Exception {
    String taken = aNameHeldElsewhere();
    String admin = admin(anOrganisation());
    String actor = whoAmI(admin);
    captured.list.clear();

    create(admin, taken);

    assertThat(captured.list)
        .describedAs(
            "a WARN line saying \"%s\" and carrying the acting account %s, among: %s",
            NAMES_THE_REFUSAL, actor, everything())
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains(NAMES_THE_REFUSAL);
              assertThat(String.valueOf(event.getKeyValuePairs())).contains(actor);
            });
  }

  @Test
  void
      GivenANameHeldInAnotherOrganisation_WhenAnAdminCreatesAnAccount_ThenNothingLoggedContainsTheName()
          throws Exception {
    String taken = aNameHeldElsewhere();
    String admin = admin(anOrganisation());
    captured.list.clear();

    create(admin, taken);

    assertThat(everything()).doesNotContain(taken);
  }

  /**
   * @return a sign-in name an account in a freshly onboarded organisation already holds
   */
  private String aNameHeldElsewhere() throws Exception {
    String taken = "colleague-%s@example.com".formatted(UUID.randomUUID());
    assertThat(create(admin(anOrganisation()), taken).getStatus())
        .describedAs("precondition: the first organisation takes the name")
        .isEqualTo(201);
    return taken;
  }

  private MockHttpServletResponse create(String adminToken, String signInName) throws Exception {
    return mockMvc
        .perform(
            with(post("/api/accounts"), adminToken)
                .content(
                    "{\"signInName\":\"%s\",\"temporaryPassword\":\"%s\",\"role\":\"%s\"}"
                        .formatted(signInName, A_PASSWORD, Role.SCHEDULER)))
        .andReturn()
        .getResponse();
  }

  private OrganisationId anOrganisation() throws Exception {
    String body =
        mockMvc
            .perform(
                with(post("/api/organisations"), TestTenants.operatorToken(mockMvc))
                    .content(TestTenants.onboarding("Acme %s".formatted(UUID.randomUUID()))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return new OrganisationId(UUID.fromString(JsonPath.read(body, "$.id")));
  }

  private String admin(OrganisationId organisation) throws Exception {
    return TestTenants.tokenFor(mockMvc, organisation, Role.ADMIN);
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

  /** Every message and every attached field, as one string to search. */
  private String everything() {
    return captured.list.stream()
        .map(
            event ->
                event.getLevel()
                    + " "
                    + event.getFormattedMessage()
                    + " "
                    + (event.getKeyValuePairs() == null ? "" : event.getKeyValuePairs()))
        .reduce("", (all, line) -> all + "\n" + line);
  }
}
