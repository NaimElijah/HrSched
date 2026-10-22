package io.github.naimelijah.hrsched.scheduling.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One request for a draft, solved in the background, and how it ended (ADR-046).
 *
 * <p>Immutable. It is {@link GenerationStatus#PENDING} from the moment it is asked for until it
 * ends, once: with a draft, refused for the rules the work carried over breaks, or failed. One
 * still pending past {@link #lostAt()} reads as {@link GenerationStatus#LOST}, because the instance
 * solving it stopped, and nothing else would ever end it.
 */
public final class RosterGeneration {

  private final GenerationId id;
  private final OrganisationId organisation;
  private final TimeRange period;
  private final Instant requestedAt;
  private final Instant lostAt;
  private final GenerationStatus status;
  private final RosterId roster;
  private final List<ConstraintViolation> violations;
  private final Instant finishedAt;

  private RosterGeneration(
      GenerationId id,
      OrganisationId organisation,
      TimeRange period,
      Instant requestedAt,
      Instant lostAt,
      GenerationStatus status,
      RosterId roster,
      List<ConstraintViolation> violations,
      Instant finishedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.organisation = Objects.requireNonNull(organisation, "organisation must not be null");
    this.period = Objects.requireNonNull(period, "period must not be null");
    this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt must not be null");
    this.lostAt = Objects.requireNonNull(lostAt, "lostAt must not be null");
    this.status = Objects.requireNonNull(status, "status must not be null");
    this.violations = List.copyOf(violations);
    this.roster = roster;
    this.finishedAt = finishedAt;
    // Reachable only through the factories and transitions below, which keep these; checked so
    // that a stored row contradicting itself cannot be loaded as though it made sense.
    if (status == GenerationStatus.LOST
        || !lostAt.isAfter(requestedAt)
        || (status == GenerationStatus.PENDING) != (finishedAt == null)
        || (status == GenerationStatus.SUCCEEDED) != (roster != null)
        || (status == GenerationStatus.REFUSED) == this.violations.isEmpty()
        || (finishedAt != null && finishedAt.isBefore(requestedAt))) {
      throw new IllegalStateException(
          "Generation %s is %s, which what it records does not agree with"
              .formatted(id.value(), status));
    }
  }

  /**
   * A generation just asked for.
   *
   * @param period the span the draft is to cover
   * @param at when it was asked for
   * @param policy how long it may stay pending before it counts as lost
   * @return it, pending
   */
  public static RosterGeneration requested(
      GenerationId id,
      OrganisationId organisation,
      TimeRange period,
      Instant at,
      GenerationPolicy policy) {
    return new RosterGeneration(
        id,
        organisation,
        period,
        at,
        at.plus(policy.lostAfter()),
        GenerationStatus.PENDING,
        null,
        List.of(),
        null);
  }

  /**
   * A generation as it was stored, for the adapter reading it back.
   *
   * @param status as stored, so never {@link GenerationStatus#LOST}
   * @param roster the draft, when it succeeded
   * @param violations the rules broken, when it was refused
   * @param finishedAt when it ended, unless it is pending
   * @throws IllegalStateException if these contradict each other
   */
  public static RosterGeneration restored(
      GenerationId id,
      OrganisationId organisation,
      TimeRange period,
      Instant requestedAt,
      Instant lostAt,
      GenerationStatus status,
      Optional<RosterId> roster,
      List<ConstraintViolation> violations,
      Optional<Instant> finishedAt) {
    return new RosterGeneration(
        id,
        organisation,
        period,
        requestedAt,
        lostAt,
        status,
        roster.orElse(null),
        violations,
        finishedAt.orElse(null));
  }

  /**
   * @param draft the draft it produced
   * @param at when it ended
   * @return it, ended with that draft
   * @throws IllegalStateException if it has already ended
   */
  public RosterGeneration succeeded(RosterId draft, Instant at) {
    requirePending();
    Objects.requireNonNull(draft, "draft must not be null");
    return ended(GenerationStatus.SUCCEEDED, draft, List.of(), at);
  }

  /**
   * @param broken every rule the work carried over breaks, at least one
   * @param at when it ended
   * @return it, refused
   * @throws IllegalStateException if it has already ended, or nothing was broken
   */
  public RosterGeneration refused(List<ConstraintViolation> broken, Instant at) {
    requirePending();
    return ended(GenerationStatus.REFUSED, null, broken, at);
  }

  /**
   * @param at when it ended
   * @return it, failed
   * @throws IllegalStateException if it has already ended
   */
  public RosterGeneration failed(Instant at) {
    requirePending();
    return ended(GenerationStatus.FAILED, null, List.of(), at);
  }

  private RosterGeneration ended(
      GenerationStatus outcome, RosterId draft, List<ConstraintViolation> broken, Instant at) {
    return new RosterGeneration(
        id,
        organisation,
        period,
        requestedAt,
        lostAt,
        outcome,
        draft,
        broken,
        Objects.requireNonNull(at, "at must not be null"));
  }

  private void requirePending() {
    if (status != GenerationStatus.PENDING) {
      throw new IllegalStateException(
          "Generation %s has already ended, %s".formatted(id.value(), status));
    }
  }

  /**
   * @param now what the clock reads
   * @return its status then: as stored, unless it is pending at or past {@link #lostAt()}
   */
  public GenerationStatus statusAt(Instant now) {
    return status == GenerationStatus.PENDING && !now.isBefore(lostAt)
        ? GenerationStatus.LOST
        : status;
  }

  public GenerationId id() {
    return id;
  }

  public OrganisationId organisation() {
    return organisation;
  }

  public TimeRange period() {
    return period;
  }

  public Instant requestedAt() {
    return requestedAt;
  }

  /**
   * @return the instant from which, still pending, it counts as lost
   */
  public Instant lostAt() {
    return lostAt;
  }

  /**
   * @return its status as stored, which is never {@link GenerationStatus#LOST}; see {@link
   *     #statusAt}
   */
  public GenerationStatus status() {
    return status;
  }

  /**
   * @return the draft it produced, if it succeeded
   */
  public Optional<RosterId> roster() {
    return Optional.ofNullable(roster);
  }

  /**
   * @return every rule the work carried over breaks, if it was refused; empty otherwise
   */
  public List<ConstraintViolation> violations() {
    return violations;
  }

  /**
   * @return when it ended, unless it is still pending
   */
  public Optional<Instant> finishedAt() {
    return Optional.ofNullable(finishedAt);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof RosterGeneration generation && id.equals(generation.id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }

  @Override
  public String toString() {
    return "RosterGeneration[id=%s, status=%s]".formatted(id.value(), status);
  }
}
