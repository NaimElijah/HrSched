package io.github.naimelijah.hrsched.access.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.access.application.port.out.AccountRepository;
import io.github.naimelijah.hrsched.access.application.port.out.PasswordHasher;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.LockoutPolicy;
import io.github.naimelijah.hrsched.access.domain.model.OperatorMembership;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Changing one's own password while the account is locked (R29).
 *
 * <p>A lock exists because somebody is guessing at the password. Sign-in refuses a locked account
 * whatever password it is given and counts nothing, so the guessing stops there. Password change
 * asks for the current password too, and is therefore a second door to the same guess: it must
 * refuse a locked account exactly as sign-in does, or a session somebody already holds - stolen, or
 * left open on a shared machine - becomes an unlimited oracle for the password, and a correct guess
 * through it replaces the password and lifts the lock.
 *
 * <p>The lock is reached the way an attacker reaches it, through failed sign-ins on the public
 * route, as many as the configured threshold. The session is opened first, because a lock stops a
 * sign-in and nothing else ({@code SessionHttpTest}), so the token outlives it.
 *
 * <p>Runs on the application's real clock. The configured lock lasts far longer than these requests
 * take, so the lock is still running when each assertion reads it; a lock re-armed by a counted
 * failure would end later than the one first set, which is what the tests look for.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 *
 * <p>Every password and sign-in name here is invented for the test.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class LockedAccountPasswordChangeHttpTest {

  /** Invented: the account's real password. */
  private static final String PASSWORD = "an invented password for a test";

  /** Invented: a guess that is not it. */
  private static final String WRONG_PASSWORD = "not the invented password";

  /** Invented: what the caller asks to change it to, long enough to satisfy the policy. */
  private static final String REPLACEMENT = "a different invented password";

  @Autowired private MockMvc mockMvc;
  @Autowired private AccountRepository accounts;
  @Autowired private PasswordHasher passwords;
  @Autowired private LockoutPolicy lockout;

  @Test
  void
      GivenALockedAccount_WhenChangingThePasswordWithTheRightCurrentOne_ThenTheAnswerIsTheOneAWrongCurrentPasswordGets()
          throws Exception {
    String unlockedToken = bearerFor(anOperator());
    MockHttpServletResponse wrongCurrent =
        changePassword(unlockedToken, WRONG_PASSWORD, REPLACEMENT);
    Account locked = anOperator();
    String lockedToken = bearerFor(locked);
    lockThroughSignIn(locked);

    MockHttpServletResponse whenLocked = changePassword(lockedToken, PASSWORD, REPLACEMENT);

    assertThat(whenLocked.getStatus())
        .describedAs("a locked account is refused as a wrong current password is")
        .isEqualTo(wrongCurrent.getStatus());
    assertThat(whenLocked.getContentAsString()).isEqualTo(wrongCurrent.getContentAsString());
  }

  @Test
  void
      GivenALockedAccount_WhenChangingThePasswordWithTheRightCurrentOne_ThenThePasswordAndTheLockAreUnchanged()
          throws Exception {
    Account account = anOperator();
    String token = bearerFor(account);
    Account lockedBefore = lockThroughSignIn(account);

    changePassword(token, PASSWORD, REPLACEMENT);

    Account after = stored(account);
    assertThat(after.passwordHash())
        .describedAs("a correct guess while locked must not replace the password")
        .isEqualTo(lockedBefore.passwordHash());
    assertThat(after.failedSignIns())
        .describedAs("a correct guess while locked must not clear the failure count")
        .isEqualTo(lockedBefore.failedSignIns());
    assertThat(after.lockedUntil())
        .describedAs("a correct guess while locked must not lift the lock")
        .isEqualTo(lockedBefore.lockedUntil());
  }

  @Test
  void GivenALockedAccount_WhenChangingThePasswordWithAWrongCurrentOne_ThenItIsRefused()
      throws Exception {
    Account account = anOperator();
    String token = bearerFor(account);
    lockThroughSignIn(account);

    MockHttpServletResponse response = changePassword(token, WRONG_PASSWORD, REPLACEMENT);

    assertThat(response.getStatus())
        .describedAs("403, the answer a wrong current password gets on an unlocked account")
        .isEqualTo(403);
  }

  /**
   * Sign-in counts nothing while locked, because a lock that extended itself on every attempt would
   * let anybody keep an account shut indefinitely just by trying. Password change must not be the
   * way around that.
   */
  @Test
  void
      GivenALockedAccount_WhenChangingThePasswordWithAWrongCurrentOne_ThenNothingIsCountedAndTheLockIsNotExtended()
          throws Exception {
    Account account = anOperator();
    String token = bearerFor(account);
    Account lockedBefore = lockThroughSignIn(account);

    changePassword(token, WRONG_PASSWORD, REPLACEMENT);

    Account after = stored(account);
    assertThat(after.failedSignIns())
        .describedAs("a wrong guess while locked must not be counted")
        .isEqualTo(lockedBefore.failedSignIns());
    assertThat(after.lockedUntil())
        .describedAs("a wrong guess while locked must not re-arm the lock from now")
        .isEqualTo(lockedBefore.lockedUntil());
  }

  /**
   * Fails sign-in as many times as the configured policy needs to lock the account.
   *
   * @return the account as stored once locked
   */
  private Account lockThroughSignIn(Account account) throws Exception {
    for (int attempt = 0; attempt < lockout.threshold(); attempt++) {
      signIn(account, WRONG_PASSWORD);
    }
    Account locked = stored(account);
    assertThat(locked.isLockedAt(Instant.now()))
        .describedAs("precondition: %d failed sign-ins lock the account", lockout.threshold())
        .isTrue();
    return locked;
  }

  private Account stored(Account account) {
    return accounts.findForAuthentication(account.id()).orElseThrow();
  }

  private MockHttpServletResponse changePassword(
      String authorization, String current, String replacement) throws Exception {
    return mockMvc
        .perform(
            post("/api/auth/password")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}"
                        .formatted(current, replacement)))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse signIn(Account account, String password) throws Exception {
    return mockMvc
        .perform(
            post("/api/auth/sign-in")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"signInName\":\"%s\",\"password\":\"%s\"}"
                        .formatted(account.signInName().value(), password)))
        .andReturn()
        .getResponse();
  }

  private String bearerFor(Account account) throws Exception {
    MockHttpServletResponse signedIn = signIn(account, PASSWORD);
    assertThat(signedIn.getStatus())
        .describedAs("precondition: the account signs in")
        .isEqualTo(200);
    return "Bearer " + JsonPath.read(signedIn.getContentAsString(), "$.accessToken");
  }

  /**
   * An operator whose password need not be changed first, so the change is the only thing asked.
   */
  private Account anOperator() {
    Account account =
        new Account(
            UserId.newId(),
            new SignInName("locked-change-%s@example.com".formatted(UUID.randomUUID())),
            passwords.hash(PASSWORD),
            new OperatorMembership(),
            false,
            Instant.now());
    accounts.save(account);
    return account;
  }
}
