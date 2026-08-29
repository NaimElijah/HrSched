package io.github.naimelijah.hrsched.scheduling.application.port.out;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import java.util.List;
import java.util.Set;

/**
 * Driven port: who scheduling could roster.
 *
 * <p>Scheduling states what it needs; whichever context or service can answer conforms to this.
 * Today it is answered from the workforce context, in process. If that ever becomes a remote call,
 * only the adapter changes.
 */
public interface WorkforceDirectory {

  /**
   * @param actor whoever is asking, carried through so the answer is scoped to their access
   * @param organisation whose people to list
   * @return everyone that organisation could roster
   */
  List<RosterCandidate> candidatesFor(Actor actor, OrganisationId organisation);

  /**
   * Lets scheduling reject a shift requiring a qualification nobody could ever hold, rather than
   * accepting it and producing a roster that is silently short of headcount forever.
   *
   * @param actor whoever is asking, carried through so the answer is scoped to their access
   * @param organisation whose qualifications to list
   * @return the identity of every qualification that organisation has defined
   */
  Set<QualificationId> definedQualifications(Actor actor, OrganisationId organisation);
}
