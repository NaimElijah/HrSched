package io.github.naimelijah.hrsched.access.adapter.in.web;

import io.github.naimelijah.hrsched.access.application.port.in.IssuedSession;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Driving adapter exposing sign-in over HTTP.
 *
 * <p>The refusal is the interesting half. Every reason a sign-in can fail - a name nobody holds, a
 * wrong password, a disabled account, a locked one - answers with the same status, the same body
 * and the same headers. A caller who could tell them apart could enumerate who has an account here
 * and learn whether guessing at one had already been noticed.
 *
 * <p>Which reason it actually was is logged and counted, because the person who needs to know is
 * whoever operates this rather than whoever is trying to get in. Neither the submitted name nor the
 * password is ever logged: the account's identifier is, when there is one.
 *
 * <p>The access token is in the body and the refresh token is in a cookie the browser cannot read,
 * which is the whole shape of ADR-028. {@code Cache-Control: no-store} keeps either out of a shared
 * cache.
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

  private static final String REFUSED_TITLE = "Sign-in refused";
  private static final String REFUSED_DETAIL = "Those credentials were not accepted.";

  private final SignInUseCase signIn;
  private final MeterRegistry meters;
  private final Clock clock;

  public AuthController(SignInUseCase signIn, MeterRegistry meters, Clock clock) {
    this.signIn = Objects.requireNonNull(signIn, "signIn must not be null");
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
      case SignInOutcome.Refused refused -> refused(refused);
    };
  }

  private ResponseEntity<SignInResponse> accepted(IssuedSession session) {
    ResponseCookie cookie =
        RefreshCookie.issuing(
            session.refreshToken(), clock.instant(), session.refreshTokenExpiresAt());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(SignInResponse.of(session, clock.instant()));
  }

  private ResponseEntity<ProblemDetail> refused(SignInOutcome.Refused refusal) {
    log.atWarn()
        .addKeyValue("account", refusal.account().map(account -> account.value()).orElse(null))
        .addKeyValue("reason", refusal.reason())
        .log("Refused: a sign-in was not accepted");
    Counter.builder(DENIED)
        .description("Refused attempts, by what refused them")
        .tag("kind", "sign_in")
        .register(meters)
        .increment();
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, REFUSED_DETAIL);
    problem.setTitle(REFUSED_TITLE);
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .header(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE)
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(problem);
  }
}
