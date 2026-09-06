package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import io.github.naimelijah.hrsched.scheduling.application.port.out.SwapRequestRepository;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequest;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestId;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestStatus;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Driven adapter storing swap requests in PostgreSQL.
 *
 * <p>Requests come back oldest first, which is the order a Scheduler works through them. That is
 * ordered on {@code requested_at} with the identity as a tie-break, so two requests raised in the
 * same instant still come back in a stable order. The aggregate gained that field for this reason:
 * the promise used to rest on a store that happened to keep insertion order (ADR-004).
 *
 * <p>Flat, with no owned rows. It names a roster and a shift by identity rather than holding them,
 * so a decided request keeps meaning something even after the roster it referred to is superseded.
 */
@Repository
public class JdbcSwapRequestRepository implements SwapRequestRepository {

  private final JdbcClient jdbc;

  public JdbcSwapRequestRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  @Override
  public void save(SwapRequest request) {
    jdbc.sql(
            """
            INSERT INTO swap_request (id, organisation_id, roster_id, shift_id, requester_id,
                                      counterparty_id, status, requested_at)
            VALUES (:id, :organisation, :roster, :shift, :requester, :counterparty, :status,
                    :requestedAt)
            ON CONFLICT (id) DO UPDATE SET organisation_id = EXCLUDED.organisation_id,
                                           roster_id = EXCLUDED.roster_id,
                                           shift_id = EXCLUDED.shift_id,
                                           requester_id = EXCLUDED.requester_id,
                                           counterparty_id = EXCLUDED.counterparty_id,
                                           status = EXCLUDED.status,
                                           requested_at = EXCLUDED.requested_at
            """)
        .param("id", request.id().value())
        .param("organisation", request.organisation().value())
        .param("roster", request.roster().value())
        .param("shift", request.shift().value())
        .param("requester", request.requester().value())
        .param("counterparty", request.counterparty().value())
        .param("status", request.status().name())
        .param("requestedAt", request.requestedAt().atOffset(ZoneOffset.UTC))
        .update();
  }

  @Override
  public Optional<SwapRequest> findById(OrganisationId organisation, SwapRequestId id) {
    return jdbc.sql(
            """
            SELECT id, organisation_id, roster_id, shift_id, requester_id, counterparty_id,
                   status, requested_at
            FROM swap_request WHERE organisation_id = :organisation AND id = :id
            """)
        .param("organisation", organisation.value())
        .param("id", id.value())
        .query(JdbcSwapRequestRepository::toSwapRequest)
        .optional();
  }

  @Override
  public List<SwapRequest> findByOrganisation(OrganisationId organisation) {
    return jdbc.sql(
            """
            SELECT id, organisation_id, roster_id, shift_id, requester_id, counterparty_id,
                   status, requested_at
            FROM swap_request WHERE organisation_id = :organisation
            ORDER BY requested_at, id
            """)
        .param("organisation", organisation.value())
        .query(JdbcSwapRequestRepository::toSwapRequest)
        .list();
  }

  /**
   * Rebuilds through the aggregate's own transitions: a decided request is constructed pending and
   * then decided, because that is the only route the domain offers to a decided one.
   */
  private static SwapRequest toSwapRequest(ResultSet row, int rowNumber) throws SQLException {
    SwapRequest pending =
        new SwapRequest(
            new SwapRequestId(row.getObject("id", UUID.class)),
            new OrganisationId(row.getObject("organisation_id", UUID.class)),
            new RosterId(row.getObject("roster_id", UUID.class)),
            new ShiftId(row.getObject("shift_id", UUID.class)),
            new EmployeeId(row.getObject("requester_id", UUID.class)),
            new EmployeeId(row.getObject("counterparty_id", UUID.class)),
            row.getObject("requested_at", OffsetDateTime.class).toInstant());
    return switch (SwapRequestStatus.valueOf(row.getString("status"))) {
      case REQUESTED -> pending;
      case APPROVED -> pending.approved();
      case REJECTED -> pending.rejected();
    };
  }
}
