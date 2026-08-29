package io.github.naimelijah.hrsched.scheduling.application.port.out;

import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import java.util.List;

/**
 * Driven port: deciding who covers what.
 *
 * <p>Automatic generation is the point of this product, and a real implementation is a constraint
 * solver. It is a port rather than a class so that the solver — a heavyweight dependency with a
 * modelling discipline of its own — can be introduced later without anything inward of this
 * interface changing.
 *
 * <p>The implementation shipped today is greedy and makes no attempt to optimise. It exists to
 * prove the boundary, not to produce good rosters.
 */
public interface RosterGenerator {

  /**
   * @param shifts what needs covering
   * @param candidates who could cover it
   * @param constraints the rules any answer must satisfy
   * @return assignments covering as much as it can, satisfying every constraint
   */
  List<Assignment> generate(
      List<Shift> shifts, List<RosterCandidate> candidates, ConstraintSet constraints);
}
