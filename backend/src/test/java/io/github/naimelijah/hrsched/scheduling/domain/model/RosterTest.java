package io.github.naimelijah.hrsched.scheduling.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Specification of the roster lifecycle. */
class RosterTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final EmployeeId ADA = EmployeeId.newId();
  private static final TimeRange WEEK =
      new TimeRange(Instant.parse("2026-09-07T00:00:00Z"), Instant.parse("2026-09-14T00:00:00Z"));
  private static final TimeRange MONDAY =
      new TimeRange(Instant.parse("2026-09-07T08:00:00Z"), Instant.parse("2026-09-07T16:00:00Z"));

  private static Roster draft() {
    return new Roster(RosterId.newId(), ACME, WEEK);
  }

  @Test
  void GivenANewRoster_WhenCreated_ThenItIsAnEmptyDraft() {
    Roster roster = draft();

    assertThat(roster.status()).isEqualTo(RosterStatus.DRAFT);
    assertThat(roster.assignments()).isEmpty();
    assertThat(roster.validatedAgainstConstraintVersion()).isNull();
  }

  @Test
  void GivenADraft_WhenAssigningSomeone_ThenTheAssignmentIsHeld() {
    ShiftId shift = ShiftId.newId();

    Roster roster = draft().with(new Assignment(shift, ADA));

    assertThat(roster.assignments()).containsExactly(new Assignment(shift, ADA));
  }

  @Test
  void GivenAnAssignment_WhenMakingItTwice_ThenItIsRejected() {
    ShiftId shift = ShiftId.newId();
    Roster roster = draft().with(new Assignment(shift, ADA));

    assertThatThrownBy(() -> roster.with(new Assignment(shift, ADA)))
        .isInstanceOf(DuplicateAssignmentException.class);
  }

  @Test
  void GivenADraft_WhenPublished_ThenItRecordsTheConstraintVersionThatApprovedIt() {
    Roster published = draft().publishedUnder(7);

    assertThat(published.status()).isEqualTo(RosterStatus.PUBLISHED);
    assertThat(published.validatedAgainstConstraintVersion())
        .describedAs("a rule change later must never make this roster retroactively illegal")
        .isEqualTo(7);
  }

  @Test
  void GivenAPublishedRoster_WhenChangingIt_ThenItIsRefused() {
    Roster published = draft().publishedUnder(1);

    assertThatThrownBy(() -> published.with(new Assignment(ShiftId.newId(), ADA)))
        .describedAs("people plan their lives around a published roster")
        .isInstanceOf(RosterNotEditableException.class);
    assertThatThrownBy(() -> published.withAssignments(List.of()))
        .isInstanceOf(RosterNotEditableException.class);
  }

  @Test
  void GivenAPublishedRoster_WhenPublishedAgain_ThenItIsRefused() {
    Roster published = draft().publishedUnder(1);

    assertThatThrownBy(() -> published.publishedUnder(2))
        .isInstanceOf(RosterNotEditableException.class);
  }

  @Test
  void GivenAPublishedRoster_WhenSuperseded_ThenItKeepsItsHistory() {
    Roster superseded =
        draft().with(new Assignment(ShiftId.newId(), ADA)).publishedUnder(3).superseded();

    assertThat(superseded.status()).isEqualTo(RosterStatus.SUPERSEDED);
    assertThat(superseded.assignments()).hasSize(1);
    assertThat(superseded.validatedAgainstConstraintVersion()).isEqualTo(3);
  }

  @Test
  void GivenADraft_WhenSuperseding_ThenItIsRefused() {
    assertThatThrownBy(() -> draft().superseded()).isInstanceOf(RosterNotEditableException.class);
  }

  @Test
  void GivenARosterWithAssignments_WhenBuildingSchedules_ThenEachEmployeeGetsTheirOwn() {
    ShiftId monday = ShiftId.newId();
    ShiftId alsoMonday = ShiftId.newId();
    EmployeeId grace = EmployeeId.newId();
    Roster roster =
        draft()
            .with(new Assignment(monday, ADA))
            .with(new Assignment(alsoMonday, ADA))
            .with(new Assignment(monday, grace));

    List<EmployeeSchedule> schedules =
        roster.schedulesFor(Map.of(monday, MONDAY, alsoMonday, MONDAY));

    assertThat(schedules).hasSize(2);
    assertThat(schedules)
        .filteredOn(schedule -> schedule.employee().equals(ADA))
        .singleElement()
        .satisfies(schedule -> assertThat(schedule.workingPeriods()).hasSize(2));
  }
}
