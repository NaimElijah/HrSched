package io.github.naimelijah.hrsched.notifications.adapter.in.web;

import io.github.naimelijah.hrsched.notifications.application.port.in.ReadNotificationsUseCase;
import io.github.naimelijah.hrsched.notifications.domain.model.NotificationId;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Driving adapter exposing one's own notifications over HTTP.
 *
 * <p>Reading and acknowledging only. There is deliberately no endpoint that raises a notification:
 * {@code NotifyUseCase} is an in-process port whose callers are use cases that already decided
 * somebody deserved telling, and putting it on a controller would make it a way to post anything
 * into any colleague's inbox.
 *
 * <p>Whose notifications are returned or acknowledged comes from the acting identity, never from a
 * path or a query parameter, so no request can name somebody else's inbox. A colleague's
 * notification named in the path is simply not found in the caller's.
 *
 * <p>The use cases' refusals are translated by {@link NotificationExceptionHandler}. The security
 * chain answers 401, and the platform operator's 403, before this controller runs.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

  private final ReadNotificationsUseCase readNotifications;

  /**
   * @param readNotifications the use case behind both routes
   */
  public NotificationController(ReadNotificationsUseCase readNotifications) {
    this.readNotifications = readNotifications;
  }

  /**
   * Lists the acting employee's own notifications, newest first, and of those raised at one
   * instant, the last raised first.
   *
   * <p>Answers 200 with an empty list when nothing has happened to them. Answers 403 to an actor
   * with no personnel record, who has no inbox. The platform operator, who belongs to no
   * organisation, is refused 403 by the security chain before this runs.
   *
   * @param actor whoever the token named
   * @return their notifications, none of them anybody else's
   */
  @GetMapping
  public List<NotificationResponse> list(@AuthenticationPrincipal Actor actor) {
    return readNotifications.findMine(actor, actingIn(actor)).stream()
        .map(NotificationResponse::from)
        .toList();
  }

  /**
   * Marks one of the acting employee's own notifications as read.
   *
   * <p>Answers 204, and answers 204 again when it was already read. Answers 404 when there is no
   * such notification of theirs, which includes a colleague's: the two are not told apart. Answers
   * 403 to an actor with no personnel record, and 400 when the identifier is not a UUID. The
   * platform operator is refused 403 by the security chain before this runs.
   *
   * @param actor whoever the token named
   * @param id the notification to acknowledge
   * @return an empty 204
   */
  @PostMapping("/{id}/read")
  public ResponseEntity<Void> markRead(
      @AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    readNotifications.markRead(actor, actingIn(actor), new NotificationId(id));
    return ResponseEntity.noContent().build();
  }

  /**
   * The organisation this request acts in, taken from the acting account and from nowhere else.
   *
   * <p>Nothing in the request names an organisation, so there is nothing for a caller to change
   * (ADR-031). The security chain refuses the platform operator before this runs; this refuses it
   * again, should a route ever let it through, because it belongs to none (ADR-030).
   *
   * @param actor whoever the token named
   * @return the organisation they belong to
   */
  private static OrganisationId actingIn(Actor actor) {
    return actor.requireTenantMembership().organisation();
  }
}
