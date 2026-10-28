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
 * @param unpopular whether the Scheduler marks it as work people would rather not do (ADR-010);
 *     absent means it is not marked, which is what every shift defined before the mark existed is
 */
public record DefineShiftRequest(
    Instant start,
    Instant end,
    String zone,
    int requiredHeadcount,
    List<UUID> requiredQualifications,
    Boolean unpopular) {

  /**
   * The only refusal a bad zone gets. Fixed, because a detail never repeats what was sent
   * (ADR-024).
   */
  static final String ZONE_REFUSAL = "zone must name an IANA time zone, such as Europe/London";

  /**
   * Only ids the JVM's time zone database knows are accepted, which refuses a raw offset such as
   * {@code +01:00} that {@link ZoneId#of} would take: somebody sending one almost always means a
   * place, and an offset ignores that place's daylight saving. The database's own fixed-offset
   * zones, {@code Etc/GMT±N}, are accepted, because a browser on a machine set to a fixed offset
   * reports exactly that name, and that is the offset its typed times were read in (ADR-034). There
   * is no default: a zone the caller did not send is a zone somebody else chose for them.
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
   *     rather than rejecting, or the span is one the store cannot hold or generation cannot count
   *     (see {@link StorableSpans#toPeriod})
   */
  TimeRange toPeriod() {
    if (start == null || end == null) {
      throw new MalformedRequestException("Both start and end are required");
    }
    return StorableSpans.toPeriod(start, end);
  }

  /**
   * @return whether the shift is marked unpopular; false unless the caller said it is
   */
  boolean toUnpopular() {
    return Boolean.TRUE.equals(unpopular);
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
