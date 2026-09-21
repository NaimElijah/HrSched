package io.github.naimelijah.hrsched.shared.testsupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.access.application.port.out.AccountRepository;
import io.github.naimelijah.hrsched.access.application.port.out.PasswordHasher;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.Membership;
import io.github.naimelijah.hrsched.access.domain.model.OperatorMembership;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.access.domain.model.TenantMembership;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

/**
 * Accounts to act as, and the tokens that prove it, for the HTTP tests.
 *
 * <p>Accounts are arranged <strong>through the repository</strong> and signed in <strong>through
 * the real endpoint</strong>, and the split is deliberate. Creating an account is somebody else's
 * use case with its own tests; driving it here would make every scheduling test depend on account
 * administration, so a change there would fail tests about rosters. Signing in is the opposite: a
 * token that did not come out of the real sign-in would prove nothing about whether signing in
 * works, and every test using it would be testing this fixture instead.
 *
 * <p>Accounts are <strong>ensured on every call</strong> rather than created once. {@code
 * PostgresDatabase.empty()} truncates every table, so an adapter test running between two HTTP test
 * classes takes the accounts with it; a fixture that cached would hand out a token for a row that
 * no longer exists.
 *
 * <p>Nothing here ever submits a wrong password, so nothing here can trip the lockout and fail an
 * unrelated test.
 *
 * <p>A token is only valid in the Spring context that issued it, because the signing key is
 * generated per context. Every method therefore takes the {@code MockMvc} of the test asking, and
 * reads its beans out of that same context.
 *
 * <p>Every name and password below is invented for the tests.
 */
public final class TestTenants {

  /** The platform operator these tests sign in as. Invented, and local to the suite. */
  private static final String OPERATOR_NAME = "suite-operator@example.com";

  /**
   * One password for every fixture account. Long enough to satisfy the policy and meaningless
   * otherwise; nothing in the suite depends on what it says.
   */
  private static final String PASSWORD = "an invented password for the test suite";

  private TestTenants() {}

  /**
   * @param mockMvc the test's own client, whose context issues the token
   * @return a bearer token for the platform operator
   */
  public static String operatorToken(MockMvc mockMvc) throws Exception {
    ensure(mockMvc, OPERATOR_NAME, new OperatorMembership());
    return signIn(mockMvc, OPERATOR_NAME);
  }

  /**
   * @param mockMvc the test's own client
   * @param organisation the organisation the account belongs to
   * @param role what it may do there
   * @return a bearer token for a fresh account in that organisation and role
   */
  public static String tokenFor(MockMvc mockMvc, OrganisationId organisation, Role role)
      throws Exception {
    return tenantToken(mockMvc, new TenantMembership(organisation, role, Optional.empty()));
  }

  /**
   * @param mockMvc the test's own client
   * @param organisation the organisation the account belongs to
   * @param employee the personnel record the account is
   * @return a bearer token for a fresh EMPLOYEE account linked to that employee
   */
  public static String tokenForEmployee(
      MockMvc mockMvc, OrganisationId organisation, EmployeeId employee) throws Exception {
    return tenantToken(
        mockMvc, new TenantMembership(organisation, Role.EMPLOYEE, Optional.of(employee)));
  }

  /**
   * @param request the request being built
   * @param token the bearer token to present
   * @return the request, carrying that token and a JSON content type
   */
  public static MockHttpServletRequestBuilder with(
      MockHttpServletRequestBuilder request, String token) {
    return request
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON);
  }

  /**
   * A fresh account every time. Sign-in names are unique platform-wide, so reusing one across tests
   * would have two tests sharing an account's password and its lockout counter.
   */
  private static String tenantToken(MockMvc mockMvc, Membership membership) throws Exception {
    String name = "suite-%s@example.com".formatted(UUID.randomUUID());
    ensure(mockMvc, name, membership);
    return signIn(mockMvc, name);
  }

  /** Stores the account only if its name is free, so calling this again changes nothing. */
  private static void ensure(MockMvc mockMvc, String signInName, Membership membership) {
    WebApplicationContext context = beans(mockMvc);
    context
        .getBean(AccountRepository.class)
        .saveIfAbsent(
            new Account(
                UserId.newId(),
                new SignInName(signInName),
                context.getBean(PasswordHasher.class).hash(PASSWORD),
                membership,
                false,
                Instant.now()));
  }

  private static String signIn(MockMvc mockMvc, String signInName) throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/auth/sign-in")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"signInName\":\"%s\",\"password\":\"%s\"}"
                            .formatted(signInName, PASSWORD)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.accessToken");
  }

  private static WebApplicationContext beans(MockMvc mockMvc) {
    return mockMvc.getDispatcherServlet().getWebApplicationContext();
  }
}
