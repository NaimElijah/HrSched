package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import java.util.List;
import java.util.UUID;

/**
 * @param id the roster identity
 * @param status where it is in its life
 * @param assignments who works which shift
 * @param validatedAgainstConstraintVersion which rules approved it, null while a draft
 */
public record RosterResponse(
    UUID id,
    String status,
    List<AssignmentResponse> assignments,
    Integer validatedAgainstConstraintVersion) {

  static RosterResponse from(Roster roster) {
    return new RosterResponse(
        roster.id().value(),
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
