package io.github.naimelijah.hrsched.workforce.application.port.out;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.workforce.domain.model.Qualification;
import java.util.List;
import java.util.Optional;

/**
 * Driven port: storing qualifications.
 *
 * <p>Scoped by organisation for the same reason as {@code EmployeeRepository}.
 */
public interface QualificationRepository {

  void save(Qualification qualification);

  /**
   * @param organisation the organisation the qualification must belong to
   * @param id which qualification to look up
   * @return the qualification, or empty if that organisation defined no such qualification
   * @throws IllegalStateException if it is stored with a name the domain calls blank (R2): a defect
   *     of the store, not of the caller's request
   */
  Optional<Qualification> findById(OrganisationId organisation, QualificationId id);

  /**
   * @param organisation whose qualifications to list
   * @return every qualification that organisation defined, ordered by name, then by identity, so
   *     two sharing a name come back in the same order every time
   * @throws IllegalStateException if any of them is stored with a name the domain calls blank, as
   *     for {@link #findById}
   */
  List<Qualification> findByOrganisation(OrganisationId organisation);
}
