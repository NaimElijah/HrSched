package io.github.naimelijah.hrsched.scheduling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.scheduling.application.port.in.ScheduleShiftCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.out.ShiftRepository;
import io.github.naimelijah.hrsched.scheduling.domain.model.InvalidShiftPeriodException;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Specification of the shift use cases, driven without Spring.
 *
 * <p>The repository is a hand-written stub of our own driven port — no mocking framework, and no
 * container. That this test can exist at all is the property the hexagon is for.
 */
class ShiftServiceTest {

  private static final Instant EIGHT_AM = Instant.parse("2026-09-01T08:00:00Z");
  private static final Instant FOUR_PM = Instant.parse("2026-09-01T16:00:00Z");

  private final StubShiftRepository shifts = new StubShiftRepository();
  private final ShiftService service = new ShiftService(shifts);

  @Test
  void GivenAValidPeriod_WhenSchedulingAShift_ThenTheStoredShiftIsReturned() {
    Shift scheduled = service.schedule(new ScheduleShiftCommand(EIGHT_AM, FOUR_PM));

    assertThat(scheduled.period().start()).isEqualTo(EIGHT_AM);
    assertThat(scheduled.period().end()).isEqualTo(FOUR_PM);
    assertThat(service.findById(scheduled.id())).contains(scheduled);
  }

  @Test
  void GivenAnEndBeforeItsStart_WhenSchedulingAShift_ThenNothingIsStored() {
    assertThatThrownBy(() -> service.schedule(new ScheduleShiftCommand(FOUR_PM, EIGHT_AM)))
        .isInstanceOf(InvalidShiftPeriodException.class);

    assertThat(shifts.saved).isEmpty();
  }

  @Test
  void GivenAnUnknownIdentity_WhenFindingAShift_ThenNothingIsReturned() {
    assertThat(service.findById(ShiftId.newId())).isEmpty();
  }

  /** Minimal in-memory stand-in for the driven port. */
  private static final class StubShiftRepository implements ShiftRepository {

    private final Map<ShiftId, Shift> saved = new HashMap<>();

    @Override
    public void save(Shift shift) {
      saved.put(shift.id(), shift);
    }

    @Override
    public Optional<Shift> findById(ShiftId id) {
      return Optional.ofNullable(saved.get(id));
    }
  }
}
