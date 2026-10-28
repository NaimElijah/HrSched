package io.github.naimelijah.hrsched.configuration.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Specification that a refused write does not put the row it refused into an exception message
 * (issue #250).
 *
 * <p>PostgreSQL describes a CHECK or NOT NULL violation with a detail line quoting the whole
 * failing row, and the JDBC driver copies that detail into the exception's message unless told
 * otherwise. An employee row holds a name and contracted hours, which say things about a person,
 * and an exception nobody handles is logged at ERROR with its message — so the application's
 * connections must keep the detail out of every message, here and in every cause beneath it.
 *
 * <p>Driven through the application's own {@link JdbcClient}, and so its configured {@code
 * DataSource}: the standalone pool the adapter tests use is configured by the test suite, not by
 * the application, and would prove nothing about what a deployment logs.
 *
 * <p>The name and the number of hours are invented, and chosen to be unmistakable in a message.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class DatabaseErrorDetailTest {

  /** Invented: a name no person here has, which a message could only contain by quoting the row. */
  private static final String INVENTED_NAME = "Invented Zebedee Quillfeather";

  /** Invented, and negative, so the contracted-hours CHECK refuses it. */
  private static final long INVENTED_NEGATIVE_SECONDS = -424_242L;

  /** Invented, and positive, so only the absent name refuses the row it is in. */
  private static final long INVENTED_POSITIVE_SECONDS = 434_343L;

  @Autowired private JdbcClient jdbc;

  @Test
  void GivenARowViolatingACheck_WhenInserted_ThenNoMessageInTheCauseChainQuotesTheRow() {
    UUID organisation = anOrganisation();

    Throwable refused =
        catchThrowable(
            () -> insertEmployee(organisation, INVENTED_NAME, INVENTED_NEGATIVE_SECONDS));

    assertThat(refused)
        .describedAs("arranged: the CHECK refuses the row")
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(messagesOf(refused))
        .noneMatch(message -> message.contains(INVENTED_NAME))
        .noneMatch(message -> message.contains(Long.toString(INVENTED_NEGATIVE_SECONDS)));
  }

  @Test
  void GivenARowViolatingNotNull_WhenInserted_ThenNoMessageInTheCauseChainQuotesTheRow() {
    UUID organisation = anOrganisation();

    Throwable refused =
        catchThrowable(() -> insertEmployee(organisation, null, INVENTED_POSITIVE_SECONDS));

    assertThat(refused)
        .describedAs("arranged: NOT NULL refuses the row")
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(messagesOf(refused))
        .noneMatch(message -> message.contains(Long.toString(INVENTED_POSITIVE_SECONDS)));
  }

  private UUID anOrganisation() {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO organisation (id, name) VALUES (:id, :name)")
        .param("id", id)
        .param("name", "Error Detail Test Organisation %s".formatted(id))
        .update();
    return id;
  }

  private void insertEmployee(UUID organisation, String name, long weeklySeconds) {
    jdbc.sql(
            """
            INSERT INTO employee (id, organisation_id, name, weekly_contracted_seconds)
            VALUES (:id, :organisation, :name, :seconds)
            """)
        .param("id", UUID.randomUUID())
        .param("organisation", organisation)
        .param("name", name)
        .param("seconds", weeklySeconds)
        .update();
  }

  /** The message of the exception and of every cause beneath it, absent messages left out. */
  private static List<String> messagesOf(Throwable thrown) {
    List<String> messages = new ArrayList<>();
    for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
      if (cause.getMessage() != null) {
        messages.add(cause.getMessage());
      }
    }
    return messages;
  }
}
