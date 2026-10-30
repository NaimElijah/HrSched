package io.github.naimelijah.hrsched.architecture.fixtures.actors;

import io.github.naimelijah.hrsched.shared.domain.model.Operator;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;

/** Breaks the actor rule by calling the {@code Operator} constructor outside the account. */
public final class BuildsAnOperator {

  /** The call the rule must reject. */
  public Operator actAsTheOperator() {
    return new Operator(UserId.newId());
  }
}
