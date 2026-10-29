package io.github.naimelijah.hrsched.architecture.fixtures.actors;

import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;

/** Breaks the account rule by asking an account for its actor outside the access use cases. */
public final class AsksAnAccountForItsActor {

  /** The call the rule must reject. */
  public Actor actAs(Account account) {
    return account.actor();
  }
}
