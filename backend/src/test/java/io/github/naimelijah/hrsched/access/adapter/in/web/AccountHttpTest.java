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
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import io.github.naimelijah.hrsched.shared.testsupport.TestTenants;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
 * same rule because it holds no role at all (ADR-030). An Admin naming an account that is not
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

  /** Invented, and what an account replaces the password somebody else chose for it with. */
  private static final String THEIR_OWN_PASSWORD = "a password that admin chose themselves";

  @Autowired private MockMvc mockMvc;

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
                    .content(creating(signInName, Role.EMPLOYEE)))
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
    create(admin, taken, Role.EMPLOYEE);

    mockMvc
        .perform(with(post("/api/accounts"), admin).content(creating(taken, Role.SCHEDULER)))
        .andExpect(status().isConflict());
  }

  /**
   * Two organisations cannot both hold the name, because a sign-in carries a name and nothing else.
   * The refusal never says whose it is.
   */
  @Test
  void GivenANameTakenInAnotherOrganisation_WhenCreatingAnAccount_ThenItIsAConflictThatSaysNothing()
      throws Exception {
    String taken = anInventedSignInName();
    create(admin(anOrganisation()), taken, Role.EMPLOYEE);

    String body =
        mockMvc
            .perform(
                with(post("/api/accounts"), admin(anOrganisation()))
                    .content(creating(taken, Role.EMPLOYEE)))
            .andExpect(status().isConflict())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain(taken);
  }

  @Test
  void GivenATemporaryPasswordBelowThePolicy_WhenCreatingAnAccount_ThenTheRuleIsNamed()
      throws Exception {
    mockMvc
        .perform(
            with(post("/api/accounts"), admin(anOrganisation()))
                .content(
                    "{\"signInName\":\"%s\",\"temporaryPassword\":\"%s\",\"role\":\"EMPLOYEE\"}"
                        .formatted(anInventedSignInName(), TOO_SHORT)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.reason").value("TOO_SHORT"));
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
        .andExpect(status().isUnprocessableEntity());
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
        .andExpect(status().isConflict());
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
    create(admin(acme), anInventedSignInName(), Role.EMPLOYEE);

    String body =
        mockMvc
            .perform(with(get("/api/accounts"), admin(acme)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain(A_PASSWORD).doesNotContain("passwordHash").doesNotContain("$2");
  }

  // Who may reach these routes at all

  @Test
  void GivenNoToken_WhenListingAccounts_ThenItIsUnauthorised() throws Exception {
    mockMvc.perform(get("/api/accounts")).andExpect(status().isUnauthorized());
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

  // Changing what an account is

  @Test
  void GivenAnAccount_WhenItsRoleIsChanged_ThenItSignsInWithTheNewRole() throws Exception {
    OrganisationId acme = anOrganisation();
    String admin = admin(acme);
    String signInName = anInventedSignInName();
    String account = create(admin, signInName, Role.EMPLOYEE);

    mockMvc
        .perform(
            with(put("/api/accounts/%s/membership".formatted(account)), admin)
                .content("{\"role\":\"SCHEDULER\"}"))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(signInAs(signInName, A_PASSWORD))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.session.role").value("SCHEDULER"));
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
        .andExpect(status().isConflict());
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
    String theirs = create(admin(anOrganisation()), anInventedSignInName(), Role.EMPLOYEE);

    mockMvc
        .perform(
            with(put("/api/accounts/%s/membership".formatted(theirs)), admin(anOrganisation()))
                .content("{\"role\":\"ADMIN\"}"))
        .andExpect(status().isNotFound());
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
    String theirs = create(admin(anOrganisation()), anInventedSignInName(), Role.EMPLOYEE);

    mockMvc
        .perform(
            with(post("/api/accounts/%s/password".formatted(theirs)), admin(anOrganisation()))
                .content("{\"temporaryPassword\":\"%s\"}".formatted(A_PASSWORD)))
        .andExpect(status().isNotFound());
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
  void GivenAnAccountThatIsNotTheirs_WhenNamed_ThenTheRefusalIsCounted() throws Exception {
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
        .andExpect(status().isConflict());
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
