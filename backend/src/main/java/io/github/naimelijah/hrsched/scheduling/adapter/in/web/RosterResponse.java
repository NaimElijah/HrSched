package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Carries the period it covers, so a caller can resolve its assignments against the shifts that
 * actually fall in it rather than against whatever span the caller happens to be looking at.
 *
 * @param id the roster identity
 * @param start when the period it covers begins
 * @param end when that period ends
 * @param status where it is in its life
 * @param assignments who works which shift
 * @param validatedAgainstConstraintVersion which rules approved it, null while a draft
 */
public record RosterResponse(
    UUID id,
    Instant start,
    Instant end,
    String status,
    List<AssignmentResponse> assignments,
    Integer validatedAgainstConstraintVersion) {

  static RosterResponse from(Roster roster) {
    return new RosterResponse(
        roster.id().value(),
        roster.period().start(),
        roster.period().end(),
        roster.status().name(),
        roster.assignments().stream()
            .map(
                assignment ->
                    new AssignmentResponse(
                        assignment.shift().value(), assignment.employee().value()))
            .toList(),
        roster.validatedAgainstConstraintVersion());
  }
}
