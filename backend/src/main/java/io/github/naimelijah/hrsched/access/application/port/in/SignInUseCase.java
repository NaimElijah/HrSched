package io.github.naimelijah.hrsched.access.application.port.in;

/**
 * Driving port: exchanging a name and a password for a session.
 *
 * <p>The only way to turn a name and a password into a session. Everything else that acts as
 * somebody needs a token, and this is where the first one comes from; renewing a session takes its
 * refresh token instead ({@link SessionsUseCase}).
 */
public interface SignInUseCase {

  /**
   * Checks the submitted credentials and, if they are right, begins a session.
   *
   * <p>Returns its refusal rather than throwing it, so that a failed attempt stays counted; see
   * {@link SignInOutcome}.
   *
   * @param command the name and password submitted, never {@code null}
   * @return the session, or the refusal and why
   */
  SignInOutcome signIn(SignInCommand command);
}
