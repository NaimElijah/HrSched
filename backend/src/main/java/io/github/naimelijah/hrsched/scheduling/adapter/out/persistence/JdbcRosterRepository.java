package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterRepository;
import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotEditableException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterPublicationConflictException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterStatus;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Driven adapter storing rosters in PostgreSQL.
 *
 * <p>History is append-only (R37, ADR-037). A save records the transition the roster has reached
 * and never rewrites what a publication recorded: a draft is stored and restored freely until it is
 * published, publishing records when, and superseding adds when that happened to a row that is
 * otherwise left alone. A save that would rewrite a published row is refused rather than applied,
 * which is also what stops an edit of a draft, committing after that draft was published, from
 * quietly turning it back into one.
 *
 * <p>A roster owns its assignments, so they are deleted and re-inserted on every save of a draft
 * and on its publication, and never touched again after that; the composite primary key on the
 * child table is {@code Roster.requireNoDuplicates} restated in the schema.
 *
 * <p>Reading rebuilds the aggregate through its own transitions rather than a back door, which is
 * why a superseded roster is assembled as draft, then published, then superseded: those are the
 * only legal routes to that state, and going round them would let this adapter construct a roster
 * the domain says cannot exist.
 *
 * <p>Saving a published roster that overlaps another roster of its organisation published over the
 * same moments of record is refused by the schema's {@code roster_one_published_per_moment}
 * constraint, and this adapter reports that in the domain's words rather than the driver's
 * (ADR-026, ADR-037).
 *
 * <p>Deliberately promises no ordering, matching the port. Its siblings sort on a field; there is
 * no field here that a caller has been told to expect an order by.
 */
@Repository
public class JdbcRosterRepository implements RosterRepository {

  /** PostgreSQL's {@code exclusion_violation}: the constraint found the overlap. */
  private static final String EXCLUSION_VIOLATION = "23P01";

  /**
   * PostgreSQL's {@code deadlock_detected}. Two writers each publishing an overlapping roster can
   * each wait on the other's uncommitted row; PostgreSQL then aborts one, which is the same
   * collision reported another way.
   */
  private static final String DEADLOCK_DETECTED = "40P01";

  /**
   * Read by SQLState rather than by exception type, because Spring maps an exclusion violation to
   * nothing more specific than a data integrity violation, which says nothing about which rule.
   */
  private static final Set<String> PUBLICATION_COLLISIONS =
      Set.of(EXCLUSION_VIOLATION, DEADLOCK_DETECTED);

  /**
   * Names what the publication lock guards, so its key has a namespace of its own rather than a
   * number somebody chose. Both halves of the key are 32-bit hashes, so two keys can still
   * coincide; the only cost is a publication that waits when it did not need to.
   */
  private static final String PUBLICATION_LOCK_SCOPE = "hrsched.roster-publication";

  private final JdbcClient jdbc;

  public JdbcRosterRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  @Override
  public void save(Roster roster) {
    try {
      store(roster);
    } catch (DataAccessException refused) {
      // Only a published roster enters the constraint's partial index, so only its save can be the
      // one PostgreSQL refuses, or picks as the victim, when two publications collide. A deadlock
      // anywhere else is not a publication conflict, and stays what it is.
      if (roster.status() == RosterStatus.PUBLISHED && isPublicationCollision(refused)) {
        throw new RosterPublicationConflictException(roster.id(), refused);
      }
      throw refused;
    }
  }

