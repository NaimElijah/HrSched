package io.github.naimelijah.hrsched.scheduling.application.port.out;

import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import java.util.List;

/**
 * Driven port: deciding who covers what.
 *
 * <p>Automatic generation is the point of this product, and its implementation is a constraint
 * solver: CP-SAT (ADR-044). It is a port rather than a class so that the solver — a heavyweight
 * dependency with a modelling discipline of its own — stays outside the hexagon. The greedy
 * generator that proved the boundary first is kept in the tests, as the baseline the solver is
 * measured against.
 */
public interface RosterGenerator {

  /**
   * Who to add to the draft. Only additions: what the draft already carries stays, so no generator
   * can take anybody off it.
   *
   * @param request the draft, the shifts it may assign, who may be assigned, and the rules
   * @return assignments to add, covering as much as it can: each names one of the request's shifts
   *     and one of its candidates, none is already on the draft, and the draft with them added
   *     satisfies every constraint
   */
  List<Assignment> generate(GenerationRequest request);
}
