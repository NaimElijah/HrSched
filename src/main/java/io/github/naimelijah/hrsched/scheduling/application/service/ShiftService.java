package io.github.naimelijah.hrsched.scheduling.application.service;

import io.github.naimelijah.hrsched.scheduling.application.port.in.FindShiftUseCase;
import io.github.naimelijah.hrsched.scheduling.application.port.in.ScheduleShiftCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.ScheduleShiftUseCase;
import io.github.naimelijah.hrsched.scheduling.application.port.out.ShiftRepository;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftPeriod;
import java.util.Objects;
import java.util.Optional;

/**
 * Orchestrates the shift use cases.
 *
 * <p>Holds no rules of its own: it translates a command into domain objects, lets the domain reject
 * what is invalid, and hands the result to a port. Carries no Spring annotation, so it can be
 * constructed and tested without a container — wiring lives in {@code configuration}.
 *
 * <p>One service currently implements both use cases because there are only two. The convention as
 * they multiply is one service per use case; see ADR-003.
 */
public class ShiftService implements ScheduleShiftUseCase, FindShiftUseCase {

  private final ShiftRepository shiftRepository;

  /**
   * @param shiftRepository the storage port, never {@code null}
   */
  public ShiftService(ShiftRepository shiftRepository) {
    this.shiftRepository = Objects.requireNonNull(shiftRepository, "shiftRepository");
  }

  @Override
  public Shift schedule(ScheduleShiftCommand command) {
    Objects.requireNonNull(command, "command must not be null");
    Shift shift = new Shift(ShiftId.newId(), new ShiftPeriod(command.start(), command.end()));
    shiftRepository.save(shift);
    return shift;
  }

  @Override
  public Optional<Shift> findById(ShiftId id) {
    Objects.requireNonNull(id, "id must not be null");
    return shiftRepository.findById(id);
  }
}
