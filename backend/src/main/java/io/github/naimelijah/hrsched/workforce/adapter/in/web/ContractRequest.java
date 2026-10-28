package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import java.time.Duration;

/**
 * Contracted hours as whole minutes, so the wire format carries no duration parsing.
 *
 * @param weeklyContractedMinutes contracted minutes per week
 */
public record ContractRequest(long weeklyContractedMinutes) {

  /**
   * Whether the hours are positive is the domain's rule and is left to it; only a count no {@code
   * Duration} can hold is refused here.
   *
   * @return the contracted minutes as a duration
   * @throws MalformedRequestException if the count overflows a {@code Duration}; the message does
   *     not repeat it, as contracted hours say things about a household
   */
  Duration weeklyContractedHours() {
    try {
      return Duration.ofMinutes(weeklyContractedMinutes);
    } catch (ArithmeticException overflow) {
      throw new MalformedRequestException(
          "weeklyContractedMinutes is beyond what a duration can hold", overflow);
    }
  }
}
