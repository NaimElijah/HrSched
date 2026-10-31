package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import io.github.naimelijah.hrsched.workforce.application.port.in.PublishedQualification;
import java.util.UUID;

/**
 * What a caller is told about a qualification an organisation defines.
 *
 * @param id the qualification identity
 * @param name what it is called
 */
public record QualificationResponse(UUID id, String name) {

  static QualificationResponse from(PublishedQualification qualification) {
    return new QualificationResponse(qualification.id().value(), qualification.name());
  }
}
