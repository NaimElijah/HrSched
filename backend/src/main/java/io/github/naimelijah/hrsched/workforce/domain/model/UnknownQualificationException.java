package io.github.naimelijah.hrsched.workforce.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;

/** Raised when no qualification with the given identity exists in the organisation. */
public class UnknownQualificationException extends RuntimeException {

  public UnknownQualificationException(QualificationId id) {
    super("No such qualification: %s".formatted(id.value()));
  }
}
