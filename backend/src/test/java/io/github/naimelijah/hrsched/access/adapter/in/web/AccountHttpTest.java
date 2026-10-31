package io.github.naimelijah.hrsched.access.adapter.in.web;

import static io.github.naimelijah.hrsched.shared.testsupport.TestTenants.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.access.application.port.out.AccountRepository;
import io.github.naimelijah.hrsched.access.application.port.out.PasswordHasher;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.PasswordPolicy;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.access.domain.model.TenantMembership;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import io.github.naimelijah.hrsched.shared.testsupport.TestTenants;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Drives account administration over HTTP (requirement F15).
 *
 * <p><strong>Nothing here names an organisation.</strong> These routes take it from the token, so
 * every request below acts in whichever organisation the bearer belongs to — which is why the
 * cross-tenant tests reach for another organisation's <em>account</em> rather than its identifier,
 * and get the ordinary 404.
 *
 * <p>Refusals come from three places and mean three different things. No token is a 401 from the
 * chain. The wrong role is a 403 from the URL rule, and the platform operator is refused by the
 * same rule because it never holds a tenant role (ADR-030). An Admin naming an account that is not
 * theirs reaches the use case and is refused with a 404, because a 403 would confirm it exists.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class AccountHttpTest {

  /** Invented for these tests, and above the configured minimum. */
  private static final String A_PASSWORD = "an invented temporary password";

  /** Invented, and deliberately below it. */
  private static final String TOO_SHORT = "brief";

  /** Invented: one byte of UTF-8, repeated to make a password over the policy's maximum. */
  private static final String ONE_BYTE_CHARACTER = "x";

  /** Invented, and what an account replaces the password somebody else chose for it with. */
  private static final String THEIR_OWN_PASSWORD = "a password that admin chose themselves";

  /**
   * The one sentence every R34 refusal answers with, whichever of creating, changing or restoring
   * was refused: it is what the Admin reads, so it has to be true of all three.
   */
  private static final String UNLINKED_EMPLOYEE_REFUSED =
      "An Employee account must be linked to one of this organisation's employees; one that is not"
          + " cannot be restored until it is.";

  /**
   * What an Admin is told of an account their organisation does not hold, whether it is another
   * organisation's or nobody's: the detail every 404 gives.
   */
  private static final String NOT_FOUND_DETAIL = "No such resource.";

  /** Invented: when an account {@link #migrated} stands for was created. */
  private static final Instant STORED_BEFORE_R34 = Instant.parse("2026-09-01T09:00:00Z");

  @Autowired private MockMvc mockMvc;
  @Autowired private AccountRepository accounts;
  @Autowired private PasswordHasher passwords;
  @Autowired private PasswordPolicy policy;

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

  private static String anInventedSignInName() {
    return "colleague-%s@example.com".formatted(UUID.randomUUID());
  }

  private static String creating(String signInName, Role role) {
    return "{\"signInName\":\"%s\",\"temporaryPassword\":\"%s\",\"role\":\"%s\"}"
        .formatted(signInName, A_PASSWORD, role);
  }

  private static String linkedTo(String signInName, String employee) {
    return ("{\"signInName\":\"%s\",\"temporaryPassword\":\"%s\",\"role\":\"EMPLOYEE\","
            + "\"employee\":\"%s\"}")
        .formatted(signInName, A_PASSWORD, employee);
  }

  /** Added through the workforce API, as an Admin, because that is the only way one exists. */
  private String anEmployee(OrganisationId organisation) throws Exception {
    String body =
        mockMvc
            .perform(
                with(post("/api/employees"), admin(organisation))
                    .content("{\"name\":\"Ada Lovelace\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.id");
  }

  /**
   * Stores, through the repository, an Employee account linked to nobody, as V9 left one: disabled.
   * No request can make one, so none is driven.
   *
   * @return its identifier
   */
  private String migrated(OrganisationId organisation, String signInName) {
    UserId id = UserId.newId();
    accounts.save(
        new Account(
            id,
            new SignInName(signInName),
            passwords.hash(A_PASSWORD),
            new TenantMembership(organisation, Role.EMPLOYEE, Optional.empty()),
            false,
            false,
            0,
            Optional.empty(),
            STORED_BEFORE_R34));
    return id.value().toString();
  }

  /**
   * Stores, through the repository, a member linked to nobody who has already replaced the password
   * they were given, so the token they sign in with holds their role from the first request. An
   * account created over HTTP would hold no role until it had (ADR-029).
   *
   * @return its identifier
   */
  private String aMemberWhoChoseTheirOwnPassword(
      OrganisationId organisation, Role role, String signInName) {
    UserId id = UserId.newId();
    accounts.save(
        new Account(
            id,
            new SignInName(signInName),
            passwords.hash(A_PASSWORD),
            new TenantMembership(organisation, role, Optional.empty()),
            false,
            Instant.now()));
    return id.value().toString();
  }

  /**
   * @return the identifier of an Employee account this Admin just created, linked to that employee
   */
  private String createLinked(String adminToken, String signInName, String employee)
      throws Exception {
    String location =
        mockMvc
            .perform(
                with(post("/api/accounts"), adminToken).content(linkedTo(signInName, employee)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getHeader("Location");
    return location.substring(location.lastIndexOf('/') + 1);
  }

  private MockHttpServletRequestBuilder signInAs(String signInName, String password) {
    return post("/api/auth/sign-in")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"signInName\":\"%s\",\"password\":\"%s\"}".formatted(signInName, password));
  }

  /**
   * @return the identifier of an account this Admin just created
   */
  private String create(String adminToken, String signInName, Role role) throws Exception {
    String location =
        mockMvc
            .perform(with(post("/api/accounts"), adminToken).content(creating(signInName, role)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getHeader("Location");
    return location.substring(location.lastIndexOf('/') + 1);
  }

  // Creating

  @Test
  void GivenAnAdmin_WhenCreatingAnAccount_ThenThatPersonCanSignInAndMustChangeThePassword()
      throws Exception {
    OrganisationId acme = anOrganisation();
    String signInName = anInventedSignInName();

    mockMvc
        .perform(
            with(post("/api/accounts"), admin(acme)).content(creating(signInName, Role.SCHEDULER)))
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"));

    mockMvc
        .perform(signInAs(signInName, A_PASSWORD))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.session.organisation").value(acme.value().toString()))
        .andExpect(jsonPath("$.session.role").value("SCHEDULER"))
        .andExpect(jsonPath("$.session.passwordChangeRequired").value(true));
  }

  /** A credential that came in must not go back out, where a proxy or a log would keep it. */
  @Test
  void GivenAnAccountCreated_WhenAnswered_ThenNeitherThePasswordNorTheNameIsEchoed()
      throws Exception {
    String signInName = anInventedSignInName();

    String answer =
        mockMvc
            .perform(
                with(post("/api/accounts"), admin(anOrganisation()))
                    .content(creating(signInName, Role.SCHEDULER)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(answer).doesNotContain(A_PASSWORD).doesNotContain(signInName);
  }

  @Test
  void GivenASignInNameAlreadyTaken_WhenCreatingAnAccount_ThenItIsAConflict() throws Exception {
    String admin = admin(anOrganisation());
    String taken = anInventedSignInName();
    create(admin, taken, Role.ADMIN);

    mockMvc
        .perform(with(post("/api/accounts"), admin).content(creating(taken, Role.SCHEDULER)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("urn:hrsched:problem:sign-in-name-taken"));
  }

  /**
   * Two organisations cannot both hold the name, because a sign-in carries a name and nothing else.
   * The refusal never says whose it is.
   */
  @Test
  void GivenANameTakenInAnotherOrganisation_WhenCreatingAnAccount_ThenItIsAConflictThatSaysNothing()
      throws Exception {
    String taken = anInventedSignInName();
    create(admin(anOrganisation()), taken, Role.SCHEDULER);

    String body =
        mockMvc
            .perform(
                with(post("/api/accounts"), admin(anOrganisation()))
                    .content(creating(taken, Role.SCHEDULER)))
            .andExpect(status().isConflict())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain(taken);
  }

  /**
   * The detail says what would satisfy the policy, in the sentence a refused change of one's own
   * password gets, because the reason member alone is not something the screen shows.
   */
  @Test
  void GivenATemporaryPasswordBelowThePolicy_WhenCreatingAnAccount_ThenTheRuleIsNamed()
      throws Exception {
    mockMvc
        .perform(
            with(post("/api/accounts"), admin(anOrganisation()))
                .content(
                    "{\"signInName\":\"%s\",\"temporaryPassword\":\"%s\",\"role\":\"SCHEDULER\"}"
                        .formatted(anInventedSignInName(), TOO_SHORT)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.reason").value("TOO_SHORT"))
        .andExpect(jsonPath("$.type").value("urn:hrsched:problem:password-not-accepted"))
        .andExpect(
            jsonPath("$.detail")
                .value(
                    "A password must be at least %d characters long."
                        .formatted(policy.minimumLength())));
  }

  /** As above, for a password over the most bytes the hasher reads, which is counted in bytes. */
  @Test
  void GivenATemporaryPasswordOverThePolicy_WhenCreatingAnAccount_ThenTheLimitIsNamed()
      throws Exception {
    String tooLong = ONE_BYTE_CHARACTER.repeat(policy.maximumBytes() + 1);

    mockMvc
        .perform(
            with(post("/api/accounts"), admin(anOrganisation()))
                .content(
                    "{\"signInName\":\"%s\",\"temporaryPassword\":\"%s\",\"role\":\"SCHEDULER\"}"
                        .formatted(anInventedSignInName(), tooLong)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.reason").value("TOO_LONG"))
        .andExpect(jsonPath("$.type").value("urn:hrsched:problem:password-not-accepted"))
        .andExpect(
            jsonPath("$.detail")
                .value(
                    "A password must be at most %d bytes long.".formatted(policy.maximumBytes())));
  }

  @Test
  void GivenAnEmployeeOfTheOrganisation_WhenLinkingAnAccountToThem_ThenItIsCreated()
      throws Exception {
    OrganisationId acme = anOrganisation();
    String ada = anEmployee(acme);

    mockMvc
        .perform(
            with(post("/api/accounts"), admin(acme)).content(linkedTo(anInventedSignInName(), ada)))
        .andExpect(status().isCreated());
  }

  /**
   * The link decides who an account is for self-service, so it may not reach into another tenant.
   */
  @Test
  void GivenAnEmployeeOfAnotherOrganisation_WhenLinkingAnAccountToThem_ThenItIsRefused()
      throws Exception {
    String theirs = anEmployee(anOrganisation());

    mockMvc
        .perform(
            with(post("/api/accounts"), admin(anOrganisation()))
                .content(linkedTo(anInventedSignInName(), theirs)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("urn:hrsched:problem:unknown-employee"));
  }

  @Test
  void GivenAnEmployeeWhoAlreadyHasAnAccount_WhenCreatingASecond_ThenItIsAConflict()
      throws Exception {
    OrganisationId acme = anOrganisation();
    String admin = admin(acme);
    String ada = anEmployee(acme);
    mockMvc
        .perform(with(post("/api/accounts"), admin).content(linkedTo(anInventedSignInName(), ada)))
        .andExpect(status().isCreated());

    mockMvc
        .perform(with(post("/api/accounts"), admin).content(linkedTo(anInventedSignInName(), ada)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("urn:hrsched:problem:employee-already-linked"));
  }

  // ADR-031: the organisation comes from the acting account, never from what the request says

  /** An organisation named in a header is not believed: the account lands in the Admin's own. */
  @Test
  void
      GivenAnAdminNamingAnotherOrganisationInAnXOrganisationHeader_WhenCreatingAnAccount_ThenItIsCreatedInTheirOwn()
          throws Exception {
    OrganisationId acme = anOrganisation();
    OrganisationId rival = anOrganisation();
    String signInName = anInventedSignInName();

    mockMvc
        .perform(
            with(post("/api/accounts"), admin(acme))
                .header("X-Organisation", rival.value().toString())
                .content(creating(signInName, Role.SCHEDULER)))
        .andExpect(status().isCreated());

    mockMvc
        .perform(signInAs(signInName, A_PASSWORD))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.session.organisation").value(acme.value().toString()));
  }

  /**
   * An organisation named in the body is not believed either: a body carrying a member its request
   * type does not have is malformed (ADR-058), so the request is refused whole and no account is
   * created in either organisation. The body cannot choose the organisation, and an attempt to is
   * refused rather than answered as a success.
   */
  @Test
  void
      GivenAnAdminNamingAnotherOrganisationInTheBody_WhenCreatingAnAccount_ThenItIsMalformedAndNoAccountExists()
          throws Exception {
    OrganisationId acme = anOrganisation();
    OrganisationId rival = anOrganisation();
    String signInName = anInventedSignInName();

    String answer =
        mockMvc
            .perform(
                with(post("/api/accounts"), admin(acme))
                    .content(
                        ("{\"signInName\":\"%s\",\"temporaryPassword\":\"%s\","
                                + "\"role\":\"SCHEDULER\",\"organisation\":\"%s\"}")
                            .formatted(signInName, A_PASSWORD, rival.value())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Malformed request"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(answer)
        .doesNotContain(signInName)
        .doesNotContain(rival.value().toString())
        .doesNotContain("\"organisation\"");
    mockMvc.perform(signInAs(signInName, A_PASSWORD)).andExpect(status().isUnauthorized());
  }

  @Test
  void GivenNoRole_WhenCreatingAnAccount_ThenItIsABadRequest() throws Exception {
    mockMvc
        .perform(
            with(post("/api/accounts"), admin(anOrganisation()))
                .content(
                    "{\"signInName\":\"%s\",\"temporaryPassword\":\"%s\"}"
                        .formatted(anInventedSignInName(), A_PASSWORD)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenAnEmployeeLinkThatIsNotAUuid_WhenCreatingAnAccount_ThenItIsABadRequest()
      throws Exception {
    mockMvc
        .perform(
            with(post("/api/accounts"), admin(anOrganisation()))
                .content(linkedTo(anInventedSignInName(), "not-a-uuid")))
        .andExpect(status().isBadRequest());
  }

  // Listing

  @Test
  void GivenAnAdmin_WhenListing_ThenOnlyTheirOwnOrganisationsAccountsAreReturned()
      throws Exception {
    OrganisationId acme = anOrganisation();
    String ours = anInventedSignInName();
    create(admin(acme), ours, Role.SCHEDULER);
    String theirs = anInventedSignInName();
    create(admin(anOrganisation()), theirs, Role.SCHEDULER);

    mockMvc
        .perform(with(get("/api/accounts"), admin(acme)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.signInName=='%s')]".formatted(ours)).exists())
        .andExpect(jsonPath("$[?(@.signInName=='%s')]".formatted(theirs)).isEmpty());
  }

  @Test
  void GivenAnAccount_WhenListed_ThenNoPasswordHashIsAnywhereInTheAnswer() throws Exception {
    OrganisationId acme = anOrganisation();
    create(admin(acme), anInventedSignInName(), Role.SCHEDULER);

    String body =
        mockMvc
            .perform(with(get("/api/accounts"), admin(acme)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain(A_PASSWORD).doesNotContain("passwordHash").doesNotContain("$2");
  }

  // Reading one

  /**
   * The Location a creation answers with is a route that serves the account it created, with every
   * member {@code docs/api.md} promises and no other.
   */
  @Test
  void GivenAnAccountCreated_WhenFollowingItsLocation_ThenTheAccountIsRead() throws Exception {
    OrganisationId acme = anOrganisation();
    String admin = admin(acme);
    String signInName = anInventedSignInName();
    Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    String location =
        mockMvc
            .perform(
                with(post("/api/accounts"), admin).content(creating(signInName, Role.SCHEDULER)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getHeader("Location");

    String body =
        mockMvc
            .perform(with(get(location), admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    Map<String, Object> account = JsonPath.read(body, "$");
    assertThat(account)
        .containsOnlyKeys(
            "id",
            "signInName",
            "role",
            "employee",
            "active",
            "passwordChangeRequired",
            "locked",
            "createdAt")
        .containsEntry("id", location.substring(location.lastIndexOf('/') + 1))
        .containsEntry("signInName", signInName)
        .containsEntry("role", "SCHEDULER")
        .containsEntry("employee", null)
        .containsEntry("active", true)
        .containsEntry("passwordChangeRequired", true)
        .containsEntry("locked", false);
    assertThat(Instant.parse((String) account.get("createdAt")))
        .isBetween(before, Instant.now().plusSeconds(1));
  }

  /** Another organisation's account is answered exactly as one that does not exist. */
  @Test
  void GivenAnotherOrganisationsAccount_WhenAnAdminReadsIt_ThenItIsNotFound() throws Exception {
    String theirs = create(admin(anOrganisation()), anInventedSignInName(), Role.SCHEDULER);

    mockMvc
        .perform(with(get("/api/accounts/" + theirs), admin(anOrganisation())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Not found"))
        .andExpect(jsonPath("$.type").value("urn:hrsched:problem:not-found"))
        .andExpect(jsonPath("$.detail").value(NOT_FOUND_DETAIL));
  }

  @Test
  void GivenAnUnknownAccount_WhenAnAdminReadsIt_ThenItIsNotFound() throws Exception {
    mockMvc
        .perform(with(get("/api/accounts/" + UUID.randomUUID()), admin(anOrganisation())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Not found"))
        .andExpect(jsonPath("$.type").value("urn:hrsched:problem:not-found"))
        .andExpect(jsonPath("$.detail").value(NOT_FOUND_DETAIL));
  }

  @Test
  void GivenASchedulerRole_WhenReadingAnAccount_ThenItIsForbidden() throws Exception {
    OrganisationId acme = anOrganisation();
    String account = create(admin(acme), anInventedSignInName(), Role.SCHEDULER);

    mockMvc
        .perform(
            with(
                get("/api/accounts/" + account),
                TestTenants.tokenFor(mockMvc, acme, Role.SCHEDULER)))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenNoToken_WhenReadingAnAccount_ThenItIsUnauthorised() throws Exception {
    String account = create(admin(anOrganisation()), anInventedSignInName(), Role.SCHEDULER);

    mockMvc.perform(get("/api/accounts/" + account)).andExpect(status().isUnauthorized());
  }

  @Test
  void GivenAnEmployeeRole_WhenReadingAnAccount_ThenItIsForbidden() throws Exception {
    OrganisationId acme = anOrganisation();
    String account = create(admin(acme), anInventedSignInName(), Role.SCHEDULER);

    mockMvc
        .perform(
            with(
                get("/api/accounts/" + account),
                TestTenants.tokenFor(mockMvc, acme, Role.EMPLOYEE)))
        .andExpect(status().isForbidden());
  }

  /** As listing and creating are: the operator is confined to the register (ADR-030). */
  @Test
  void GivenTheOperator_WhenReadingAnAccount_ThenItIsForbidden() throws Exception {
    String account = create(admin(anOrganisation()), anInventedSignInName(), Role.SCHEDULER);

    mockMvc
        .perform(with(get("/api/accounts/" + account), TestTenants.operatorToken(mockMvc)))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenAnIdentifierThatIsNotAUuid_WhenReadingAnAccount_ThenItIsABadRequest() throws Exception {
    mockMvc
        .perform(with(get("/api/accounts/not-a-uuid"), admin(anOrganisation())))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenAnAccount_WhenReadAlone_ThenNoPasswordHashIsInTheAnswer() throws Exception {
    OrganisationId acme = anOrganisation();
    String admin = admin(acme);
    String account = create(admin, anInventedSignInName(), Role.SCHEDULER);

    String body =
        mockMvc
            .perform(with(get("/api/accounts/" + account), admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain(A_PASSWORD).doesNotContain("passwordHash").doesNotContain("$2");
  }

  // Who may reach these routes at all

  @Test
  void GivenNoToken_WhenListingAccounts_ThenItIsUnauthorised() throws Exception {
    mockMvc
        .perform(get("/api/accounts"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.type").value("urn:hrsched:problem:unauthenticated"));
  }

  @Test
  void GivenASchedulerRole_WhenListingAccounts_ThenItIsForbidden() throws Exception {
    String scheduler = TestTenants.tokenFor(mockMvc, anOrganisation(), Role.SCHEDULER);

    mockMvc.perform(with(get("/api/accounts"), scheduler)).andExpect(status().isForbidden());
  }

  @Test
  void GivenAnEmployeeRole_WhenListingAccounts_ThenItIsForbidden() throws Exception {
    String employee = TestTenants.tokenFor(mockMvc, anOrganisation(), Role.EMPLOYEE);

    mockMvc.perform(with(get("/api/accounts"), employee)).andExpect(status().isForbidden());
  }

  /** The operator administers the register of tenants, never the people inside one (ADR-030). */
  @Test
  void GivenTheOperator_WhenListingAccounts_ThenItIsForbidden() throws Exception {
    mockMvc
        .perform(with(get("/api/accounts"), TestTenants.operatorToken(mockMvc)))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenTheOperator_WhenCreatingAnAccount_ThenItIsForbidden() throws Exception {
    mockMvc
        .perform(
            with(post("/api/accounts"), TestTenants.operatorToken(mockMvc))
                .content(creating(anInventedSignInName(), Role.ADMIN)))
        .andExpect(status().isForbidden());
  }

  /** Claiming a role in a header does not add one: the token decides. */
  @Test
  void GivenAnEmployeeTokenAndAnXRoleAdminHeader_WhenListingAccounts_ThenItIsForbidden()
      throws Exception {
    String employee = TestTenants.tokenFor(mockMvc, anOrganisation(), Role.EMPLOYEE);

    mockMvc
        .perform(with(get("/api/accounts"), employee).header("X-Role", "ADMIN"))
        .andExpect(status().isForbidden());
  }

  // An Employee account is always linked to an employee (R34)

  @Test
  void GivenAnEmployeeAccountLinkedToNobody_WhenCreated_ThenItIsUnprocessableAndNothingIsCreated()
      throws Exception {
    OrganisationId acme = anOrganisation();
    String signInName = anInventedSignInName();

    mockMvc
        .perform(
            with(post("/api/accounts"), admin(acme)).content(creating(signInName, Role.EMPLOYEE)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.title").value("Employee account not linked"))
        .andExpect(jsonPath("$.type").value("urn:hrsched:problem:employee-account-not-linked"))
        .andExpect(jsonPath("$.detail").value(UNLINKED_EMPLOYEE_REFUSED));

    mockMvc.perform(signInAs(signInName, A_PASSWORD)).andExpect(status().isUnauthorized());
  }

  @Test
  void GivenAnEmployeeAccount_WhenItsLinkIsTakenAway_ThenItIsUnprocessable() throws Exception {
    OrganisationId acme = anOrganisation();
    String admin = admin(acme);
    String account = createLinked(admin, anInventedSignInName(), anEmployee(acme));

    mockMvc
        .perform(
            with(put("/api/accounts/%s/membership".formatted(account)), admin)
                .content("{\"role\":\"EMPLOYEE\"}"))
        .andExpect(status().isUnprocessableEntity());
  }

  /**
   * The whole way back for an account V9 disabled: Restore is refused while it is linked to nobody,
   * linking it leaves it disabled, and restoring it then lets its holder sign in again.
   */
  @Test
  void GivenAnEmployeeAccountLinkedToNobody_WhenLinkedThenRestored_ThenItSignsInAgain()
      throws Exception {
    OrganisationId acme = anOrganisation();
    String admin = admin(acme);
    String signInName = anInventedSignInName();
    String account = migrated(acme, signInName);

    mockMvc
        .perform(
            with(put("/api/accounts/%s/activation".formatted(account)), admin)
                .content("{\"active\":true}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value(UNLINKED_EMPLOYEE_REFUSED));

    mockMvc
        .perform(
            with(put("/api/accounts/%s/membership".formatted(account)), admin)
                .content("{\"role\":\"EMPLOYEE\",\"employee\":\"%s\"}".formatted(anEmployee(acme))))
        .andExpect(status().isNoContent());
    // Linking is not restoring.
    mockMvc.perform(signInAs(signInName, A_PASSWORD)).andExpect(status().isUnauthorized());

    mockMvc
        .perform(
            with(put("/api/accounts/%s/activation".formatted(account)), admin)
                .content("{\"active\":true}"))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(signInAs(signInName, A_PASSWORD))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.session.role").value("EMPLOYEE"));
  }

  // Changing what an account is

  @Test
  void GivenAnAccount_WhenItsRoleIsChanged_ThenItSignsInWithTheNewRole() throws Exception {
    OrganisationId acme = anOrganisation();
    String admin = admin(acme);
    String signInName = anInventedSignInName();
    String account = create(admin, signInName, Role.SCHEDULER);

    mockMvc
        .perform(
            with(put("/api/accounts/%s/membership".formatted(account)), admin)
                .content("{\"role\":\"ADMIN\"}"))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(signInAs(signInName, A_PASSWORD))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.session.role").value("ADMIN"));
  }

  /**
   * The role is read from the account on every request, never from the token: a demotion takes away
   * what the role allowed on the next request, without the demoted person signing in again. The
   * token is used once before the demotion, so a role remembered from that first use would still
   * let it in.
   */
  @Test
  void GivenAnAdminHoldingAToken_WhenDemotedToScheduler_ThenThatTokenIsRefusedTheAccountList()
      throws Exception {
    OrganisationId acme = anOrganisation();
    String signInName = anInventedSignInName();
    String demoted = aMemberWhoChoseTheirOwnPassword(acme, Role.ADMIN, signInName);
    String theirToken = tokenFromSignIn(signInName, A_PASSWORD);
    mockMvc.perform(with(get("/api/accounts"), theirToken)).andExpect(status().isOk());

    mockMvc
        .perform(
            with(put("/api/accounts/%s/membership".formatted(demoted)), admin(acme))
                .content("{\"role\":\"SCHEDULER\"}"))
        .andExpect(status().isNoContent());

    mockMvc.perform(with(get("/api/accounts"), theirToken)).andExpect(status().isForbidden());
  }

  /** The mirror of the demotion: what a new role allows, a token already held is allowed. */
  @Test
  void GivenASchedulerHoldingAToken_WhenPromotedToAdmin_ThenThatTokenIsGrantedTheAccountList()
      throws Exception {
    OrganisationId acme = anOrganisation();
    String signInName = anInventedSignInName();
    String promoted = aMemberWhoChoseTheirOwnPassword(acme, Role.SCHEDULER, signInName);
    String theirToken = tokenFromSignIn(signInName, A_PASSWORD);
    mockMvc.perform(with(get("/api/accounts"), theirToken)).andExpect(status().isForbidden());

    mockMvc
        .perform(
            with(put("/api/accounts/%s/membership".formatted(promoted)), admin(acme))
                .content("{\"role\":\"ADMIN\"}"))
        .andExpect(status().isNoContent());

    mockMvc.perform(with(get("/api/accounts"), theirToken)).andExpect(status().isOk());
  }

  /** There is no way back from an organisation with no Admin: nobody outside it may act inside. */
  @Test
  void GivenTheLastActiveAdmin_WhenDemoted_ThenItIsAConflict() throws Exception {
    String admin = theOnlyAdminOf(anInventedSignInName());
    String onlyAdmin = whoAmI(admin);

    mockMvc
        .perform(
            with(put("/api/accounts/%s/membership".formatted(onlyAdmin)), admin)
                .content("{\"role\":\"SCHEDULER\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("urn:hrsched:problem:last-active-admin"));
  }

  @Test
  void GivenASecondAdmin_WhenTheFirstIsDemoted_ThenItIsAllowed() throws Exception {
    String admin = theOnlyAdminOf(anInventedSignInName());
    create(admin, anInventedSignInName(), Role.ADMIN);

    mockMvc
        .perform(
            with(put("/api/accounts/%s/membership".formatted(whoAmI(admin))), admin)
                .content("{\"role\":\"SCHEDULER\"}"))
        .andExpect(status().isNoContent());
  }

  /** A 404 rather than a 403: saying otherwise would confirm that account exists. */
  @Test
  void GivenAnAccountOfAnotherOrganisation_WhenChangingIt_ThenItIsNotFound() throws Exception {
    String theirName = anInventedSignInName();
    String theirs = create(admin(anOrganisation()), theirName, Role.SCHEDULER);

    mockMvc
        .perform(
            with(put("/api/accounts/%s/membership".formatted(theirs)), admin(anOrganisation()))
                .content("{\"role\":\"ADMIN\"}"))
        .andExpect(status().isNotFound());

    mockMvc
        .perform(signInAs(theirName, A_PASSWORD))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.session.role").value("SCHEDULER"));
  }

  @Test
  void GivenNoSuchAccount_WhenChangingIt_ThenItIsNotFound() throws Exception {
    mockMvc
        .perform(
            with(
                    put("/api/accounts/%s/membership".formatted(UUID.randomUUID())),
                    admin(anOrganisation()))
                .content("{\"role\":\"ADMIN\"}"))
        .andExpect(status().isNotFound());
  }

  /**
   * The whole of a customer's first day, in one test: onboarded, signed in on a password somebody
   * else chose, made to replace it, and only then able to administer anybody.
   */
  @Test
  void GivenAnOnboardedOrganisation_WhenItsFirstAdminChangesTheirPassword_ThenTheyCanAdminister()
      throws Exception {
    String signInName = anInventedSignInName();
    onboardAdministeredBy(signInName);

    // A pending password change leaves an account holding no role at all, so not even its own
    // organisation's account list is reachable yet.
    mockMvc
        .perform(with(get("/api/accounts"), tokenFromSignIn(signInName, TestTenants.password())))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(with(get("/api/accounts"), afterChoosingTheirOwnPassword(signInName)))
        .andExpect(status().isOk());
  }

  /**
   * R16 on the routes every member uses, which a separate rule guards from account administration.
   * The Actor behind a pending password change still carries its role, and only the authority the
   * URL rule reads is withheld, so this rule is the one thing between whoever knows the password an
   * Admin chose and acting as that Scheduler. Listing qualifications is open to every member, so
   * the same request answers once the Scheduler has chosen their own password.
   */
  @Test
  void
      GivenASchedulerOnThePasswordAnAdminChose_WhenCallingATenantRoute_ThenItIsForbiddenUntilTheyChooseTheirOwn()
          throws Exception {
    String signInName = anInventedSignInName();
    create(admin(anOrganisation()), signInName, Role.SCHEDULER);
    String pending = tokenFromSignIn(signInName, A_PASSWORD);

    mockMvc.perform(with(get("/api/qualifications"), pending)).andExpect(status().isForbidden());

    String changed =
        mockMvc
            .perform(
                with(post("/api/auth/password"), pending)
                    .content(
                        "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}"
                            .formatted(A_PASSWORD, THEIR_OWN_PASSWORD)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    mockMvc
        .perform(with(get("/api/qualifications"), JsonPath.read(changed, "$.accessToken")))
        .andExpect(status().isOk());
  }

  // Disabling, restoring and resetting

  /** A token already issued would otherwise keep working until it expired. */
  @Test
  void GivenAnAccountHoldingASession_WhenDisabled_ThenItsTokenStopsWorking() throws Exception {
    OrganisationId acme = anOrganisation();
    String admin = admin(acme);
    String signInName = anInventedSignInName();
    String account = create(admin, signInName, Role.SCHEDULER);
    String theirToken = tokenFromSignIn(signInName, A_PASSWORD);

    mockMvc.perform(with(get("/api/auth/session"), theirToken)).andExpect(status().isOk());

    mockMvc
        .perform(
            with(put("/api/accounts/%s/activation".formatted(account)), admin)
                .content("{\"active\":false}"))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(with(get("/api/auth/session"), theirToken))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void GivenADisabledAccount_WhenSigningIn_ThenItIsRefused() throws Exception {
    OrganisationId acme = anOrganisation();
    String admin = admin(acme);
    String signInName = anInventedSignInName();
    String account = create(admin, signInName, Role.SCHEDULER);

    mockMvc
        .perform(
            with(put("/api/accounts/%s/activation".formatted(account)), admin)
                .content("{\"active\":false}"))
        .andExpect(status().isNoContent());

    mockMvc.perform(signInAs(signInName, A_PASSWORD)).andExpect(status().isUnauthorized());
  }

  @Test
  void GivenADisabledAccount_WhenRestored_ThenItCanSignInAgain() throws Exception {
    OrganisationId acme = anOrganisation();
    String admin = admin(acme);
    String signInName = anInventedSignInName();
    String account = create(admin, signInName, Role.SCHEDULER);
    String activation = "/api/accounts/%s/activation".formatted(account);

    mockMvc
        .perform(with(put(activation), admin).content("{\"active\":false}"))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(with(put(activation), admin).content("{\"active\":true}"))
        .andExpect(status().isNoContent());

    mockMvc.perform(signInAs(signInName, A_PASSWORD)).andExpect(status().isOk());
  }

  /**
   * A 404 rather than a 403, and nothing reached the account: the session it already holds goes on
   * working, and it still signs in. Ending sessions is keyed by account alone, so what keeps a
   * rival's Admin from signing anybody out by identifier is the use case checking tenancy first,
   * and the refusal rolling back the one transaction the change runs in. {@code
   * AccountAdministrationTest} pins the order on its own; this test fails once both give way, as
   * when the sessions are ended before the check and outside that transaction.
   */
  @Test
  void GivenAnAccountOfAnotherOrganisation_WhenDisablingIt_ThenItIsNotFoundAndItsSessionGoesOn()
      throws Exception {
    String theirName = anInventedSignInName();
    String theirs = create(admin(anOrganisation()), theirName, Role.SCHEDULER);
    String theirToken = tokenFromSignIn(theirName, A_PASSWORD);

    mockMvc
        .perform(
            with(put("/api/accounts/%s/activation".formatted(theirs)), admin(anOrganisation()))
                .content("{\"active\":false}"))
        .andExpect(status().isNotFound());

    mockMvc.perform(with(get("/api/auth/session"), theirToken)).andExpect(status().isOk());
    mockMvc.perform(signInAs(theirName, A_PASSWORD)).andExpect(status().isOk());
  }

  @Test
  void GivenTheLastActiveAdmin_WhenDisabled_ThenItIsAConflict() throws Exception {
    String admin = theOnlyAdminOf(anInventedSignInName());

    mockMvc
        .perform(
            with(put("/api/accounts/%s/activation".formatted(whoAmI(admin))), admin)
                .content("{\"active\":false}"))
        .andExpect(status().isConflict());
  }

  @Test
  void GivenAnAccount_WhenItsPasswordIsReset_ThenOnlyTheNewOneSignsIn() throws Exception {
    OrganisationId acme = anOrganisation();
    String admin = admin(acme);
    String signInName = anInventedSignInName();
    String account = create(admin, signInName, Role.SCHEDULER);
    String replacement = "another invented temporary password";

    mockMvc
        .perform(
            with(post("/api/accounts/%s/password".formatted(account)), admin)
                .content("{\"temporaryPassword\":\"%s\"}".formatted(replacement)))
        .andExpect(status().isNoContent());

    mockMvc.perform(signInAs(signInName, A_PASSWORD)).andExpect(status().isUnauthorized());
    mockMvc
        .perform(signInAs(signInName, replacement))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.session.passwordChangeRequired").value(true));
  }

  @Test
  void GivenAnAccountHoldingASession_WhenItsPasswordIsReset_ThenItsTokenStopsWorking()
      throws Exception {
    OrganisationId acme = anOrganisation();
    String admin = admin(acme);
    String signInName = anInventedSignInName();
    String account = create(admin, signInName, Role.SCHEDULER);
    String theirToken = tokenFromSignIn(signInName, A_PASSWORD);

    mockMvc
        .perform(
            with(post("/api/accounts/%s/password".formatted(account)), admin)
                .content("{\"temporaryPassword\":\"%s\"}".formatted("yet another invented one")))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(with(get("/api/auth/session"), theirToken))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void GivenAResetBelowThePolicy_WhenResetting_ThenTheOldPasswordStillWorks() throws Exception {
    OrganisationId acme = anOrganisation();
    String admin = admin(acme);
    String signInName = anInventedSignInName();
    String account = create(admin, signInName, Role.SCHEDULER);

    mockMvc
        .perform(
            with(post("/api/accounts/%s/password".formatted(account)), admin)
                .content("{\"temporaryPassword\":\"%s\"}".formatted(TOO_SHORT)))
        .andExpect(status().isUnprocessableEntity());

    mockMvc.perform(signInAs(signInName, A_PASSWORD)).andExpect(status().isOk());
  }

  @Test
  void GivenAnAccountOfAnotherOrganisation_WhenResettingItsPassword_ThenItIsNotFound()
      throws Exception {
    String theirName = anInventedSignInName();
    String theirs = create(admin(anOrganisation()), theirName, Role.SCHEDULER);
    String replacement = "an invented password the rival would choose";

    mockMvc
        .perform(
            with(post("/api/accounts/%s/password".formatted(theirs)), admin(anOrganisation()))
                .content("{\"temporaryPassword\":\"%s\"}".formatted(replacement)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("No such resource."))
        .andExpect(jsonPath("$.type").value("urn:hrsched:problem:not-found"));

    mockMvc.perform(signInAs(theirName, A_PASSWORD)).andExpect(status().isOk());
    mockMvc.perform(signInAs(theirName, replacement)).andExpect(status().isUnauthorized());
  }

  /** Onboards an organisation whose first — and for now only — Admin signs in under this name. */
  private void onboardAdministeredBy(String firstAdmin) throws Exception {
    mockMvc
        .perform(
            with(post("/api/organisations"), TestTenants.operatorToken(mockMvc))
                .content(
                    TestTenants.onboarding("Acme %s".formatted(UUID.randomUUID()), firstAdmin)))
        .andExpect(status().isCreated());
  }

  /**
   * Signs in on the password somebody else chose and replaces it, which is what every account
   * created here has to do before it holds a role at all (ADR-029).
   *
   * @return a bearer token for that account, now able to act
   */
  private String afterChoosingTheirOwnPassword(String signInName) throws Exception {
    String pending = tokenFromSignIn(signInName, TestTenants.password());
    String body =
        mockMvc
            .perform(
                with(post("/api/auth/password"), pending)
                    .content(
                        "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}"
                            .formatted(TestTenants.password(), THEIR_OWN_PASSWORD)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.accessToken");
  }

  /**
   * An organisation whose only Admin is the one onboarding created.
   *
   * <p>Needed because {@code TestTenants} hands out a <em>fresh</em> Admin, and an organisation
   * with two of them cannot show the last-Admin rule at all.
   *
   * @return a bearer token for that Admin
   */
  private String theOnlyAdminOf(String signInName) throws Exception {
    onboardAdministeredBy(signInName);
    return afterChoosingTheirOwnPassword(signInName);
  }

  /**
   * The one field a caller could once omit and still be acted on. A body without it used to read as
   * {@code false} and disable somebody, which is not an outcome anybody would attribute to a typo.
   */
  @Test
  void GivenNoActiveField_WhenChangingActivation_ThenItIsABadRequestAndNobodyIsDisabled()
      throws Exception {
    OrganisationId acme = anOrganisation();
    String admin = admin(acme);
    String signInName = anInventedSignInName();
    String account = create(admin, signInName, Role.SCHEDULER);

    mockMvc
        .perform(with(put("/api/accounts/%s/activation".formatted(account)), admin).content("{}"))
        .andExpect(status().isBadRequest());

    mockMvc.perform(signInAs(signInName, A_PASSWORD)).andExpect(status().isOk());
  }

  /**
   * A single mistyped identifier is a mistake; a hundred of them is somebody looking for accounts
   * that are not theirs. The 404 is deliberately indistinguishable from "that one is somebody
   * else's", which is exactly what makes enumeration the way to find out — so it is counted, the
   * way a cross-tenant attempt is.
   */
  @Test
  void GivenAnAccountIdentifierNobodyHolds_WhenNamed_ThenTheRefusalIsCounted() throws Exception {
    String admin = admin(anOrganisation());
    double before = refusalsOfKind("unknown_account");

    mockMvc
        .perform(
            with(put("/api/accounts/%s/membership".formatted(UUID.randomUUID())), admin)
                .content("{\"role\":\"ADMIN\"}"))
        .andExpect(status().isNotFound());

    assertThat(refusalsOfKind("unknown_account")).isEqualTo(before + 1);
  }

  private double refusalsOfKind(String kind) {
    Counter counter =
        mockMvc
            .getDispatcherServlet()
            .getWebApplicationContext()
            .getBean(MeterRegistry.class)
            .find("hrsched.access.denied")
            .tag("kind", kind)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  /** The other door is open to them, and it asks for the password they already know. */
  @Test
  void GivenAnAdmin_WhenResettingTheirOwnPassword_ThenItIsAConflict() throws Exception {
    String admin = admin(anOrganisation());

    mockMvc
        .perform(
            with(post("/api/accounts/%s/password".formatted(whoAmI(admin))), admin)
                .content("{\"temporaryPassword\":\"%s\"}".formatted(A_PASSWORD)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("urn:hrsched:problem:not-your-own-password"));
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

  private String tokenFromSignIn(String signInName, String password) throws Exception {
    String body =
        mockMvc
            .perform(signInAs(signInName, password))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.accessToken");
  }
}
