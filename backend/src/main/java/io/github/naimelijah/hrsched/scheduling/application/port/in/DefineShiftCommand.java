package io.github.naimelijah.hrsched.scheduling.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.util.Objects;
import java.util.Set;

/**
 * @param organisation whose shift
 * @param period when it runs
 * @param requiredHeadcount how many people it needs
 * @param requiredQualifications what each of them must hold
 */
public record DefineShiftCommand(
    OrganisationId organisation,
    TimeRange period,
    int requiredHeadcount,
    Set<QualificationId> requiredQualifications) {

  public DefineShiftCommand {
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(period, "period must not be null");
    requiredQualifications = Set.copyOf(requiredQualifications);
  }
}
