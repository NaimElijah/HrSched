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
 */
public final class Shift {

  private final ShiftId id;
  private final OrganisationId organisation;
  private final TimeRange period;
  private final ZoneId zone;
  private final int requiredHeadcount;
  private final Set<QualificationId> requiredQualifications;

  /**
   * @param id the shift identity, never {@code null}
   * @param organisation whose shift this is, never {@code null}
   * @param period when it runs, never {@code null}
   * @param zone where its working-time days are counted (R36), never {@code null}
   * @param requiredHeadcount how many people it needs, at least one
   * @param requiredQualifications what every assigned person must hold, possibly empty
   * @throws InvalidHeadcountException if fewer than one person is required
   */
  public Shift(
      ShiftId id,
      OrganisationId organisation,
      TimeRange period,
      ZoneId zone,
      int requiredHeadcount,
      Set<QualificationId> requiredQualifications) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.organisation = Objects.requireNonNull(organisation, "organisation must not be null");
    this.period = Objects.requireNonNull(period, "period must not be null");
    this.zone = Objects.requireNonNull(zone, "zone must not be null");
    if (requiredHeadcount < 1) {
      throw new InvalidHeadcountException(requiredHeadcount);
    }
    this.requiredHeadcount = requiredHeadcount;
    this.requiredQualifications = Set.copyOf(requiredQualifications);
  }

  public ShiftId id() {
    return id;
  }

  public OrganisationId organisation() {
    return organisation;
  }

  public TimeRange period() {
    return period;
  }

  public ZoneId zone() {
    return zone;
  }

  /**
   * @return when this shift runs, with the zone its working-time days are counted in
   */
  public WorkingPeriod workingPeriod() {
    return new WorkingPeriod(period, zone);
  }

  public int requiredHeadcount() {
    return requiredHeadcount;
  }

  public Set<QualificationId> requiredQualifications() {
    return requiredQualifications;
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
