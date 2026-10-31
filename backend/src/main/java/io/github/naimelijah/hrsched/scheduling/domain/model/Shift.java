package io.github.naimelijah.hrsched.scheduling.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;

/**
 * A period of work that needs covering, and what it takes to cover it.
 *
 * <p>An entity: two shifts with identical periods are still different shifts, so equality is by
 * {@link ShiftId} alone.
 *
 * <p>Demand is expressed as pre-defined shifts rather than as coverage requirements over a window.
 * Someone states that Monday 08:00 to 16:00 needs three people, one of whom must be a supervisor;
 * the generator decides who. Shift boundaries are therefore an input, never something the system
 * invents.
 *
 * <p>Whether a shift is unpopular is an input too: the Scheduler defining it says so, and nothing
 * infers it from the clock, the weekday or anybody's preferences (ADR-010). It changes no rule a
 * roster must obey; it is what fairness counts.
 */
public final class Shift {

  private final ShiftId id;
  private final OrganisationId organisation;
  private final TimeRange period;
  private final ZoneId zone;
  private final int requiredHeadcount;
  private final Set<QualificationId> requiredQualifications;
  private final boolean unpopular;

  /**
   * @param id the shift identity, never {@code null}
   * @param organisation whose shift this is, never {@code null}
   * @param period when it runs, never {@code null}
   * @param zone where its working-time days are counted (R36), never {@code null}
   * @param requiredHeadcount how many people it needs, at least one
   * @param requiredQualifications what every assigned person must hold, possibly empty
   * @param unpopular whether the Scheduler marked it as work people would rather not do, which the
   *     fairness objective spreads (ADR-010)
   * @throws InvalidHeadcountException if fewer than one person is required
   */
  public Shift(
      ShiftId id,
      OrganisationId organisation,
      TimeRange period,
      ZoneId zone,
      int requiredHeadcount,
      Set<QualificationId> requiredQualifications,
      boolean unpopular) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.organisation = Objects.requireNonNull(organisation, "organisation must not be null");
    this.period = Objects.requireNonNull(period, "period must not be null");
    this.zone = Objects.requireNonNull(zone, "zone must not be null");
    if (requiredHeadcount < 1) {
      throw new InvalidHeadcountException();
    }
    this.requiredHeadcount = requiredHeadcount;
    this.requiredQualifications = Set.copyOf(requiredQualifications);
    this.unpopular = unpopular;
  }

  /**
   * @return this shift's identity, the only thing its equality compares
   */
  public ShiftId id() {
    return id;
  }

  /**
   * @return the organisation this shift belongs to
   */
  public OrganisationId organisation() {
    return organisation;
  }

  /**
   * @return when this shift runs, as instants; {@link #zone} says which days it falls on
   */
  public TimeRange period() {
    return period;
  }

  /**
   * @return the zone in which this shift's working-time days are counted (R36)
   */
  public ZoneId zone() {
    return zone;
  }

  /**
   * @return when this shift runs, with the zone its working-time days are counted in
   */
  public WorkingPeriod workingPeriod() {
    return new WorkingPeriod(period, zone);
  }

  /**
   * @return how many people this shift needs, at least one
   */
  public int requiredHeadcount() {
    return requiredHeadcount;
  }

  /**
   * @return what every person assigned must hold; unmodifiable, and empty if none is demanded
   */
  public Set<QualificationId> requiredQualifications() {
    return requiredQualifications;
  }

  /**
   * @return whether the Scheduler marked this shift as unpopular (ADR-010)
   */
  public boolean unpopular() {
    return unpopular;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Shift shift && id.equals(shift.id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }

  @Override
  public String toString() {
    return "Shift[id=%s, period=%s, headcount=%d]".formatted(id, period, requiredHeadcount);
  }
}
