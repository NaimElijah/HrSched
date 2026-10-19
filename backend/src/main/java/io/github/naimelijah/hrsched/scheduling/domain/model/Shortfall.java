package io.github.naimelijah.hrsched.scheduling.domain.model;

import java.util.Objects;

/**
 * A shift a roster puts fewer people on than it requires, and by how many.
 *
 * <p>A headcount is the least a shift needs, not the most it may have (R22), so a shift with more
 * people than it requires is never short, and has no shortfall to describe.
 *
 * @param shift the shift
 * @param required how many people it needs
 * @param assigned how many the roster puts on it: at least none, and fewer than {@code required}
 */
public record Shortfall(ShiftId shift, int required, int assigned) {

  public Shortfall {
    Objects.requireNonNull(shift, "shift must not be null");
    if (assigned < 0 || assigned >= required) {
      throw new IllegalArgumentException(
          "A shortfall puts fewer people on a shift than it requires, and never fewer than none");
    }
  }

  /**
   * @return how many more people the shift needs
   */
  public int shortBy() {
    return required - assigned;
  }
}
