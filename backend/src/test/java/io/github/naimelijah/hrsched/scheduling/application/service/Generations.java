package io.github.naimelijah.hrsched.scheduling.application.service;

import io.github.naimelijah.hrsched.scheduling.application.port.in.GenerateRosterCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.GenerationState;
import io.github.naimelijah.hrsched.scheduling.application.port.in.PlanRostersUseCase;
import io.github.naimelijah.hrsched.scheduling.domain.model.GenerationStatus;
import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;

/**
 * Generates a draft the way a Scheduler now does (ADR-046): start it, then read back how it ended.
 *
 * <p>The use case tests run generation on the calling thread, so it has ended by the time starting
 * it returns. Named without a Test suffix so Surefire does not try to run it.
 */
final class Generations {

  private Generations() {}

  /**
   * @return the draft the generation ended with
   * @throws AssertionError if it ended any other way, which a test meaning to generate a draft did
   *     not expect
   */
  static Roster draft(PlanRostersUseCase planning, Actor actor, GenerateRosterCommand command) {
    GenerationState started = planning.startGeneration(actor, command);
    GenerationState ended =
        planning.findGeneration(actor, command.organisation(), started.id()).orElseThrow();
    if (ended.status() != GenerationStatus.SUCCEEDED) {
      throw new AssertionError("The generation ended %s, not with a draft".formatted(ended));
    }
    return planning
        .findRoster(actor, command.organisation(), ended.roster().orElseThrow())
        .orElseThrow();
  }
}
