package io.github.naimelijah.hrsched.workforce.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import org.junit.jupiter.api.Test;

/** Specification of the one invariant the workforce context currently enforces. */
class EmployeeTest {

  @Test
  void GivenANonBlankName_WhenConstructingAnEmployee_ThenItIsAccepted() {
    Employee employee = new Employee(EmployeeId.newId(), "Ada Lovelace");

    assertThat(employee.name()).isEqualTo("Ada Lovelace");
  }

  @Test
  void GivenAWhitespaceOnlyName_WhenConstructingAnEmployee_ThenItIsRejected() {
    assertThatThrownBy(() -> new Employee(EmployeeId.newId(), "   "))
        .isInstanceOf(InvalidEmployeeNameException.class)
        .hasMessageContaining("not blank");
  }

  @Test
  void GivenAnEmptyName_WhenConstructingAnEmployee_ThenItIsRejected() {
    assertThatThrownBy(() -> new Employee(EmployeeId.newId(), ""))
        .isInstanceOf(InvalidEmployeeNameException.class);
  }

  @Test
  void GivenANullName_WhenConstructingAnEmployee_ThenItIsRejected() {
    assertThatThrownBy(() -> new Employee(EmployeeId.newId(), null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenANullIdentity_WhenConstructingAnEmployee_ThenItIsRejected() {
    assertThatThrownBy(() -> new Employee(null, "Ada Lovelace"))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenTwoEmployeesSharingAName_WhenComparingThem_ThenIdentityDecides() {
    EmployeeId id = EmployeeId.newId();

    assertThat(new Employee(id, "Ada Lovelace")).isEqualTo(new Employee(id, "Ada L."));
    assertThat(new Employee(EmployeeId.newId(), "Ada Lovelace"))
        .isNotEqualTo(new Employee(EmployeeId.newId(), "Ada Lovelace"));
  }
}
