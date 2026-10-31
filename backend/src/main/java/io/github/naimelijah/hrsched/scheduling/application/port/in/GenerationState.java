package io.github.naimelijah.hrsched.scheduling.application.port.in;

import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintViolation;
import io.github.naimelijah.hrsched.scheduling.domain.model.GenerationId;
import io.github.naimelijah.hrsched.scheduling.domain.model.GenerationStatus;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterGeneration;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * How a generation stood at the moment it was read (ADR-046).
 *
 * @param id which generation
 * @param status as of that moment, so {@link GenerationStatus#LOST} if it was still pending when it
 *     should long since have ended
 * @param roster the draft, once it succeeded
 * @param violations every rule the work it would carry over breaks, if it was refused
 */
public record GenerationState(
    GenerationId id,
    GenerationStatus status,
    Optional<RosterId> roster,
    List<ConstraintViolation> violations) {

  public GenerationState {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(roster, "roster must not be null");
    violations = List.copyOf(violations);
  }

  /**
   * @param generation the generation as stored
   * @param now what the clock reads
   * @return how {@code generation} stands then
   */
  public static GenerationState of(RosterGeneration generation, Instant now) {
    return new GenerationState(
        generation.id(), generation.statusAt(now), generation.roster(), generation.violations());
  }
}
