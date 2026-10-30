package io.github.naimelijah.hrsched.architecture.actors;

import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import java.util.function.Function;

/** Breaks the account rule with a method reference, which is not a call. */
public final class ReferencesAccountActor {

  /** The reference the rule must reject. */
  public Function<Account, Actor> actorOf() {
    return Account::actor;
  }
}
