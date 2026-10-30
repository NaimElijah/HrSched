package io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model;

import java.io.Serializable;
import java.io.UncheckedIOException;

/** Keeps the I/O rule: {@code java.io} names that do no I/O themselves. */
public record SerializablePayslip(String reference) implements Serializable {

  /** Names an I/O failure without performing any. */
  public boolean isAboutIo(RuntimeException failure) {
    return failure instanceof UncheckedIOException;
  }
}
