package io.github.naimelijah.hrsched.scheduling.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A generation's life (ADR-046): pending until it ends, once, and lost if it is still pending when
 * it should long since have ended.
 *
 * <p>The quarter of an hour it may stay pending is invented for these tests.
 */
class RosterGenerationTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final TimeRange THE_WEEK =
      new TimeRange(Instant.parse("2026-10-05T00:00:00Z"), Instant.parse("2026-10-12T00:00:00Z"));
  private static final Instant ASKED = Instant.parse("2026-10-01T09:00:00Z");

  /** Invented: a quarter of an hour. */
  private static final GenerationPolicy POLICY = new GenerationPolicy(Duration.ofMinutes(15));

  private static final ConstraintViolation BROKEN =
      ConstraintViolation.declaredUnavailable(EmployeeId.newId(), ShiftId.newId());

  private static RosterGeneration asked() {
    return RosterGeneration.requested(GenerationId.newId(), ACME, THE_WEEK, ASKED, POLICY);
  }

  @Test
  void GivenAGenerationJustAskedFor_WhenRead_ThenItIsPendingUntilItsLostAtInstant() {
    RosterGeneration asked = asked();

    assertThat(asked.status()).isEqualTo(GenerationStatus.PENDING);
    assertThat(asked.lostAt()).isEqualTo(ASKED.plus(Duration.ofMinutes(15)));
    assertThat(asked.finishedAt()).isEmpty();
    assertThat(asked.statusAt(ASKED.plus(Duration.ofMinutes(14))))
        .isEqualTo(GenerationStatus.PENDING);
  }

  /** The instance solving it stopped: nothing else would ever end it, so it reads as lost. */
  @Test
  void GivenAGenerationStillPendingAtItsLostAtInstant_WhenRead_ThenItIsLost() {
    RosterGeneration asked = asked();

    assertThat(asked.statusAt(asked.lostAt())).isEqualTo(GenerationStatus.LOST);
  }

  /**
   * Read as lost from its lost-at instant, and the Scheduler told to generate again, it stays lost:
   * a solve that outlasted that instant cannot end it after all.
   */
  @Test
  void GivenAGenerationAtItsLostAtInstant_WhenItEnds_ThenItIsRejected() {
    RosterGeneration asked = asked();

    assertThatThrownBy(() -> asked.succeeded(RosterId.newId(), asked.lostAt()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> asked.refused(List.of(BROKEN), asked.lostAt()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> asked.failed(asked.lostAt()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void GivenAGenerationThatSucceeded_WhenReadLongAfter_ThenItStillNamesItsDraft() {
    RosterId draft = RosterId.newId();

    RosterGeneration done = asked().succeeded(draft, ASKED.plusSeconds(3));

    assertThat(done.statusAt(ASKED.plus(Duration.ofDays(1)))).isEqualTo(GenerationStatus.SUCCEEDED);
    assertThat(done.roster()).contains(draft);
    assertThat(done.finishedAt()).contains(ASKED.plusSeconds(3));
  }

  @Test
  void GivenWorkCarriedOverThatCannotStay_WhenRefused_ThenTheRulesItBreaksAreKept() {
    RosterGeneration refused = asked().refused(List.of(BROKEN), ASKED.plusSeconds(1));

    assertThat(refused.status()).isEqualTo(GenerationStatus.REFUSED);
    assertThat(refused.violations()).containsExactly(BROKEN);
    assertThat(refused.roster()).isEmpty();
  }

  @Test
  void GivenNoRuleBroken_WhenRefused_ThenItIsRejected() {
    assertThatThrownBy(() -> asked().refused(List.of(), ASKED.plusSeconds(1)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void GivenAGenerationThatFailed_WhenEndedAgain_ThenItIsRejected() {
    RosterGeneration failed = asked().failed(ASKED.plusSeconds(1));

    assertThat(failed.status()).isEqualTo(GenerationStatus.FAILED);
    assertThatThrownBy(() -> failed.succeeded(RosterId.newId(), ASKED.plusSeconds(2)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void GivenAStoredRowThatContradictsItself_WhenRestored_ThenItIsRejected() {
    assertThatThrownBy(
            () ->
                RosterGeneration.restored(
                    GenerationId.newId(),
                    ACME,
                    THE_WEEK,
                    ASKED,
                    ASKED.plusSeconds(900),
                    GenerationStatus.SUCCEEDED,
                    Optional.empty(),
                    List.of(),
                    Optional.of(ASKED.plusSeconds(3))))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void GivenNoTimeAtAll_WhenAPolicyIsMade_ThenItIsRejected() {
    assertThatThrownBy(() -> new GenerationPolicy(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
