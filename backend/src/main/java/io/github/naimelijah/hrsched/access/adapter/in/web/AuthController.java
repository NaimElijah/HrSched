package io.github.naimelijah.hrsched.access.adapter.in.web;

import io.github.naimelijah.hrsched.access.application.port.in.ChangePasswordCommand;
import io.github.naimelijah.hrsched.access.application.port.in.ChangePasswordOutcome;
import io.github.naimelijah.hrsched.access.application.port.in.ChangePasswordUseCase;
import io.github.naimelijah.hrsched.access.application.port.in.CurrentPasswordRefusal;
import io.github.naimelijah.hrsched.access.application.port.in.IssuedSession;
import io.github.naimelijah.hrsched.access.application.port.in.ReadSessionUseCase;
import io.github.naimelijah.hrsched.access.application.port.in.RefreshOutcome;
import io.github.naimelijah.hrsched.access.application.port.in.RefreshRefusal;
import io.github.naimelijah.hrsched.access.application.port.in.SessionsUseCase;
import io.github.naimelijah.hrsched.access.application.port.in.SignInCommand;
import io.github.naimelijah.hrsched.access.application.port.in.SignInOutcome;
import io.github.naimelijah.hrsched.access.application.port.in.SignInUseCase;
import io.github.naimelijah.hrsched.access.domain.model.PasswordPolicy;
import io.github.naimelijah.hrsched.access.domain.model.PasswordRejection;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Driving adapter exposing signing in, renewing a session, and signing out.
 *
 * <p>The refusal is the interesting half. Every reason a sign-in can fail - a name nobody holds, a
 * wrong password, a disabled account, a locked one - answers with the same status, the same body
 * and the same headers, and a refused renewal answers with that same 401 again. A caller who could
 * tell them apart could enumerate who has an account here, learn whether guessing at one had been
 * noticed, and find out whether a token they are holding was ever real.
 *
 * <p>Which reason it actually was is logged and counted, because the person who needs to know is
 * whoever operates this rather than whoever is trying to get in. Neither the submitted name, nor
 * the password, nor any token value is ever logged: the account or the session identifier is.
 *
 * <p>The access token is in the body and the refresh token is in a cookie the browser cannot read,
 * which is the whole shape of ADR-028. {@code Cache-Control: no-store} keeps either out of a shared
 * cache.
 *
 * <p>Renewing and signing out require the header {@code X-Requested-With}, and its
 * <em>presence</em> is the entire check - the value is never read. It works because this is not a
 * header a browser will attach to a cross-site request on its own: sending it forces a CORS
 * preflight, and this application configures no CORS at all, so that preflight is refused. Together
 * with {@code SameSite=Strict} on the cookie, it means another origin cannot cause a renewal or a
 * sign-out simply by navigating to one - which is the hazard on an endpoint whose credential is a
 * cookie the browser attaches by itself.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private static final Logger log = LoggerFactory.getLogger(AuthController.class);

  /**
   * Shared with the exception handlers, so one query answers "who was refused" across all of it.
   */
  private static final String DENIED = "hrsched.access.denied";

  /** RFC 9110 requires a 401 to carry a challenge; this is the scheme the API accepts. */
  private static final String BEARER_CHALLENGE = "Bearer";

  private static final String NO_STORE = "no-store";
  private static final String REFUSED_TITLE = "Sign-in refused";
  private static final String REFUSED_DETAIL = "Those credentials were not accepted.";

  private final SignInUseCase signIn;
  private final SessionsUseCase sessions;
  private final ReadSessionUseCase readSession;
  private final ChangePasswordUseCase changePassword;
  private final PasswordPolicy policy;
  private final MeterRegistry meters;
  private final Clock clock;

  public AuthController(
      SignInUseCase signIn,
      SessionsUseCase sessions,
      ReadSessionUseCase readSession,
      ChangePasswordUseCase changePassword,
      PasswordPolicy policy,
      MeterRegistry meters,
      Clock clock) {
    this.signIn = Objects.requireNonNull(signIn, "signIn must not be null");
    this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
    this.readSession = Objects.requireNonNull(readSession, "readSession must not be null");
    this.changePassword = Objects.requireNonNull(changePassword, "changePassword must not be null");
    this.policy = Objects.requireNonNull(policy, "policy must not be null");
    this.meters = Objects.requireNonNull(meters, "meters must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * @param request the submitted name and password
   * @return 200 with an access token and a refresh cookie, or 401 with a problem detail that says
   *     the same thing however the attempt failed
   */
  @PostMapping(path = "/sign-in", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> signIn(@RequestBody SignInRequest request) {
    SignInOutcome outcome =
        signIn.signIn(new SignInCommand(request.submittedName(), request.submittedPassword()));
    return switch (outcome) {
      case SignInOutcome.SignedIn signedIn -> accepted(signedIn.session());
      case SignInOutcome.Refused refused -> refusedSignIn(refused);
    };
  }

  /**
   * @param refreshToken the refresh cookie, absent when the browser holds none
   * @param requestedWith required for its presence alone; see this class's javadoc
   * @return 200 with a fresh access token and a rotated cookie, or 401 with the cookie cleared
   */
  @PostMapping("/refresh")
  public ResponseEntity<?> refresh(
      @CookieValue(name = RefreshCookie.NAME, required = false) String refreshToken,
      @RequestHeader("X-Requested-With") String requestedWith) {
    if (refreshToken == null || refreshToken.isBlank()) {
      return refusedRefresh(RefreshOutcome.Refused.of(RefreshRefusal.UNKNOWN_TOKEN));
    }
    return switch (sessions.refresh(refreshToken)) {
      case RefreshOutcome.Renewed renewed -> accepted(renewed.session());
      case RefreshOutcome.Refused refused -> refusedRefresh(refused);
    };
  }

  /**
   * Always 204, and always clears the cookie. Whether what was presented was ever a real token is
   * not something signing out is a place to find out.
   *
   * @param refreshToken the refresh cookie, absent when the browser holds none
   * @param requestedWith required for its presence alone; see this class's javadoc
   * @return 204, with the cookie cleared
   */
  @PostMapping("/sign-out")
  public ResponseEntity<Void> signOut(
      @CookieValue(name = RefreshCookie.NAME, required = false) String refreshToken,
      @RequestHeader("X-Requested-With") String requestedWith) {
    if (refreshToken != null && !refreshToken.isBlank()) {
      sessions.signOut(refreshToken);
    }
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, RefreshCookie.clearing().toString())
        .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
        .build();
  }

  /**
   * @param actor whoever is asking, taken from their verified token
   * @return 200 describing the caller's own account
   */
  @GetMapping("/session")
  public ResponseEntity<SessionResponse> session(@AuthenticationPrincipal Actor actor) {
    return readSession
        .findSession(actor)
        .map(SessionResponse::of)
        .map(
            session ->
                ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE).body(session))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * Changes the caller's own password, ending every session it had and beginning a new one.
   *
   * <p>A wrong current password is a 403 rather than a 401, deliberately. A 401 means "renew your
   * token and try again", which is exactly what a client would do — and it would then try the same
   * wrong password for ever.
   *
   * @param actor whoever is changing their own password
   * @param request the current and new passwords
   * @return 200 with a new session, 403 if the current password was wrong, the account is locked or
   *     it was disabled since the request was authenticated (answered alike), or 422 naming the
   *     rule a new password broke
   */
  @PostMapping(path = "/password", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> changePassword(
      @AuthenticationPrincipal Actor actor, @RequestBody ChangePasswordRequest request) {
    ChangePasswordOutcome outcome =
        changePassword.changePassword(
            actor, new ChangePasswordCommand(request.submittedCurrent(), request.submittedNew()));
    return switch (outcome) {
      case ChangePasswordOutcome.Changed changed -> accepted(changed.session());
      case ChangePasswordOutcome.CurrentPasswordRejected rejected ->
          currentPasswordRejected(actor, rejected.reason());
      case ChangePasswordOutcome.NewPasswordRejected rejected ->
          newPasswordRejected(rejected.reason());
    };
  }

  /**
   * Answers a locked or newly disabled account exactly as a wrong current password, and records
   * which it was: the caller must not learn of the lock, and an operator must, since refusals while
   * locked are somebody still guessing.
   */
  private ResponseEntity<ProblemDetail> currentPasswordRejected(
      Actor actor, CurrentPasswordRefusal reason) {
    log.atWarn()
        .addKeyValue("actor", actor.id().value())
        .addKeyValue("reason", reason)
        .log("Refused: a password change was not accepted for its current password");
    denied("current_password");
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Current password not accepted.");
    problem.setTitle("Password not changed");
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
        .body(problem);
  }

  /**
   * Names the rule that was broken, because somebody choosing a password has to be told what would
   * satisfy it. The rule is the same for everybody and could be found by trying, so saying it
   * reveals nothing. The password itself is never echoed.
   */
  private ResponseEntity<ProblemDetail> newPasswordRejected(PasswordRejection reason) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, detailFor(reason));
    problem.setTitle("Password not accepted");
    problem.setProperty("reason", reason.name());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
        .body(problem);
  }

  private String detailFor(PasswordRejection reason) {
    return switch (reason) {
      case TOO_SHORT ->
          "A password must be at least %d characters long.".formatted(policy.minimumLength());
      case TOO_LONG -> "A password must be at most %d bytes long.".formatted(policy.maximumBytes());
      case SAME_AS_CURRENT -> "The new password must be different from the current one.";
    };
  }

  private ResponseEntity<SignInResponse> accepted(IssuedSession session) {
    ResponseCookie cookie =
        RefreshCookie.issuing(
            session.refreshToken(), clock.instant(), session.refreshTokenExpiresAt());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
        .body(SignInResponse.of(session, clock.instant()));
  }

  private ResponseEntity<ProblemDetail> refusedSignIn(SignInOutcome.Refused refusal) {
    log.atWarn()
        .addKeyValue("account", refusal.account().map(account -> account.value()).orElse(null))
        .addKeyValue("reason", refusal.reason())
        .log("Refused: a sign-in was not accepted");
    denied("sign_in");
    return unauthorised().body(refusedBody());
  }

  /**
   * Answers a refused renewal exactly as a refused sign-in is answered, and takes the cookie away.
   * Whatever the browser is holding is of no further use, and leaving it there would only mean it
   * gets presented again.
   */
  private ResponseEntity<ProblemDetail> refusedRefresh(RefreshOutcome.Refused refusal) {
    log.atWarn()
        .addKeyValue("session", refusal.session().orElse(null))
        .addKeyValue("reason", refusal.reason())
        .log("Refused: a session was not renewed");
    denied("refresh");
    return unauthorised()
        .header(HttpHeaders.SET_COOKIE, RefreshCookie.clearing().toString())
        .body(refusedBody());
  }

  /** The same 401 for every refusal here, so that none of them can be told apart from another. */
  private static ResponseEntity.BodyBuilder unauthorised() {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .header(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE)
        .header(HttpHeaders.CACHE_CONTROL, NO_STORE);
  }

  private static ProblemDetail refusedBody() {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, REFUSED_DETAIL);
    problem.setTitle(REFUSED_TITLE);
    return problem;
  }

  private void denied(String kind) {
    Counter.builder(DENIED)
        .description("Refused attempts, by what refused them")
        .tag("kind", kind)
        .register(meters)
        .increment();
  }
}