  /**
   * A transaction-scoped advisory lock keyed by organisation (ADR-027).
   *
   * <p>Transaction-scoped because PostgreSQL releases it when the transaction ends, so no path can
   * forget to, and it stays correct behind a pooler that lends connections per transaction.
   * Re-entrant within a session, so the publication nested in a swap approval takes it again
   * harmlessly. Two organisations whose identities hash alike only wait for each other; neither
   * sees anything of the other's.
   */
  @Override
  public void lockPublicationsOf(OrganisationId organisation) {
    // Taken in autocommit, a transaction-scoped lock ends with its own statement: the caller would
    // believe it was taking turns while nothing waited for anything.
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Publications can only be locked inside a transaction; outside one the lock would be"
              + " released as soon as it was taken");
    }
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:scope), hashtext(:organisation))")
        .param("scope", PUBLICATION_LOCK_SCOPE)
        .param("organisation", organisation.value().toString())
        .query()
        .listOfRows();
  }

  private void store(Roster roster) {
    switch (roster.status()) {
      case DRAFT, PUBLISHED -> storeUntilPublished(roster);
      case SUPERSEDED -> recordSupersession(roster);
    }
  }

  /**
   * Stores a draft, or records its publication, but only over a row that is still a draft. The
   * guard is in the statement rather than read first, so a writer that waited on the row for a
   * publication to commit sees the published row when it gets it, and is refused.
   */
  private void storeUntilPublished(Roster roster) {
    UUID id = roster.id().value();
    int stored =
        jdbc.sql(
                """
            INSERT INTO roster (id, organisation_id, starts_at, ends_at, published_at,
                                validated_against_constraint_version)
            VALUES (:id, :organisation, :startsAt, :endsAt, :publishedAt, :version)
            ON CONFLICT (id) DO UPDATE SET starts_at = EXCLUDED.starts_at,
                                           ends_at = EXCLUDED.ends_at,
                                           published_at = EXCLUDED.published_at,
                                           validated_against_constraint_version =
                                               EXCLUDED.validated_against_constraint_version
            WHERE roster.organisation_id = EXCLUDED.organisation_id
              AND roster.published_at IS NULL
            """)
            .param("id", id)
            .param("organisation", roster.organisation().value())
            .param("startsAt", roster.period().start().atOffset(ZoneOffset.UTC))
            .param("endsAt", roster.period().end().atOffset(ZoneOffset.UTC))
            .param("publishedAt", atUtc(roster.publishedAt()))
            .param("version", roster.validatedAgainstConstraintVersion())
            .update();
    requireRecorded(stored, roster);

    jdbc.sql("DELETE FROM roster_assignment WHERE roster_id = :id").param("id", id).update();
    for (Assignment assignment : roster.assignments()) {
      jdbc.sql(
              """
              INSERT INTO roster_assignment (roster_id, shift_id, employee_id)
              VALUES (:id, :shift, :employee)
              """)
          .param("id", id)
          .param("shift", assignment.shift().value())
          .param("employee", assignment.employee().value())
          .update();
    }
  }

  /**
   * Adds the moment a published roster stopped governing, and nothing else: its period, its
   * assignments and its publication stay as they were recorded.
   */
  private void recordSupersession(Roster roster) {
    int recorded =
        jdbc.sql(
                """
                UPDATE roster SET superseded_at = :supersededAt
                WHERE id = :id AND organisation_id = :organisation
                  AND published_at IS NOT NULL AND superseded_at IS NULL
                """)
            .param("supersededAt", atUtc(roster.supersededAt()))
            .param("id", roster.id().value())
            .param("organisation", roster.organisation().value())
            .update();
    requireRecorded(recorded, roster);
  }

  private static OffsetDateTime atUtc(Instant moment) {
    return moment == null ? null : moment.atOffset(ZoneOffset.UTC);
  }

  private static boolean isPublicationCollision(DataAccessException refused) {
    return NestedExceptionUtils.getMostSpecificCause(refused) instanceof SQLException cause
        && PUBLICATION_COLLISIONS.contains(cause.getSQLState());
  }

  @Override
  public Optional<Roster> findById(OrganisationId organisation, RosterId id) {
    return jdbc.sql(
            """
            SELECT id, organisation_id, starts_at, ends_at, published_at, superseded_at,
                   validated_against_constraint_version
            FROM roster WHERE organisation_id = :organisation AND id = :id
            """)
        .param("organisation", organisation.value())
        .param("id", id.value())
        .query(JdbcRosterRepository::toBareRoster)
        .optional()
        .map(bare -> withAssignments(List.of(bare)).getFirst());
  }

  @Override
  public List<Roster> findByOrganisation(OrganisationId organisation) {
    return withAssignments(
        jdbc.sql(
                """
                SELECT id, organisation_id, starts_at, ends_at, published_at, superseded_at,
                       validated_against_constraint_version
                FROM roster WHERE organisation_id = :organisation
                """)
            .param("organisation", organisation.value())
            .query(JdbcRosterRepository::toBareRoster)
            .list());
  }

  /** A roster's own row, before its assignments are attached. */
  private record BareRoster(
      RosterId id,
      OrganisationId organisation,
      TimeRange period,
      Instant publishedAt,
      Instant supersededAt,
      Integer constraintVersion) {}

  private static BareRoster toBareRoster(ResultSet row, int rowNumber) throws SQLException {
    return new BareRoster(
        new RosterId(row.getObject("id", UUID.class)),
        new OrganisationId(row.getObject("organisation_id", UUID.class)),
        new TimeRange(
            row.getObject("starts_at", OffsetDateTime.class).toInstant(),
            row.getObject("ends_at", OffsetDateTime.class).toInstant()),
        instantOrNull(row, "published_at"),
        instantOrNull(row, "superseded_at"),
        row.getObject("validated_against_constraint_version", Integer.class));
  }

  private static Instant instantOrNull(ResultSet row, String column) throws SQLException {
    OffsetDateTime moment = row.getObject(column, OffsetDateTime.class);
    return moment == null ? null : moment.toInstant();
  }

  private List<Roster> withAssignments(List<BareRoster> bare) {
    if (bare.isEmpty()) {
      return List.of();
    }
    Map<UUID, List<Assignment>> assignments =
        assignmentsOf(bare.stream().map(roster -> roster.id().value()).toList());
    return bare.stream()
        .map(roster -> rebuild(roster, assignments.getOrDefault(roster.id().value(), List.of())))
        .toList();
  }

  /**
   * Walks the aggregate back to the state it was stored in, using only the transitions the domain
   * offers. A published or superseded roster is therefore built as a draft first, because that is
   * the only way one is ever legitimately reached. Which state that is follows from the moments the
   * row records, as the schema stores no status of its own to disagree with them.
   */
  private static Roster rebuild(BareRoster bare, List<Assignment> assignments) {
    Roster draft =
        new Roster(bare.id(), bare.organisation(), bare.period()).withAssignments(assignments);
    if (bare.publishedAt() == null) {
      return draft;
    }
    Roster published = draft.publishedUnder(bare.constraintVersion(), bare.publishedAt());
    return bare.supersededAt() == null ? published : published.superseded(bare.supersededAt());
  }

  private Map<UUID, List<Assignment>> assignmentsOf(List<UUID> ids) {
    record Owned(UUID roster, Assignment assignment) {}
    List<Owned> rows =
        jdbc.sql(
                """
                SELECT roster_id, shift_id, employee_id FROM roster_assignment
                WHERE roster_id IN (:ids) ORDER BY roster_id, shift_id, employee_id
                """)
            .param("ids", ids)
            .query(
                (row, rowNumber) ->
                    new Owned(
                        row.getObject("roster_id", UUID.class),
                        new Assignment(
                            new ShiftId(row.getObject("shift_id", UUID.class)),
                            new EmployeeId(row.getObject("employee_id", UUID.class)))))
            .list();

    Map<UUID, List<Assignment>> byRoster = new LinkedHashMap<>();
    for (Owned row : rows) {
      byRoster.computeIfAbsent(row.roster(), key -> new ArrayList<>()).add(row.assignment());
    }
    return byRoster;
  }

  /**
   * Turns a write that affected nothing into a refusal, because a write that quietly did nothing is
   * the worse of the two failures.
   *
   * <p>Each statement's {@code WHERE} stands between a save and two things it must not do: write
   * over another tenant's row, and rewrite this tenant's history. Which one stopped it is read
   * back, so the refusal can say. A roster of this organisation that has moved past what the save
   * expects is a roster that is no longer editable, in the domain's words; anything else is an
   * identity the organisation does not hold.
   *
   * @throws RosterNotEditableException if the roster is stored, but published, or not published,
   *     where the save needed otherwise
   * @throws IllegalStateException if the organisation holds no roster with that identity
   */
  private void requireRecorded(int rows, Roster roster) {
    if (rows > 0) {
      return;
    }
    UUID id = roster.id().value();
    RosterStatus stored =
        jdbc.sql(
                """
                SELECT published_at, superseded_at FROM roster
                WHERE id = :id AND organisation_id = :organisation
                """)
            .param("id", id)
            .param("organisation", roster.organisation().value())
            .query(
                (row, rowNumber) ->
                    statusOf(
                        instantOrNull(row, "published_at"), instantOrNull(row, "superseded_at")))
            .optional()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Refused to store %s: its organisation holds no roster with that identity"
                            .formatted(id)));
    throw new RosterNotEditableException(
        roster.id(),
        stored,
        roster.status() == RosterStatus.SUPERSEDED
            ? "only a published roster can be superseded"
            : "a roster can only be changed while a draft");
  }

  private static RosterStatus statusOf(Instant publishedAt, Instant supersededAt) {
    if (publishedAt == null) {
      return RosterStatus.DRAFT;
    }
    return supersededAt == null ? RosterStatus.PUBLISHED : RosterStatus.SUPERSEDED;
  }
}
