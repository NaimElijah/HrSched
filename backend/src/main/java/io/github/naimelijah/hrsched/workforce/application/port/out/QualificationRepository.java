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

  Optional<Qualification> findById(OrganisationId organisation, QualificationId id);

  List<Qualification> findByOrganisation(OrganisationId organisation);
}
