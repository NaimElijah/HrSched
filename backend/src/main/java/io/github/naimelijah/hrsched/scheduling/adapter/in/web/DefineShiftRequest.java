package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * @param start when the shift begins
 * @param end when the shift ends
 * @param zone an IANA time zone name, such as {@code Europe/London}: where its days are counted
 * @param requiredHeadcount how many people it needs
 * @param requiredQualifications what each of them must hold, may be null for none
 */
public record DefineShiftRequest(
    Instant start,
    Instant end,
    String zone,
    int requiredHeadcount,
    List<UUID> requiredQualifications) {

  /**
   * The only refusal a bad zone gets. Fixed, because a detail never repeats what was sent
   * (ADR-024).
   */
  static final String ZONE_REFUSAL = "zone must name an IANA time zone, such as Europe/London";

  /**
   * Only names in the time zone database are accepted. {@link ZoneId#of} also takes fixed offsets
   * such as {@code +01:00}, which ignore daylight saving and would count a shift's days an hour
   * wrong half the year (ADR-034). There is no default: a zone the caller did not send is a zone
   * somebody else chose for them.
   *
   * @return the zone the shift's working-time days are counted in
   * @throws MalformedRequestException if the zone is absent or is not a time zone database name
   */
  ZoneId toZone() {
    if (zone == null || !ZoneId.getAvailableZoneIds().contains(zone)) {
      throw new MalformedRequestException(ZONE_REFUSAL);
    }
    return ZoneId.of(zone);
  }

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
