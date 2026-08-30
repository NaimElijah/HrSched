package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.Operator;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Specification of the header placeholder that stands in for authentication.
 *
 * <p>The class under test is insecure by design and will be deleted when an identity provider is
 * chosen. It is tested anyway, because until then it is the only thing deciding who the caller is,
 * and a malformed header must become a 400 rather than a 500 or a silently wrong identity.
 *
 * <p>Deliberately duplicated in the other context's web adapter, exactly as the class under test
 * is. Sharing the test would create a dependency between two contexts' adapter packages -- the
 * coupling the production duplication exists to avoid. If the two copies drift apart, two tests say
 * so.
 */
class ActorFromHeadersTest {

  private static final String ORGANISATION = UUID.randomUUID().toString();
  private static final String USER = UUID.randomUUID().toString();

  /** Rows for every header that is required, each paired with the way it can be absent. */
  private static Stream<Arguments> missingRequiredHeaders() {
    return Stream.of(
        Arguments.of("X-Organisation", null, USER, "ADMIN"),
        Arguments.of("X-Organisation", "   ", USER, "ADMIN"),
        Arguments.of("X-User", ORGANISATION, null, "ADMIN"),
        Arguments.of("X-User", ORGANISATION, "   ", "ADMIN"),
        Arguments.of("X-Role", ORGANISATION, USER, null),
        Arguments.of("X-Role", ORGANISATION, USER, "   "));
  }

  @ParameterizedTest
  @MethodSource("missingRequiredHeaders")
  void GivenAMissingHeader_WhenResolvingTheActor_ThenTheMessageNamesThatHeader(
      String missing, String organisation, String user, String role) {
    assertThatThrownBy(() -> ActorFromHeaders.resolve(organisation, user, role, null))
        .isInstanceOf(MalformedRequestException.class)
        .hasMessageContaining(missing);
  }

  @ParameterizedTest
  @ValueSource(strings = {"EMPLOYEE", "SCHEDULER", "ADMIN", "employee", "Admin"})
  void GivenATenantRoleInAnyCase_WhenResolvingTheActor_ThenATenantActorIsReturned(String role) {
    Actor actor = ActorFromHeaders.resolve(ORGANISATION, USER, role, null);

    assertThat(actor)
        .isInstanceOf(TenantActor.class)
        .extracting(resolved -> ((TenantActor) resolved).role())
        .isEqualTo(Role.valueOf(role.toUpperCase(Locale.ROOT)));
  }

  @Test
  void GivenATenantRole_WhenResolvingTheActor_ThenItBelongsToTheOrganisationHeader() {
    Actor actor = ActorFromHeaders.resolve(ORGANISATION, USER, "ADMIN", null);

    assertThat(((TenantActor) actor).organisation())
        .isEqualTo(ActorFromHeaders.organisationOf(ORGANISATION));
  }

  @ParameterizedTest
  @ValueSource(strings = {"OPERATOR", "operator", "Operator"})
  void GivenTheOperatorClaimInAnyCase_WhenResolvingTheActor_ThenAnOperatorIsReturned(String role) {
    assertThat(ActorFromHeaders.resolve(ORGANISATION, USER, role, null))
        .isInstanceOf(Operator.class);
  }

  /**
   * A genuine edge: {@code resolve} returns before it ever parses the organisation, so an operator
   * with an unusable organisation header is rejected only when the controller calls {@link
   * ActorFromHeaders#organisationOf} separately. Both calls are needed; neither is redundant.
   */
  @Test
  void GivenTheOperatorClaim_WhenResolvingTheActor_ThenTheOrganisationHeaderIsNotEvenRead() {
    assertThat(ActorFromHeaders.resolve("not-a-uuid", USER, "OPERATOR", null))
        .isInstanceOf(Operator.class);
  }

  @Test
  void GivenAnEmployeeHeader_WhenResolvingTheActor_ThenItCarriesThatEmployeeLink() {
    String employee = UUID.randomUUID().toString();

    Actor actor = ActorFromHeaders.resolve(ORGANISATION, USER, "EMPLOYEE", employee);

    assertThat(((TenantActor) actor).employee())
        .contains(new EmployeeId(UUID.fromString(employee)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"   ", ""})
  void GivenABlankEmployeeHeader_WhenResolvingTheActor_ThenItCarriesNoLink(String employee) {
    Actor actor = ActorFromHeaders.resolve(ORGANISATION, USER, "EMPLOYEE", employee);

    assertThat(((TenantActor) actor).employee()).isEmpty();
  }

  @Test
  void GivenNoEmployeeHeader_WhenResolvingTheActor_ThenItCarriesNoLink() {
    Actor actor = ActorFromHeaders.resolve(ORGANISATION, USER, "EMPLOYEE", null);

    assertThat(((TenantActor) actor).employee()).isEmpty();
  }

  @Test
  void GivenAnEmployeeHeaderThatIsNotAUuid_WhenResolvingTheActor_ThenItIsMalformed() {
    assertThatThrownBy(() -> ActorFromHeaders.resolve(ORGANISATION, USER, "EMPLOYEE", "not-a-uuid"))
        .isInstanceOf(MalformedRequestException.class)
        .hasMessageContaining("X-Employee");
  }

  @Test
  void GivenPaddedHeaders_WhenResolvingTheActor_ThenTheyAreTrimmed() {
    Actor actor =
        ActorFromHeaders.resolve("  " + ORGANISATION + "  ", "  " + USER + " ", " ADMIN ", null);

    assertThat(((TenantActor) actor).role()).isEqualTo(Role.ADMIN);
  }

  /**
   * The message has to stay useful as roles are added, so it is asserted against {@link Role}
   * itself rather than against a copied string.
   */
  @Test
  void GivenARoleNobodyDefined_WhenResolvingTheActor_ThenTheMessageNamesTheLegalValues() {
    assertThatThrownBy(() -> ActorFromHeaders.resolve(ORGANISATION, USER, "SUPERVISOR", null))
        .isInstanceOf(MalformedRequestException.class)
        .hasMessageContaining(Arrays.toString(Role.values()))
        .hasMessageContaining("OPERATOR");
  }

  @Test
  void GivenAUserHeaderThatIsNotAUuid_WhenResolvingTheActor_ThenItIsMalformed() {
    assertThatThrownBy(() -> ActorFromHeaders.resolve(ORGANISATION, "not-a-uuid", "ADMIN", null))
        .isInstanceOf(MalformedRequestException.class)
        .hasMessageContaining("X-User");
  }

  @Test
  void GivenAnOrganisationHeaderThatIsNotAUuid_WhenReadingIt_ThenItIsMalformed() {
    assertThatThrownBy(() -> ActorFromHeaders.organisationOf("not-a-uuid"))
        .isInstanceOf(MalformedRequestException.class)
        .hasMessageContaining("X-Organisation");
  }

  @Test
  void GivenNoOrganisationHeader_WhenReadingIt_ThenItIsMalformed() {
    assertThatThrownBy(() -> ActorFromHeaders.organisationOf(null))
        .isInstanceOf(MalformedRequestException.class)
        .hasMessageContaining("X-Organisation");
  }

  @Test
  void GivenAnOrganisationHeader_WhenReadingIt_ThenItIsThatOrganisation() {
    assertThat(ActorFromHeaders.organisationOf(ORGANISATION).value())
        .isEqualTo(UUID.fromString(ORGANISATION));
  }
}
