package io.github.naimelijah.hrsched.architecture.fixtures.payroll.application.port.out;

import java.util.List;

/**
 * Breaks the tenancy rule from a directory rather than a repository: a driven port reading another
 * context's people by name alone, which reads them in every organisation (ADR-009).
 */
public interface UnscopedPayeeDirectory {

  /** The read the rule must reject. */
  List<String> payeesNamed(String name);
}
