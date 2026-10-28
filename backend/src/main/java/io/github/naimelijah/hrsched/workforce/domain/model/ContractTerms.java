package io.github.naimelijah.hrsched.workforce.domain.model;

import java.time.Duration;
import java.util.Objects;

/**
 * What an employee is contracted to work.
 *
 * <p>Held separately from the organisation's constraint set on purpose: a constraint set is the
 * rules everyone is bound by, while contract terms are what varies person to person. Conflating
 * them would mean a part-time employee needed their own copy of every statutory rule.
 *
 * @param weeklyContractedHours contracted hours per week, strictly positive
 */
public record ContractTerms(Duration weeklyContractedHours) {

  public ContractTerms {
    Objects.requireNonNull(weeklyContractedHours, "weeklyContractedHours must not be null");
    if (weeklyContractedHours.isNegative() || weeklyContractedHours.isZero()) {
      throw new InvalidContractTermsException();
    }
  }
}
