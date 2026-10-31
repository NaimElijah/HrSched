package io.github.naimelijah.hrsched.workforce.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What another context is told about someone who can be rostered.
 *
 * <p>This is workforce's published language, and deliberately not its {@code Employee} aggregate.
 * Built only from shared-kernel types so that a consumer never needs to see inside this context —
 * which is also what the architecture rules enforce.
 *
 * <p>Everything here is what a roster generator needs to decide eligibility, and to weigh one legal
 * roster against another: what each person said about when they would like to work (ADR-010). It
 * also carries their contracted hours, which scheduling does not read yet: #103 bounds a roster by
 * them (ADR-008). Nothing else.
 *
 * @param id who they are
 * @param name their name, for display
 * @param qualifications what they hold
 * @param unavailability spans they cannot work
 * @param weeklyContractedHours their contracted hours, absent for casual staff
 * @param wouldLikeToWork spans they stated they would like to work
 * @param wouldRatherNotWork spans they stated they would rather not work, but can
 */
public record RosterableEmployee(
    EmployeeId id,
    String name,
    Set<QualificationId> qualifications,
    List<TimeRange> unavailability,
    Optional<Duration> weeklyContractedHours,
    List<TimeRange> wouldLikeToWork,
    List<TimeRange> wouldRatherNotWork) {

  /**
   * Copies the collections, so the record cannot be changed through the ones passed in.
   *
   * @throws NullPointerException if any component is {@code null}, or a collection holds a {@code
   *     null}
   */
  public RosterableEmployee {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(name, "name must not be null");
    qualifications = Set.copyOf(qualifications);
    unavailability = List.copyOf(unavailability);
    Objects.requireNonNull(weeklyContractedHours, "weeklyContractedHours must not be null");
    wouldLikeToWork = List.copyOf(wouldLikeToWork);
    wouldRatherNotWork = List.copyOf(wouldRatherNotWork);
  }
}
