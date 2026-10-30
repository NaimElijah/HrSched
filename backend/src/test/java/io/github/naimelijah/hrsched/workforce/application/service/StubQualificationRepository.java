package io.github.naimelijah.hrsched.workforce.application.service;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.workforce.application.port.out.QualificationRepository;
import io.github.naimelijah.hrsched.workforce.domain.model.Qualification;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory stand-in for the qualification port, scoped by organisation exactly as the real one is.
 *
 * <p>Hand-written rather than mocked, like every other double here: the repository is our own port.
 * It copies {@code JdbcQualificationRepository}'s one refusal, a save over an identity another
 * organisation holds, because a double accepting what the database refuses tests nothing. Its
 * self-test is in {@code QualificationServiceTest}.
 *
 * <p>Shared between {@code EmployeeServiceTest} and {@code QualificationServiceTest} rather than
 * copied into each, so a refusal the adapter gains is copied once and reaches both.
 *
 * <p>Named without a {@code Test} suffix so Surefire does not try to run it.
 */
final class StubQualificationRepository implements QualificationRepository {

  private final Map<QualificationId, Qualification> saved = new HashMap<>();

  /**
   * @throws IllegalStateException if the identity already belongs to another organisation, as
   *     {@code JdbcQualificationRepository}'s upsert refuses it
   */
  @Override
  public void save(Qualification qualification) {
    Qualification held = saved.get(qualification.id());
    if (held != null && !held.organisation().equals(qualification.organisation())) {
      throw new IllegalStateException(
          "Refused to store %s: that identity already belongs to another organisation"
              .formatted(qualification.id().value()));
    }
    saved.put(qualification.id(), qualification);
  }

  @Override
  public Optional<Qualification> findById(OrganisationId organisation, QualificationId id) {
    return Optional.ofNullable(saved.get(id))
        .filter(qualification -> qualification.organisation().equals(organisation));
  }

  @Override
  public List<Qualification> findByOrganisation(OrganisationId organisation) {
    return saved.values().stream()
        .filter(qualification -> qualification.organisation().equals(organisation))
        .toList();
  }
}
