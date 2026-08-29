package io.github.naimelijah.hrsched.workforce.domain.model;

/**
 * How strongly an employee feels about working a span of time.
 *
 * <p>Never blocks an assignment. A preference is an input to the generator's objectives; the thing
 * that blocks is unavailability, which is a different concept precisely so the two can never be
 * confused. An employee who cannot work must say so, not merely prefer not to.
 */
public enum PreferenceStrength {

  /** Would like to work this span. */
  PREFERS,

  /** Would rather not work this span, but can. */
  PREFERS_NOT
}
