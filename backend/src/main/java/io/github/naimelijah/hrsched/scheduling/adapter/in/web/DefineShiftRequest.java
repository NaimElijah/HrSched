package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * @param start when the shift begins
 * @param end when the shift ends
 * @param requiredHeadcount how many people it needs
 * @param requiredQualifications what each of them must hold, may be null for none
 */
public record DefineShiftRequest(
    Instant start, Instant end, int requiredHeadcount, List<UUID> requiredQualifications) {

  /**
   * @return the span the shift covers
   * @throws MalformedRequestException if either instant is absent, which Jackson maps to null
   *     rather than rejecting
   */
  TimeRange toPeriod() {
    if (start == null || end == null) {
      throw new MalformedRequestException("Both start and end are required");
    }
    return new TimeRange(start, end);
  }

  /**
   * @return the qualifications every person on this shift must hold, empty if none were named
   */
  Set<QualificationId> toRequiredQualifications() {
    if (requiredQualifications == null) {
      return Set.of();
    }
    if (requiredQualifications.contains(null)) {
      throw new MalformedRequestException("requiredQualifications must not contain a null entry");
    }
    return requiredQualifications.stream()
        .map(QualificationId::new)
        .collect(Collectors.toUnmodifiableSet());
  }
}
