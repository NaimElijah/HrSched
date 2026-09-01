package io.github.naimelijah.hrsched.scheduling.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.util.List;

/**
 * Driving port: an employee reading the work they are rostered for (requirement F5).
 *
 * <p>Deliberately a narrower capability beside {@code PlanRostersUseCase}, not a widening of it.
 * Reading a whole roster stays restricted to Admins and Schedulers (R12) because a roster says who
 * works when about every colleague; this answers only for the person asking, so it discloses
 * nothing about anybody else.
 *
 * <p>This is the answer to Q18. "Their own" means the assignments naming them, and only from
 * published rosters — a draft binds nobody and may never be published, so showing one would invite
 * somebody to plan their week around work they have not been given.
 */
public interface ViewOwnScheduleUseCase {

  /**
   * @param actor whoever is asking; answered only for the personnel record they are
   * @param organisation whose rosters
   * @param within the span to look inside; required, because there is no honest default window
   * @return the work they are rostered for in that span, earliest first
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.NoEmployeeRecordException if the
   *     actor has no personnel record and therefore no schedule of their own
   * @throws io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException if they
   *     name an organisation that is not theirs
   */
  List<RosteredShift> findOwnSchedule(Actor actor, OrganisationId organisation, TimeRange within);
}
