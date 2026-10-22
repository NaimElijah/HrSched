package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintViolation;
import io.github.naimelijah.hrsched.scheduling.domain.model.GenerationId;
import io.github.naimelijah.hrsched.scheduling.domain.model.GenerationPolicy;
import io.github.naimelijah.hrsched.scheduling.domain.model.GenerationStatus;
import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterGeneration;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the generation store promises (ADR-046): a generation reads back as it was saved, ends once,
 * and is visible only to the organisation that asked for it.
 *
 * <p>The quarter of an hour a generation may stay pending is invented for these tests.
 */
class JdbcRosterGenerationRepositoryTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final OrganisationId RIVAL = OrganisationId.newId();
  private static final TimeRange THE_WEEK =
      new TimeRange(Instant.parse("2026-10-05T00:00:00Z"), Instant.parse("2026-10-12T00:00:00Z"));
  private static final Instant ASKED = Instant.parse("2026-10-01T09:00:00Z");

  /** Invented: a quarter of an hour. */
  private static final GenerationPolicy POLICY = new GenerationPolicy(Duration.ofMinutes(15));

  private final JdbcRosterGenerationRepository repository =
      new JdbcRosterGenerationRepository(PostgresDatabase.jdbcClient());

  @BeforeEach
  void emptyTheDatabase() {
    PostgresDatabase.empty();
    PostgresDatabase.given(ACME, RIVAL);
  }

  private static RosterGeneration askedBy(OrganisationId organisation) {
    return RosterGeneration.requested(GenerationId.newId(), organisation, THE_WEEK, ASKED, POLICY);
  }

  @Test
  void GivenAGenerationJustAskedFor_WhenReadBack_ThenItIsPendingAsSaved() {
    RosterGeneration asked = askedBy(ACME);

    repository.save(asked);

    RosterGeneration read = repository.findById(ACME, asked.id()).orElseThrow();
    assertThat(read.status()).isEqualTo(GenerationStatus.PENDING);
    assertThat(read.period()).isEqualTo(THE_WEEK);
    assertThat(read.requestedAt()).isEqualTo(ASKED);
    assertThat(read.lostAt()).isEqualTo(asked.lostAt());
    assertThat(read.finishedAt()).isEmpty();
  }

  @Test
  void GivenAGenerationThatSucceeded_WhenReadBack_ThenItNamesItsDraft() {
    Roster draft = new Roster(RosterId.newId(), ACME, THE_WEEK);
    new JdbcRosterRepository(PostgresDatabase.jdbcClient()).save(draft);
    RosterGeneration asked = askedBy(ACME);
    repository.save(asked);

    repository.save(asked.succeeded(draft.id(), ASKED.plusSeconds(3)));

    RosterGeneration read = repository.findById(ACME, asked.id()).orElseThrow();
    assertThat(read.status()).isEqualTo(GenerationStatus.SUCCEEDED);
    assertThat(read.roster()).contains(draft.id());
    assertThat(read.finishedAt()).contains(ASKED.plusSeconds(3));
  }

  @Test
  void GivenARefusedGeneration_WhenReadBack_ThenEveryRuleItBreaksComesBackInOrder() {
    EmployeeId ada = EmployeeId.newId();
    List<ConstraintViolation> broken =
        List.of(
            ConstraintViolation.declaredUnavailable(ada, ShiftId.newId()),
            ConstraintViolation.notOfTheOrganisation(EmployeeId.newId(), ShiftId.newId()));
    RosterGeneration asked = askedBy(ACME);
    repository.save(asked);

    repository.save(asked.refused(broken, ASKED.plusSeconds(1)));

    assertThat(repository.findById(ACME, asked.id()).orElseThrow().violations())
        .containsExactlyElementsOf(broken);
  }

  @Test
  void GivenAnotherOrganisationsGeneration_WhenReadByUs_ThenNothingIsFound() {
    RosterGeneration theirs = askedBy(RIVAL);
    repository.save(theirs);

    assertThat(repository.findById(ACME, theirs.id())).isEmpty();
  }

  /** A generation ends once: a second ending is a defect, refused rather than stored over. */
  @Test
  void GivenAGenerationThatHasEnded_WhenEndedAgain_ThenItIsRefusedAndTheFirstEndingStands() {
    RosterGeneration asked = askedBy(ACME);
    repository.save(asked);
    repository.save(asked.failed(ASKED.plusSeconds(1)));

    assertThatThrownBy(
            () ->
                repository.save(
                    asked.refused(
                        List.of(
                            ConstraintViolation.declaredUnavailable(
                                EmployeeId.newId(), ShiftId.newId())),
                        ASKED.plusSeconds(2))))
        .isInstanceOf(IllegalStateException.class);
    assertThat(repository.findById(ACME, asked.id()).orElseThrow().status())
        .isEqualTo(GenerationStatus.FAILED);
  }

  @Test
  void GivenAnotherOrganisationsGeneration_WhenSavedOverAsOurs_ThenItIsRefused() {
    RosterGeneration theirs = askedBy(RIVAL);
    repository.save(theirs);
    RosterGeneration ours = RosterGeneration.requested(theirs.id(), ACME, THE_WEEK, ASKED, POLICY);

    assertThatThrownBy(() -> repository.save(ours.failed(ASKED.plusSeconds(1))))
        .isInstanceOf(IllegalStateException.class);
    assertThat(repository.findById(RIVAL, theirs.id()).orElseThrow().status())
        .isEqualTo(GenerationStatus.PENDING);
  }
}
