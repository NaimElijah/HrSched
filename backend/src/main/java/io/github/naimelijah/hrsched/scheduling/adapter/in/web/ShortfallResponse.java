package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.scheduling.domain.model.Shortfall;
import java.util.UUID;

/**
 * A shift a draft puts fewer people on than it requires.
 *
 * @param shift the shift
 * @param required how many people it needs
 * @param assigned how many the draft puts on it
 * @param shortBy how many more it needs, given so a client does no arithmetic of its own
 */
public record ShortfallResponse(UUID shift, int required, int assigned, int shortBy) {

  static ShortfallResponse from(Shortfall shortfall) {
    return new ShortfallResponse(
        shortfall.shift().value(), shortfall.required(), shortfall.assigned(), shortfall.shortBy());
  }
}
