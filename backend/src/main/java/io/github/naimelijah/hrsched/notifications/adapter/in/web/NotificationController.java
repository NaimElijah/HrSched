package io.github.naimelijah.hrsched.notifications.adapter.in.web;

import io.github.naimelijah.hrsched.notifications.application.port.in.ReadNotificationsUseCase;
import io.github.naimelijah.hrsched.notifications.domain.model.NotificationId;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
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
  public List<NotificationResponse> list(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    return readNotifications.findMine(actor, ActorFromHeaders.organisationOf(organisation)).stream()
        .map(NotificationResponse::from)
        .toList();
  }

  @PostMapping("/{id}/read")
  public ResponseEntity<Void> markRead(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs,
      @PathVariable UUID id) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    readNotifications.markRead(
        actor, ActorFromHeaders.organisationOf(organisation), new NotificationId(id));
    return ResponseEntity.noContent().build();
  }
}
