package io.github.naimelijah.hrsched.scheduling.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import java.util.Set;

/**
 * Raised when a shift demands a qualification the organisation has never defined.
 *
 * <p>Such a shift is not merely wrong, it is quietly unfillable: nobody can hold the qualification,
 * so every generated roster is short of headcount with nothing to explain why. Refusing it at
 * definition time turns a permanent silent gap into an immediate error.
 */
public class UnknownRequiredQualificationException extends RuntimeException {

  public UnknownRequiredQualificationException(Set<QualificationId> unknown) {
    super(
        "A shift cannot require qualifications this organisation has not defined: %s"
            .formatted(unknown.stream().map(QualificationId::value).toList()));
  }
}
