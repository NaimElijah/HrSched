package io.github.naimelijah.hrsched.workforce.application.service;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.workforce.application.port.in.DefineQualificationCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.ManageQualificationsUseCase;
import io.github.naimelijah.hrsched.workforce.application.port.in.PublishedQualification;
import io.github.naimelijah.hrsched.workforce.application.port.out.QualificationRepository;
import io.github.naimelijah.hrsched.workforce.domain.model.Qualification;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Orchestrates the qualification use cases. */
public class QualificationService implements ManageQualificationsUseCase {

  private static final Set<Role> MAY_DEFINE = Set.of(Role.ADMIN);

  private final QualificationRepository qualifications;

  public QualificationService(QualificationRepository qualifications) {
    this.qualifications = Objects.requireNonNull(qualifications, "qualifications must not be null");
  }

  @Override
  public QualificationId defineQualification(Actor actor, DefineQualificationCommand command) {
    actor.requireAccessTo(command.organisation());
    if (actor instanceof TenantActor tenant) {
      tenant.requireRoleIn(MAY_DEFINE);
    }
    Qualification qualification =
        new Qualification(QualificationId.newId(), command.organisation(), command.name());
    qualifications.save(qualification);
    return qualification.id();
  }

  @Override
  public List<PublishedQualification> findQualifications(Actor actor, OrganisationId organisation) {
    actor.requireAccessTo(organisation);
    return qualifications.findByOrganisation(organisation).stream()
        .map(qualification -> new PublishedQualification(qualification.id(), qualification.name()))
        .toList();
  }
}
