package io.github.naimelijah.hrsched.workforce.adapter.out.persistence;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.workforce.application.port.out.QualificationRepository;
import io.github.naimelijah.hrsched.workforce.domain.model.Qualification;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * Driven adapter storing qualifications in memory.
 *
 * <p>PLACEHOLDER until ADR-004, scoped by organisation on every read.
 */
@Repository
public class InMemoryQualificationRepository implements QualificationRepository {

  private final Map<QualificationId, Qualification> byId = new ConcurrentHashMap<>();

  @Override
  public void save(Qualification qualification) {
    byId.put(qualification.id(), qualification);
  }

  @Override
  public Optional<Qualification> findById(OrganisationId organisation, QualificationId id) {
    return Optional.ofNullable(byId.get(id))
        .filter(qualification -> qualification.organisation().equals(organisation));
  }

  @Override
  public List<Qualification> findByOrganisation(OrganisationId organisation) {
    return byId.values().stream()
        .filter(qualification -> qualification.organisation().equals(organisation))
        .sorted(Comparator.comparing(Qualification::name))
        .toList();
  }
}
