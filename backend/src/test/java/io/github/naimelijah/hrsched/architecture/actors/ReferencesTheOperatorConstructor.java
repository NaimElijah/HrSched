package io.github.naimelijah.hrsched.architecture.actors;

import io.github.naimelijah.hrsched.shared.domain.model.Operator;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.util.function.Function;

/** Breaks the actor rule with a constructor reference, which is not a call. */
public final class ReferencesTheOperatorConstructor {

  /** The reference the rule must reject. */
  public Function<UserId, Operator> operatorMaker() {
    return Operator::new;
  }
}
