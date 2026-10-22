package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.scheduling.application.port.in.GenerationState;
import io.github.naimelijah.hrsched.scheduling.domain.model.GenerationStatus;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import java.util.List;
import java.util.UUID;

/**
 * How a generation stands (ADR-046), for a client waiting on it.
 *
 * @param id which generation
 * @param status {@code PENDING} until it ends; then {@code SUCCEEDED}, {@code REFUSED} or {@code
 *     FAILED}; or {@code LOST} if the instance solving it stopped
 * @param roster the draft, once it succeeded; null until then
 * @param violations the rules the work it would carry over breaks, when it was refused; empty
 *     otherwise, in the shape publication lists them in
 */
public record GenerationResponse(
    UUID id, GenerationStatus status, UUID roster, List<ViolationResponse> violations) {

  static GenerationResponse from(GenerationState state) {
    return new GenerationResponse(
        state.id().value(),
        state.status(),
        state.roster().map(RosterId::value).orElse(null),
        state.violations().stream().map(ViolationResponse::from).toList());
  }
}
