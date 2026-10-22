package io.github.naimelijah.hrsched.scheduling.application.service;

import io.github.naimelijah.hrsched.scheduling.application.port.out.UnitsOfWork;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Hand-written, like every other double here: units of work are our own port.
 *
 * <p>Runs each unit at once, as the real adapter does, and records which units were begun and which
 * one is open, so a test can see what a use case did inside one and what it did outside any. A unit
 * opened inside another joins it, as a {@code REQUIRED} transaction joins its caller's.
 */
final class RecordingUnitsOfWork implements UnitsOfWork {

  /** Which kind of unit. */
  enum Unit {
    READING,
    WRITING
  }

  private final List<Unit> begun = new ArrayList<>();
  private final Deque<Unit> open = new ArrayDeque<>();

  @Override
  public <T> T reading(Supplier<T> work) {
    return within(Unit.READING, work);
  }

  @Override
  public <T> T writing(Supplier<T> work) {
    return within(Unit.WRITING, work);
  }

  private <T> T within(Unit unit, Supplier<T> work) {
    if (open.isEmpty()) {
      begun.add(unit);
    }
    open.push(open.isEmpty() ? unit : open.peek());
    try {
      return work.get();
    } finally {
      open.pop();
    }
  }

  /**
   * @return every unit begun, in order; one opened inside another is not a unit of its own
   */
  List<Unit> begun() {
    return List.copyOf(begun);
  }

  /**
   * @return the unit open right now, or nothing outside any
   */
  Optional<Unit> open() {
    return Optional.ofNullable(open.peek());
  }
}
