package io.github.naimelijah.hrsched.scheduling.domain.model;

import java.util.Objects;

/**
 * A period of work that needs covering.
 *
 * <p>An entity, not a value object: two shifts with identical periods are still different shifts,
 * so equality is by {@link ShiftId} alone. This is why it is a class and not a record — a record
 * would compare every component.
 *
 * <p>INCOMPLETE. A shift will carry the organisation it belongs to, its required headcount and
 * qualifications, and the assignments made against it, and will become the aggregate root enforcing
 * those rules. Q2, Q3 and Q4 are now answered, so that work is unblocked — see {@code
 * docs/requirements/requirements.md} — but none of it is modelled yet. Note in particular that no
 * working-time threshold belongs in this class: those are values in a configured constraint set,
 * never constants in the domain.
 */
public final class Shift {

  private final ShiftId id;
  private final ShiftPeriod period;

  /**
   * @param id the shift's identity, never {@code null}
   * @param period when the shift runs, never {@code null}
   */
  public Shift(ShiftId id, ShiftPeriod period) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.period = Objects.requireNonNull(period, "period must not be null");
  }

  public ShiftId id() {
    return id;
  }

  public ShiftPeriod period() {
    return period;
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
    return "Shift[id=%s, period=%s]".formatted(id, period);
  }
}
