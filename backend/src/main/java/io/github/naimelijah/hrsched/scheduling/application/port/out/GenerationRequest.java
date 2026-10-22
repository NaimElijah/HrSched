package io.github.naimelijah.hrsched.scheduling.application.port.out;

import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.scheduling.domain.model.HeldWork;
import io.github.naimelijah.hrsched.scheduling.domain.model.ObjectiveWeights;
import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterStatus;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Everything a {@link RosterGenerator} decides from, read before it is asked.
 *
 * <p>One record rather than a growing parameter list, because what a generator needs grows with
 * what it optimises, and every implementation and test double would otherwise change with it.
 *
 * @param draft the draft being generated: its organisation, its period, and whatever it already
 *     carries, which the generator adds to and never takes from. What it carries is the work its
 *     people already hold on these same shifts on another authoritative roster, carried over (#130)
 * @param shifts every shift overlapping the draft's period, which are the only ones it may assign
 * @param candidates who may be put on them; the directory's answer for the organisation
 * @param constraints the working-time rules the finished draft must satisfy
 * @param heldElsewhere the work these people hold on the rosters that stay authoritative beside the
 *     draft. Nobody may be put on a shift overlapping a different shift they hold there (R35); it
 *     counts toward no working-time rule, which judges one roster at a time (ADR-033)
 * @param weights how the organisation weights the objectives a roster is scored on (ADR-010)
 * @param superseded the published rosters the draft would replace, which the assignments it changes
 *     are counted against (disruption)
 * @throws IllegalArgumentException if the draft is not a draft, carries a shift not among {@code
 *     shifts}, two candidates share an identity, the weights are another organisation's, or a
 *     roster offered as superseded is not one the draft would supersede
 */
public record GenerationRequest(
    Roster draft,
    List<Shift> shifts,
    List<RosterCandidate> candidates,
    ConstraintSet constraints,
    List<HeldWork> heldElsewhere,
    ObjectiveWeights weights,
    List<Roster> superseded) {

  public GenerationRequest {
    Objects.requireNonNull(draft, "draft must not be null");
    Objects.requireNonNull(constraints, "constraints must not be null");
    shifts = List.copyOf(shifts);
    candidates = List.copyOf(candidates);
    heldElsewhere = List.copyOf(heldElsewhere);
    Objects.requireNonNull(weights, "weights must not be null");
    superseded = List.copyOf(superseded);
    if (draft.status() != RosterStatus.DRAFT) {
      throw new IllegalArgumentException(
          "Only a draft is generated, and roster %s is %s"
              .formatted(draft.id().value(), draft.status()));
    }
    Set<ShiftId> offered = shifts.stream().map(Shift::id).collect(Collectors.toSet());
    for (Assignment carried : draft.assignments()) {
      if (!offered.contains(carried.shift())) {
        throw new IllegalArgumentException(
            "The draft carries shift %s, which is not among the shifts to cover"
                .formatted(carried.shift().value()));
      }
    }
    Set<EmployeeId> seen = new HashSet<>();
    for (RosterCandidate candidate : candidates) {
      if (!seen.add(candidate.id())) {
        throw new IllegalArgumentException(
            "Candidate %s is offered twice".formatted(candidate.id().value()));
      }
    }
    if (!weights.organisation().equals(draft.organisation())) {
      throw new IllegalArgumentException("The weights are another organisation's");
    }
    for (Roster replaced : superseded) {
      if (!replaced.isSupersededBy(draft)) {
        throw new IllegalArgumentException(
            "Roster %s would not be superseded by the draft".formatted(replaced.id().value()));
      }
    }
  }
}
