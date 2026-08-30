package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * @param id the shift identity
 * @param start when it begins
 * @param end when it ends
 * @param requiredHeadcount how many people it needs
 * @param requiredQualifications what each of them must hold
 */
public record ShiftResponse(
    UUID id, Instant start, Instant end, int requiredHeadcount, List<UUID> requiredQualifications) {

  static ShiftResponse from(Shift shift) {
    return new ShiftResponse(
        shift.id().value(),
        shift.period().start(),
        shift.period().end(),
        shift.requiredHeadcount(),
        shift.requiredQualifications().stream().map(QualificationId::value).toList());
  }
}
