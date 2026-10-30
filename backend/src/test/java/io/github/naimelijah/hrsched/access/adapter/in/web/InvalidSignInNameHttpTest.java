package io.github.naimelijah.hrsched.access.adapter.in.web;

import static io.github.naimelijah.hrsched.shared.testsupport.TestTenants.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.access.application.port.out.AccountRepository;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import io.github.naimelijah.hrsched.shared.testsupport.TestTenants;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A sign-in name that cannot be one, submitted where an account is created.
 *
 * <p>A blank name, one longer than {@code SignInName} accepts, or one carrying a NUL or a lone
 * surrogate the database could not store as sent, is the caller's mistake: a 400 problem detail,
 * the answer every other malformed field here gets, and never a 500. A 500 says the server failed
 * and pages whoever watches it, for a request nobody should be woken for.
 *
 * <p>Both routes that create an account from a submitted name are driven: an Admin creating one in
 * their organisation, and the operator onboarding an organisation with its first Admin. Onboarding
 * is one transaction, so a refused name must leave no organisation behind either.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 *
 * <p>Every password and name here is invented for the test.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class InvalidSignInNameHttpTest {

  /** Invented, and above the configured minimum. */
  private static final String A_PASSWORD = "an invented temporary password";

  /** Invented: spaces only, which normalises to nothing. */
  private static final String BLANK = "   ";

  /**
   * Invented: one character past the 254 {@code SignInName} accepts, the longest an email address
   * can be.
   */
  private static final String TOO_LONG = "a".repeat(255);

  /**
   * Invented, and carrying a NUL as JSON escapes one: PostgreSQL's {@code text} cannot store it, so
   * a name that reached the database with it answered 500.
   */
  private static final String CARRYING_A_NUL = "ada-carrying-a-nul@example.com\\u0000";

  /**
   * Invented, and carrying a lone surrogate as JSON escapes one: it has no UTF-8 form, so a name
   * that reached the database with it was stored with a {@code ?} in its place.
   */
  private static final String CARRYING_A_SURROGATE = "ada-carrying-a-surrogate@example.com\\ud800";

  @Autowired private MockMvc mockMvc;
  @Autowired private AccountRepository accounts;

  @Test
  void GivenABlankSignInName_WhenAnAdminCreatesAnAccount_ThenItIsABadRequestProblem()
      throws Exception {
    MockHttpServletResponse response = create(admin(anOrganisation()), BLANK);

    assertBadRequestProblem(response);
  }

  @Test
  void GivenABlankSignInName_WhenAnAdminCreatesAnAccount_ThenNoAccountIsStored() throws Exception {
    OrganisationId organisation = anOrganisation();
    String admin = admin(organisation);
    int before = accounts.findAll(organisation).size();

    create(admin, BLANK);

    assertThat(accounts.findAll(organisation)).hasSize(before);
  }

  @Test
  void GivenAnOverLongSignInName_WhenAnAdminCreatesAnAccount_ThenItIsABadRequestProblem()
      throws Exception {
    MockHttpServletResponse response = create(admin(anOrganisation()), TOO_LONG);

    assertBadRequestProblem(response);
  }

  @Test
  void GivenABlankFirstAdminSignInName_WhenTheOperatorOnboards_ThenItIsABadRequestProblem()
      throws Exception {
    MockHttpServletResponse response = onboard(aName(), BLANK);

    assertBadRequestProblem(response);
  }

  @Test
  void GivenABlankFirstAdminSignInName_WhenTheOperatorOnboards_ThenNoOrganisationIsStored()
      throws Exception {
    String refused = aName();

    onboard(refused, BLANK);

    assertThatNoOrganisationIsCalled(refused);
  }

  @Test
  void GivenAnOverLongFirstAdminSignInName_WhenTheOperatorOnboards_ThenItIsABadRequestProblem()
      throws Exception {
    MockHttpServletResponse response = onboard(aName(), TOO_LONG);

    assertBadRequestProblem(response);
  }

  @Test
  void GivenASignInNameCarryingANul_WhenAnAdminCreatesAnAccount_ThenItIsABadRequestProblem()
      throws Exception {
    MockHttpServletResponse response = create(admin(anOrganisation()), CARRYING_A_NUL);

    assertBadRequestProblem(response);
  }

  @Test
  void GivenAFirstAdminSignInNameCarryingANul_WhenTheOperatorOnboards_ThenItIsABadRequestProblem()
      throws Exception {
    MockHttpServletResponse response = onboard(aName(), CARRYING_A_NUL);

    assertBadRequestProblem(response);
  }

  @Test
  void
      GivenASignInNameCarryingALoneSurrogate_WhenAnAdminCreatesAnAccount_ThenItIsABadRequestProblem()
          throws Exception {
    MockHttpServletResponse response = create(admin(anOrganisation()), CARRYING_A_SURROGATE);

    assertBadRequestProblem(response);
  }

  @Test
  void
      GivenAFirstAdminSignInNameCarryingALoneSurrogate_WhenTheOperatorOnboards_ThenItIsABadRequestProblem()
          throws Exception {
    MockHttpServletResponse response = onboard(aName(), CARRYING_A_SURROGATE);

    assertBadRequestProblem(response);
  }

  private static void assertBadRequestProblem(MockHttpServletResponse response) {
    assertThat(response.getStatus()).describedAs("the caller's mistake, not ours").isEqualTo(400);
    assertThat(MediaType.parseMediaType(response.getContentType()))
        .describedAs("a problem detail")
        .matches(type -> type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
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

  private MockHttpServletResponse onboard(String name, String firstAdmin) throws Exception {
    return mockMvc
        .perform(
            with(post("/api/organisations"), TestTenants.operatorToken(mockMvc))
                .content(TestTenants.onboarding(name, firstAdmin)))
        .andReturn()
        .getResponse();
  }

  private OrganisationId anOrganisation() throws Exception {
    MockHttpServletResponse response =
        onboard(aName(), "first-admin-%s@example.com".formatted(UUID.randomUUID()));
    assertThat(response.getStatus()).describedAs("precondition: onboarded").isEqualTo(201);
    return new OrganisationId(
        UUID.fromString(JsonPath.read(response.getContentAsString(), "$.id")));
  }

  private String admin(OrganisationId organisation) throws Exception {
    return TestTenants.tokenFor(mockMvc, organisation, Role.ADMIN);
  }

  private void assertThatNoOrganisationIsCalled(String name) throws Exception {
    mockMvc
        .perform(with(get("/api/organisations"), TestTenants.operatorToken(mockMvc)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.name=='%s')]".formatted(name)).isEmpty());
  }

  private static String aName() {
    return "Acme %s".formatted(UUID.randomUUID());
  }
}
