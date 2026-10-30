package io.github.naimelijah.hrsched.scheduling.application.port.out;

import io.github.naimelijah.hrsched.scheduling.domain.model.StatedPreferences;
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
 * <p>It carries no name: nothing in scheduling shows one, and a person is named by identity.
 *
 * @param id who they are
 * @param qualifications what they hold
 * @param unavailability spans they cannot work
 * @param preferences what they said about when they would like to work, which never decides whether
 *     they may (ADR-010)
 */
public record RosterCandidate(
    EmployeeId id,
    Set<QualificationId> qualifications,
    List<TimeRange> unavailability,
    StatedPreferences preferences) {

  public RosterCandidate {
    Objects.requireNonNull(id, "id must not be null");
    qualifications = Set.copyOf(qualifications);
    unavailability = List.copyOf(unavailability);
    Objects.requireNonNull(preferences, "preferences must not be null");
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

  /**
   * Names only who they are: a {@code toString} ends up in places a field does not, and absence and
   * preferences say things about a person's health and household.
   */
  @Override
  public String toString() {
    return "RosterCandidate[id=%s, qualifications=%d]".formatted(id, qualifications.size());
  }
}
