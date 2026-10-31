package io.github.naimelijah.hrsched.access.adapter.in.web;

import static io.github.naimelijah.hrsched.shared.testsupport.TestTenants.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.access.LoggedText;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import io.github.naimelijah.hrsched.shared.testsupport.TestTenants;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A body refused for a member its request type does not have is recorded, as the refusal it is
 * (ADR-058).
 *
 * <p>The body is bound before any use case runs, so a caller the use case would have refused for
 * their role, and logged and counted as such, is answered as malformed instead. Without a line of
 * its own that request would leave nothing behind. So the refusal is logged at WARN, naming who
 * asked and which request type was sent, and counted. It never names the member or anything sent: a
 * member is whatever the caller wrote, and its value may be a person's.
 *
 * <p>Two callers: an Admin creating an account, whose token names them; and somebody signing in,
 * who has no token yet, so the line carries no actor, and whose body holds a password.
 *
 * <p>The log is read from the root logger rather than one class's, so the test holds wherever the
 * line is written from.
 *
 * <p>Every name, password, member and value here is invented for the test.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class UnknownMemberRefusalHttpTest {

  /** Invented, and above the configured minimum, so the password is never what is refused. */
  private static final String A_PASSWORD = "an invented temporary password";

  /** Invented: a member no request here has, of the kind that would carry something personal. */
  private static final String UNKNOWN_MEMBER = "preferredNickname";

  /** Invented: what was sent in it, which must not be written anywhere either. */
  private static final String UNKNOWN_VALUE = "an invented nickname";

  private static final String DENIED = "hrsched.access.denied";
  private static final String KIND = "unknown_member";

  /** The line's constant message, so the line found is this refusal's and nothing else's. */
  private static final String REFUSAL =
      "Refused: a request body carried a member its request type does not have";

  @Autowired private MockMvc mockMvc;

  private Logger root;
  private ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void captureTheLog() {
    root = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME);
    captured = LoggedText.capturing();
    captured.start();
    root.addAppender(captured);
  }

  @AfterEach
  void releaseTheLog() {
    root.detachAppender(captured);
  }

  @Test
  void
      GivenAnAdminSendingAMemberTheRequestLacks_WhenCreatingAnAccount_ThenOneWarningNamesThemAndTheRequestType()
          throws Exception {
    String admin = admin(anOrganisation());
    String actor = whoAmI(admin);
    captured.list.clear();

    createWithAnUnknownMember(admin, anInventedSignInName());

    ILoggingEvent line = theOnlyRefusalLine();
    assertThat(line.getLevel()).isEqualTo(Level.WARN);
    assertThat(fieldsOf(line))
        .containsOnly(
            Map.entry("actor", UUID.fromString(actor)),
            Map.entry("requestType", CreateAccountRequest.class.getSimpleName()));
  }

  @Test
  void GivenAnAdminSendingAMemberTheRequestLacks_WhenCreatingAnAccount_ThenItIsCountedOnce()
      throws Exception {
    String admin = admin(anOrganisation());
    double before = refusalsOfKind(KIND);

    createWithAnUnknownMember(admin, anInventedSignInName());

    assertThat(refusalsOfKind(KIND))
        .describedAs("%s{kind=%s} after one refusal", DENIED, KIND)
        .isEqualTo(before + 1);
  }

  @Test
  void
      GivenAnAdminSendingAMemberTheRequestLacks_WhenCreatingAnAccount_ThenNothingLoggedCarriesWhatWasSent()
          throws Exception {
    String admin = admin(anOrganisation());
    String signInName = anInventedSignInName();
    captured.list.clear();

    createWithAnUnknownMember(admin, signInName);

    assertThat(everything())
        .doesNotContain(UNKNOWN_MEMBER)
        .doesNotContain(UNKNOWN_VALUE)
        .doesNotContain(signInName)
        .doesNotContain(A_PASSWORD);
  }

  @Test
  void
      GivenSomebodySigningInWithAMemberTheRequestLacks_WhenRefused_ThenOneWarningNamesOnlyTheRequestType()
          throws Exception {
    String signInName = anInventedSignInName();
    captured.list.clear();

    mockMvc
        .perform(
            post("/api/auth/sign-in")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"signInName\":\"%s\",\"password\":\"%s\",\"%s\":\"%s\"}"
                        .formatted(signInName, A_PASSWORD, UNKNOWN_MEMBER, UNKNOWN_VALUE)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Malformed request"));

    Map<String, Object> noActor = new HashMap<>();
    noActor.put("actor", null);
    noActor.put("requestType", SignInRequest.class.getSimpleName());
    assertThat(fieldsOf(theOnlyRefusalLine())).isEqualTo(noActor);
    assertThat(everything())
        .doesNotContain(UNKNOWN_MEMBER)
        .doesNotContain(UNKNOWN_VALUE)
        .doesNotContain(signInName)
        .doesNotContain(A_PASSWORD);
  }

  /**
   * Only the unknown member is recorded: a body that is not JSON at all is a fault, not a probe.
   */
  @Test
  void GivenABodyThatIsNotJson_WhenSigningIn_ThenNoRefusalIsRecorded() throws Exception {
    double before = refusalsOfKind(KIND);
    captured.list.clear();

    mockMvc
        .perform(
            post("/api/auth/sign-in")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"signInName\":"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Malformed request"));

    assertThat(captured.list)
        .noneSatisfy(event -> assertThat(event.getFormattedMessage()).isEqualTo(REFUSAL));
    assertThat(refusalsOfKind(KIND)).isEqualTo(before);
  }

  private void createWithAnUnknownMember(String adminToken, String signInName) throws Exception {
    mockMvc
        .perform(
            with(post("/api/accounts"), adminToken)
                .content(
                    ("{\"signInName\":\"%s\",\"temporaryPassword\":\"%s\",\"role\":\"%s\","
                            + "\"%s\":\"%s\"}")
                        .formatted(
                            signInName, A_PASSWORD, Role.SCHEDULER, UNKNOWN_MEMBER, UNKNOWN_VALUE)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Malformed request"));
  }

  private ILoggingEvent theOnlyRefusalLine() {
    List<ILoggingEvent> refusals =
        captured.list.stream()
            .filter(event -> REFUSAL.equals(event.getFormattedMessage()))
            .toList();
    assertThat(refusals)
        .describedAs("one line saying \"%s\", among: %s", REFUSAL, everything())
        .hasSize(1);
    return refusals.getFirst();
  }

  /** A map that keeps a null value, which a field logged for an absent actor holds. */
  private static Map<String, Object> fieldsOf(ILoggingEvent event) {
    Map<String, Object> fields = new HashMap<>();
    event.getKeyValuePairs().forEach(pair -> fields.put(pair.key, pair.value));
    return fields;
  }

  private static String anInventedSignInName() {
    return "colleague-%s@example.com".formatted(UUID.randomUUID());
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

  /** Everything the captured events carry, as one string to search: see {@link LoggedText}. */
  private String everything() {
    return LoggedText.of(captured.list);
  }
}
