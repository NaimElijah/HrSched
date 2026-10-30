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

  /**
   * A character no name may carry, because PostgreSQL's {@code text} cannot hold it: the database
   * would refuse the name as a fault rather than as the caller's mistake. A technical limit of the
   * store, not a rule about names.
   *
   * <p>No length limit sits beside it, because nothing indexes the name: the one bound a store puts
   * on an indexed value, the size of an index entry, does not apply to it.
   */
  private static final char NUL = '\0';

  private final OrganisationId id;
  private final String name;

  /**
   * @param id the organisation's identity, never {@code null}
   * @param name the organisation's name, never {@code null}, never blank, and never carrying a NUL
   * @throws NullPointerException if {@code id} or {@code name} is null
   * @throws InvalidOrganisationNameException if {@code name} is blank or carries a NUL
   */
  public Organisation(OrganisationId id, String name) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(name, "name must not be null");
    if (name.isBlank() || name.indexOf(NUL) >= 0) {
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
