package io.github.naimelijah.hrsched.scheduling.application.port.out;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Someone scheduling could put on a shift, described in scheduling terms.
 *
 * <p>An anti-corruption layer in one record. Scheduling states what it needs to know about a person
 * — can they work then, and do they hold the right tickets — without adopting the shape workforce
 * happens to use. If workforce reshapes its aggregate, one adapter changes and nothing inward of
 * this port notices.
 *
 * @param id who they are
 * @param name their name, for display
 * @param qualifications what they hold
 * @param unavailability spans they cannot work
 */
public record RosterCandidate(
    EmployeeId id,
    String name,
    Set<QualificationId> qualifications,
    List<TimeRange> unavailability) {

  public RosterCandidate {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(name, "name must not be null");
    qualifications = Set.copyOf(qualifications);
    unavailability = List.copyOf(unavailability);
  }

  /**
   * @param period a span of work
   * @param required the qualifications it demands
   * @return whether this person could cover it at all
   */
  public boolean couldCover(TimeRange period, Set<QualificationId> required) {
    return lacking(required).isEmpty() && !isUnavailableDuring(period);
  }

  /**
   * R4, asked so that a refusal can say which qualifications were missing.
   *
   * @param required the qualifications a shift demands
   * @return those of them this person does not hold, in the order given; empty if they hold all
   */
  public Set<QualificationId> lacking(Set<QualificationId> required) {
    Set<QualificationId> missing = new LinkedHashSet<>(required);
    missing.removeAll(qualifications);
    return Collections.unmodifiableSet(missing);
  }

  /**
   * R18: whether the span runs through any time this person declared they cannot work.
   *
   * @param period a span of work
   * @return whether any declared unavailability overlaps it; touching at an end is not overlapping
   */
  public boolean isUnavailableDuring(TimeRange period) {
    return unavailability.stream().anyMatch(period::overlaps);
  }
}
