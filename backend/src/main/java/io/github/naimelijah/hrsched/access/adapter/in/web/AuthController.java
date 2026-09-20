package io.github.naimelijah.hrsched.access.adapter.in.web;

import io.github.naimelijah.hrsched.access.application.port.in.IssuedSession;
import io.github.naimelijah.hrsched.access.application.port.in.RefreshOutcome;
import io.github.naimelijah.hrsched.access.application.port.in.RefreshRefusal;
import io.github.naimelijah.hrsched.access.application.port.in.SessionsUseCase;
import io.github.naimelijah.hrsched.access.application.port.in.SignInCommand;
import io.github.naimelijah.hrsched.access.application.port.in.SignInOutcome;
import io.github.naimelijah.hrsched.access.application.port.in.SignInUseCase;
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
import org.springframework.web.bind.annotation.CookieValue;
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
  private final MeterRegistry meters;
  private final Clock clock;

  public AuthController(
      SignInUseCase signIn, SessionsUseCase sessions, MeterRegistry meters, Clock clock) {
    this.signIn = Objects.requireNonNull(signIn, "signIn must not be null");
    this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
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
