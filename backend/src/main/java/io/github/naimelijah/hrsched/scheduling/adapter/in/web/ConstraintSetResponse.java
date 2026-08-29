package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.scheduling.domain.model.WorkingTimeConstraint;
import java.util.List;

/**
 * @param version which revision of the rules this is
 * @param constraints the rules by name, so a caller can see what is enforced
 */
public record ConstraintSetResponse(int version, List<String> constraints) {

  static ConstraintSetResponse from(ConstraintSet constraintSet) {
    return new ConstraintSetResponse(
        constraintSet.version(),
        constraintSet.constraints().stream().map(WorkingTimeConstraint::name).toList());
  }
}
