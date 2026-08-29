package io.github.naimelijah.hrsched.access.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.Objects;

/**
 * A customer organisation: the unit of tenancy.
 *
 * <p>An entity — two organisations sharing a name are still different customers, so equality is by
 * {@link OrganisationId} alone.
 */
public final class Organisation {

  private final OrganisationId id;
  private final String name;

  /**
   * @param id the organisation's identity, never {@code null}
   * @param name the organisation's name, never {@code null} and never blank
   * @throws NullPointerException if {@code id} or {@code name} is null
   * @throws InvalidOrganisationNameException if {@code name} is blank
   */
  public Organisation(OrganisationId id, String name) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(name, "name must not be null");
    if (name.isBlank()) {
      throw new InvalidOrganisationNameException();
    }
    this.name = name;
  }

  public OrganisationId id() {
    return id;
  }

  public String name() {
    return name;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Organisation organisation && id.equals(organisation.id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }

  @Override
  public String toString() {
    return "Organisation[id=%s]".formatted(id);
  }
}
