package io.github.naimelijah.hrsched.notifications.application.port.in;

import io.github.naimelijah.hrsched.notifications.domain.model.NotificationId;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.List;

/**
 * Driving port: reading and acknowledging one's own notifications.
 *
 * <p>Strictly self-service. There is no role that lets anybody read a colleague's inbox, and no
 * parameter that would let them try: whose notifications are returned is decided from the personnel
 * record the actor carries (ADR-012), never from something the caller supplies.
 */
public interface ReadNotificationsUseCase {

  /**
   * @param actor whoever is asking
   * @param organisation whose notifications
   * @return their own notifications, newest first
   * @throws io.github.naimelijah.hrsched.notifications.domain.model.NotAddressableException if the
   *     actor has no personnel record and therefore no inbox
   */
  List<PublishedNotification> findMine(Actor actor, OrganisationId organisation);

  /**
   * Acknowledges one notification. Acknowledging an already-read one is not an error.
   *
   * @param actor whoever is acknowledging it
   * @param organisation whose notification
   * @param notification which one
   * @throws io.github.naimelijah.hrsched.notifications.domain.model.UnknownNotificationException if
   *     there is no such notification of theirs — including when it is somebody else's
   * @throws io.github.naimelijah.hrsched.notifications.domain.model.NotAddressableException if the
   *     actor has no personnel record
   */
  void markRead(Actor actor, OrganisationId organisation, NotificationId notification);
}
