package io.github.naimelijah.hrsched.scheduling.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.scheduling.domain.model.ObjectiveWeights;
import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.scheduling.domain.model.StatedPreferences;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What a generator may be asked: a draft, and only shifts and people it can account for.
 *
 * <p>Every request a generator sees is built by the use case, so these refusals are about a
 * caller's mistake, never a state a user can reach.
 */
class GenerationRequestTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final TimeRange THE_WEEK =
      new TimeRange(Instant.parse("2026-10-05T00:00:00Z"), Instant.parse("2026-10-12T00:00:00Z"));
  private static final Shift MONDAY =
      new Shift(
          ShiftId.newId(),
          ACME,
          new TimeRange(
              Instant.parse("2026-10-05T08:00:00Z"), Instant.parse("2026-10-05T16:00:00Z")),
          ZoneOffset.UTC,
          1,
          Set.of(),
          false);

  private static final ObjectiveWeights NONE = ObjectiveWeights.none(ACME);

  private static Roster emptyDraft() {
    return new Roster(RosterId.newId(), ACME, THE_WEEK);
  }

  private static RosterCandidate candidate(EmployeeId id) {
    return new RosterCandidate(id, "Ada", Set.of(), List.of(), StatedPreferences.none());
  }

  private static Stream<ThrowingCallable> nullFields() {
    return Stream.of(
        () ->
            new GenerationRequest(
                null,
                List.of(),
                List.of(),
                ConstraintSet.initial(ACME),
                List.of(),
                NONE,
                List.of()),
        () ->
            new GenerationRequest(
                emptyDraft(),
                null,
                List.of(),
                ConstraintSet.initial(ACME),
                List.of(),
                NONE,
                List.of()),
        () ->
            new GenerationRequest(
                emptyDraft(),
                List.of(),
                null,
                ConstraintSet.initial(ACME),
                List.of(),
                NONE,
                List.of()),
        () ->
            new GenerationRequest(
                emptyDraft(), List.of(), List.of(), null, List.of(), NONE, List.of()),
        () ->
            new GenerationRequest(
                emptyDraft(),
                List.of(),
                List.of(),
                ConstraintSet.initial(ACME),
                null,
                NONE,
                List.of()),
        () ->
            new GenerationRequest(
                emptyDraft(),
                List.of(),
                List.of(),
                ConstraintSet.initial(ACME),
                List.of(),
                null,
                List.of()),
        () ->
            new GenerationRequest(
                emptyDraft(),
                List.of(),
                List.of(),
                ConstraintSet.initial(ACME),
                List.of(),
                NONE,
                null));
  }

  @ParameterizedTest
  @MethodSource("nullFields")
  void GivenANullField_WhenRequestingGeneration_ThenItIsRejected(ThrowingCallable requesting) {
    assertThatThrownBy(requesting).isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenAPublishedRoster_WhenRequestingGeneration_ThenItIsRefused() {
    Roster published = emptyDraft().publishedUnder(1, Instant.parse("2026-10-01T09:00:00Z"));

    assertThatThrownBy(
            () ->
                new GenerationRequest(
                    published,
                    List.of(MONDAY),
                    List.of(),
                    ConstraintSet.initial(ACME),
                    List.of(),
                    NONE,
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void GivenADraftCarryingAShiftNotOffered_WhenRequestingGeneration_ThenItIsRefused() {
    EmployeeId ada = EmployeeId.newId();
    Roster carrying = emptyDraft().with(new Assignment(MONDAY.id(), ada));

    assertThatThrownBy(
            () ->
                new GenerationRequest(
                    carrying,
                    List.of(),
                    List.of(candidate(ada)),
                    ConstraintSet.initial(ACME),
                    List.of(),
                    NONE,
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void GivenOneCandidateOfferedTwice_WhenRequestingGeneration_ThenItIsRefused() {
    EmployeeId ada = EmployeeId.newId();

    assertThatThrownBy(
            () ->
                new GenerationRequest(
                    emptyDraft(),
                    List.of(MONDAY),
                    List.of(candidate(ada), candidate(ada)),
                    ConstraintSet.initial(ACME),
                    List.of(),
                    NONE,
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void GivenAnotherOrganisationsWeights_WhenRequestingGeneration_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                new GenerationRequest(
                    emptyDraft(),
                    List.of(MONDAY),
                    List.of(),
                    ConstraintSet.initial(ACME),
                    List.of(),
                    ObjectiveWeights.none(OrganisationId.newId()),
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** Disruption is counted against the rosters the draft replaces, and only against those. */
  @Test
  void GivenARosterTheDraftWouldNotSupersede_WhenOfferedAsSuperseded_ThenItIsRefused() {
    TimeRange nextWeek =
        new TimeRange(Instant.parse("2026-10-12T00:00:00Z"), Instant.parse("2026-10-19T00:00:00Z"));
    Roster alongside =
        new Roster(RosterId.newId(), ACME, nextWeek)
            .publishedUnder(1, Instant.parse("2026-10-01T09:00:00Z"));

    assertThatThrownBy(
            () ->
                new GenerationRequest(
                    emptyDraft(),
                    List.of(MONDAY),
                    List.of(),
                    ConstraintSet.initial(ACME),
                    List.of(),
                    NONE,
                    List.of(alongside)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void GivenTheListsChangeAfterwards_WhenTheRequestIsRead_ThenItStillHoldsWhatItWasGiven() {
    List<Shift> shifts = new ArrayList<>(List.of(MONDAY));
    List<RosterCandidate> candidates = new ArrayList<>(List.of(candidate(EmployeeId.newId())));
    GenerationRequest request =
        new GenerationRequest(
            emptyDraft(),
            shifts,
            candidates,
            ConstraintSet.initial(ACME),
            List.of(),
            NONE,
            List.of());

    shifts.clear();
    candidates.clear();

    assertThat(request.shifts()).containsExactly(MONDAY);
    assertThat(request.candidates()).hasSize(1);
  }
}
