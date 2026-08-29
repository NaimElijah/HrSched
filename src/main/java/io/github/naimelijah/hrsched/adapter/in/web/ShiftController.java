package io.github.naimelijah.hrsched.adapter.in.web;

import io.github.naimelijah.hrsched.application.port.in.FindShiftUseCase;
import io.github.naimelijah.hrsched.application.port.in.ScheduleShiftCommand;
import io.github.naimelijah.hrsched.application.port.in.ScheduleShiftUseCase;
import io.github.naimelijah.hrsched.domain.model.ShiftId;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Driving adapter exposing shifts over HTTP.
 *
 * <p>Depends only on the driving ports, never on {@code ShiftService}, so the use cases can be
 * re-implemented without touching this class. Its whole job is translation: HTTP in, command out,
 * domain result in, HTTP out.
 */
@RestController
@RequestMapping("/api/shifts")
public class ShiftController {

  private final ScheduleShiftUseCase scheduleShift;
  private final FindShiftUseCase findShift;

  public ShiftController(ScheduleShiftUseCase scheduleShift, FindShiftUseCase findShift) {
    this.scheduleShift = scheduleShift;
    this.findShift = findShift;
  }

  /**
   * Places a new shift on the schedule.
   *
   * @param request the requested period
   * @return 201 with the created shift and its {@code Location}
   */
  @PostMapping
  public ResponseEntity<ShiftResponse> schedule(@RequestBody ScheduleShiftRequest request) {
    ShiftId id = scheduleShift.schedule(new ScheduleShiftCommand(request.start(), request.end()));
    ShiftResponse body =
        findShift
            .findById(id)
            .map(ShiftResponse::from)
            .orElseThrow(
                () -> new IllegalStateException("Shift %s vanished after saving".formatted(id)));
    return ResponseEntity.created(URI.create("/api/shifts/" + id.value())).body(body);
  }

  /**
   * @param id the shift identity from the path
   * @return 200 with the shift, or 404 if there is no such shift
   */
  @GetMapping("/{id}")
  public ResponseEntity<ShiftResponse> findById(@PathVariable UUID id) {
    return findShift
        .findById(new ShiftId(id))
        .map(ShiftResponse::from)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
