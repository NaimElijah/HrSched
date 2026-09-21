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
 * <p>Reading only. There is deliberately no endpoint that raises a notification: {@code
 * NotifyUseCase} is an in-process port whose callers are use cases that already decided somebody
 * deserved telling, and putting it on a controller would make it a way to post anything into any
 * colleague's inbox.
 *
 * <p>Whose notifications are returned comes from the acting identity, never from a path or a query
 * parameter, so there is no request that even expresses "somebody else's".
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

  private final ReadNotificationsUseCase readNotifications;

  public NotificationController(ReadNotificationsUseCase readNotifications) {
    this.readNotifications = readNotifications;
  }

  @GetMapping
  public List<NotificationResponse> list(@AuthenticationPrincipal Actor actor) {
    return readNotifications.findMine(actor, actingIn(actor)).stream()
        .map(NotificationResponse::from)
        .toList();
  }

  @PostMapping("/{id}/read")
  public ResponseEntity<Void> markRead(
      @AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    readNotifications.markRead(actor, actingIn(actor), new NotificationId(id));
    return ResponseEntity.noContent().build();
  }

  /**
   * The organisation this request acts in, taken from the acting account and from nowhere else.
   *
   * <p>It replaced {@code X-Organisation}, and the difference is the whole point: there is no
   * longer anything in the request naming an organisation, so there is nothing for a caller to
   * change. The platform operator is refused here, because it belongs to none (ADR-030).
   *
   * @param actor whoever the token named
   * @return the organisation they belong to
   */
  private static OrganisationId actingIn(Actor actor) {
    return actor.requireTenantMembership().organisation();
  }
}
