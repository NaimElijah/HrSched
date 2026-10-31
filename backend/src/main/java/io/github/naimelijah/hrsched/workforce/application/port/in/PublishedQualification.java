package io.github.naimelijah.hrsched.workforce.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import java.util.Objects;

/**
 * What another context is told about a qualification: its identity and its name.
 *
 * @param id the qualification's identity
 * @param name what the organisation calls it
 */
public record PublishedQualification(QualificationId id, String name) {

  /**
   * @throws NullPointerException if either component is {@code null}
   */
  public PublishedQualification {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(name, "name must not be null");
  }
}
