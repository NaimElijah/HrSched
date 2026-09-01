package io.github.naimelijah.hrsched.notifications.application.port.in;

import io.github.naimelijah.hrsched.notifications.domain.model.NotificationId;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;

/**
 * This context's published API: telling somebody that something happened to them.
 *
 * <p>The one entry point another context may use. {@code scheduling} reaches it through an adapter,
 * never from its core, which is what ADR-008 requires of any cross-context call.
 *
 * <p><strong>Not exposed over HTTP, deliberately.</strong> It is gated only by the tenant boundary
 * — an employee requesting a swap has to be able to notify the colleague they are asking, and no
 * role describes that. Behind an in-process port that is exactly right, because the only callers
 * are use cases that have already decided somebody deserves telling. Put it on a controller and it
 * becomes a way for any authenticated caller to post anything into any colleague's inbox.
 */
public interface NotifyUseCase {

  /**
   * @param actor on whose behalf the notification is raised; must belong to the organisation
   * @param command who is being told, and about what
   * @return the new notification's identity
   * @throws io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException if the
   *     actor may not touch that organisation
   */
  NotificationId notify(Actor actor, NotifyCommand command);
}
