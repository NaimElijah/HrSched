package io.github.naimelijah.hrsched.workforce.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import java.util.Objects;

/**
 * Something an employee can hold and a shift can require, defined per organisation.
 *
 * <p>No levels and no expiry: a qualification is either held or not. Both were considered and
 * deliberately left out until a requirement asks for them — expiry in particular would make a
 * roster's validity change with the passage of time rather than with the data, which is a
 * materially harder thing to reason about.
 */
public final class Qualification {

  private final QualificationId id;
  private final OrganisationId organisation;
  private final String name;

  public Qualification(QualificationId id, OrganisationId organisation, String name) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.organisation = Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(name, "name must not be null");
    if (name.isBlank()) {
      throw new InvalidQualificationNameException();
    }
    this.name = name;
  }

  public QualificationId id() {
    return id;
  }

  public OrganisationId organisation() {
    return organisation;
  }

  public String name() {
    return name;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Qualification qualification && id.equals(qualification.id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }

  @Override
  public String toString() {
    return "Qualification[id=%s, name=%s]".formatted(id, name);
  }
}
