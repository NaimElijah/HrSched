package io.github.naimelijah.hrsched.workforce.application.service;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.workforce.application.port.in.DefineQualificationCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.ManageQualificationsUseCase;
import io.github.naimelijah.hrsched.workforce.application.port.in.PublishedQualification;
import io.github.naimelijah.hrsched.workforce.application.port.out.AuditTrail;
import io.github.naimelijah.hrsched.workforce.application.port.out.AuditedChange;
import io.github.naimelijah.hrsched.workforce.application.port.out.QualificationRepository;
import io.github.naimelijah.hrsched.workforce.application.port.out.WorkforceChange;
import io.github.naimelijah.hrsched.workforce.domain.model.Qualification;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Orchestrates the qualification use cases. */
public class QualificationService implements ManageQualificationsUseCase {

  private static final Set<Role> MAY_DEFINE = Set.of(Role.ADMIN);

  private final QualificationRepository qualifications;
  private final AuditTrail audit;

  /**
   * @param qualifications where qualifications are stored
   * @param audit where each definition is recorded
   * @throws NullPointerException if any argument is {@code null}
   */
  public QualificationService(QualificationRepository qualifications, AuditTrail audit) {
    this.qualifications = Objects.requireNonNull(qualifications, "qualifications must not be null");
    this.audit = Objects.requireNonNull(audit, "audit must not be null");
  }

  @Override
  public QualificationId defineQualification(Actor actor, DefineQualificationCommand command) {
    actor.requireAccessTo(command.organisation()).requireRoleIn(MAY_DEFINE);
    Qualification qualification =
        new Qualification(QualificationId.newId(), command.organisation(), command.name());
    qualifications.save(qualification);
    audit.record(
        new AuditedChange(
            command.organisation(),
            actor.id(),
            WorkforceChange.QUALIFICATION_DEFINED,
            qualification.id().value()));
    return qualification.id();
  }

  @Override
  public List<PublishedQualification> findQualifications(Actor actor, OrganisationId organisation) {
    actor.requireAccessTo(organisation);
    return qualifications.findByOrganisation(organisation).stream()
        .map(QualificationService::publish)
        .toList();
  }

  @Override
  public Optional<PublishedQualification> findQualification(
      Actor actor, OrganisationId organisation, QualificationId qualification) {
    actor.requireAccessTo(organisation);
    return qualifications.findById(organisation, qualification).map(QualificationService::publish);
  }

  private static PublishedQualification publish(Qualification qualification) {
    return new PublishedQualification(qualification.id(), qualification.name());
  }
}
