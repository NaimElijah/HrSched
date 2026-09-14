import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/problem';
import { Busy, Failure } from './Feedback';

/**
 * `Failure` is a branch table rather than markup, and two of its branches were unreachable from any
 * page test: the 404 and the fallback for something that is not an `ApiError` at all.
 *
 * The 404 branch carries a design decision to the user. A cross-tenant attempt answers 404 rather
 * than 403 so a response never confirms that a resource exists in somebody else's organisation,
 * which means "not found" here genuinely cannot distinguish "no such thing" from "not yours". The
 * copy has to say so, or a scheduler reads it as a bug.
 */

function ruleBreach(violations: { employee: string; constraint: string; detail: string }[]) {
  return new ApiError(422, 'Refused', violations);
}

describe('when there is nothing wrong', () => {
  it.each([null, undefined])('GivenNoError_WhenRendering_ThenNothingIsShown (%j)', (error) => {
    const { container } = render(<Failure error={error} />);

    expect(container).toBeEmptyDOMElement();
  });
});

describe('a rule breach', () => {
  it('GivenARuleBreach_WhenRendering_ThenEveryViolationIsListed', () => {
    render(
      <Failure
        error={ruleBreach([
          { employee: 'ada', constraint: 'maximum daily working time', detail: 'worked PT12H' },
          { employee: 'grace', constraint: 'minimum rest between assignments', detail: 'PT4H' },
        ])}
      />,
    );

    expect(screen.getAllByRole('listitem')).toHaveLength(2);
    expect(screen.getByText(/maximum daily working time/)).toBeInTheDocument();
    expect(screen.getByText(/minimum rest between assignments/)).toBeInTheDocument();
  });

  it('GivenARuleBreach_WhenRendering_ThenItSaysThereIsNoOverride', () => {
    render(
      <Failure error={ruleBreach([{ employee: 'ada', constraint: 'a rule', detail: 'x' }])} />,
    );

    expect(screen.getByText(/no override/i)).toBeInTheDocument();
  });

  it('GivenANameResolver_WhenRenderingARuleBreach_ThenTheEmployeeIsNamed', () => {
    render(
      <Failure
        error={ruleBreach([{ employee: 'ada', constraint: 'a rule', detail: 'a detail' }])}
        nameOf={(employee) => (employee === 'ada' ? 'Ada Lovelace' : undefined)}
      />,
    );

    expect(screen.getByText('Ada Lovelace')).toBeInTheDocument();
  });

  it('GivenNoNameResolver_WhenRenderingARuleBreach_ThenTheIdentifierIsShownRatherThanNothing', () => {
    render(
      <Failure error={ruleBreach([{ employee: 'ada', constraint: 'a rule', detail: 'x' }])} />,
    );

    expect(screen.getByText('ada')).toBeInTheDocument();
  });

  it('GivenAResolverThatKnowsNobody_WhenRendering_ThenTheIdentifierIsShown', () => {
    render(
      <Failure
        error={ruleBreach([{ employee: 'ada', constraint: 'a rule', detail: 'x' }])}
        nameOf={() => undefined}
      />,
    );

    expect(screen.getByText('ada')).toBeInTheDocument();
  });
});

describe('a refusal that lists no rules', () => {
  it('GivenAnAssignmentRefusedWithoutViolations_WhenRendering_ThenTheServersReasonIsShownNotTheWorkingTimeRules', () => {
    render(
      <Failure
        error={new ApiError(422, 'Ada does not hold every qualification the shift requires')}
      />,
    );

    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('Refused.');
    expect(alert).toHaveTextContent('does not hold every qualification the shift requires');
    expect(alert).not.toHaveTextContent(/working-time rules/);
    expect(screen.queryByRole('listitem')).not.toBeInTheDocument();
  });

  it('GivenARefusalCarryingATraceId_WhenRendering_ThenItIsShownToBeQuoted', () => {
    const trace = '4bf92f3577b34da6a3ce929d0e0e4736';
    render(<Failure error={new ApiError(422, 'That shift is outside the roster.', [], trace)} />);

    expect(screen.getByRole('alert')).toHaveTextContent(`Reference: ${trace}`);
  });
});

describe('the other refusals', () => {
  it('GivenAForbidden_WhenRendering_ThenTheRoleRefusalIsShown', () => {
    render(<Failure error={new ApiError(403, 'Your role does not permit this.')} />);

    expect(screen.getByRole('alert')).toHaveTextContent('Your role may not do this.');
  });

  it('GivenANotFound_WhenRendering_ThenItSaysItMayExistForSomebodyElse', () => {
    render(<Failure error={new ApiError(404, 'No such resource.')} />);

    expect(screen.getByRole('alert')).toHaveTextContent(
      /may also mean it exists for somebody else/,
    );
  });

  it('GivenAPlainError_WhenRendering_ThenItsMessageIsShown', () => {
    render(<Failure error={new Error('the network went away')} />);

    expect(screen.getByRole('alert')).toHaveTextContent('the network went away');
  });

  it.each([['a string'], [42], [{ odd: true }], [true]])(
    'GivenSomethingThatIsNotAnError_WhenRendering_ThenItIsStringifiedRatherThanCrashing (%j)',
    (thrown) => {
      render(<Failure error={thrown} />);

      expect(screen.getByRole('alert')).toBeInTheDocument();
    },
  );

  it('GivenAnUnexpectedStatus_WhenRendering_ThenTheMessageStillReachesTheUser', () => {
    render(<Failure error={new ApiError(500, 'The server returned 500.')} />);

    expect(screen.getByRole('alert')).toHaveTextContent('The server returned 500.');
  });
});

describe('the trace reference', () => {
  const TRACE = '4bf92f3577b34da6a3ce929d0e0e4736';

  it('GivenAForbiddenCarryingATraceId_WhenRendering_ThenItIsShownToBeQuoted', () => {
    render(<Failure error={new ApiError(403, 'Your role does not permit this.', [], TRACE)} />);

    expect(screen.getByRole('alert')).toHaveTextContent(`Reference: ${TRACE}`);
  });

  it('GivenANotFoundCarryingATraceId_WhenRendering_ThenItIsShown', () => {
    render(<Failure error={new ApiError(404, 'No such resource.', [], TRACE)} />);

    expect(screen.getByRole('alert')).toHaveTextContent(`Reference: ${TRACE}`);
  });

  it('GivenARuleBreachCarryingATraceId_WhenRendering_ThenItIsShownBesideTheViolations', () => {
    render(
      <Failure
        error={
          new ApiError(
            422,
            'Refused',
            [{ employee: 'ada', constraint: 'maximum daily working time', detail: 'PT12H' }],
            TRACE,
          )
        }
      />,
    );

    expect(screen.getAllByRole('listitem')).toHaveLength(1);
    expect(screen.getByRole('alert')).toHaveTextContent(`Reference: ${TRACE}`);
  });

  it('GivenAnUnexpectedStatusCarryingATraceId_WhenRendering_ThenItIsShown', () => {
    render(<Failure error={new ApiError(500, 'The server returned 500.', [], TRACE)} />);

    expect(screen.getByRole('alert')).toHaveTextContent(`Reference: ${TRACE}`);
  });

  it('GivenAFailureWithoutATraceId_WhenRendering_ThenNoReferenceIsInvented', () => {
    render(<Failure error={new ApiError(403, 'Your role does not permit this.')} />);

    expect(screen.queryByText(/Reference:/)).not.toBeInTheDocument();
  });

  /** A render crash never went through the API client, so there is no trace to quote. */
  it('GivenSomethingThatIsNotAnApiError_WhenRendering_ThenNoReferenceIsShown', () => {
    render(<Failure error={new Error('the interface broke')} />);

    expect(screen.getByRole('alert')).toHaveTextContent('the interface broke');
    expect(screen.queryByText(/Reference:/)).not.toBeInTheDocument();
  });
});

/**
 * A live region is announced when its content changes, not when it appears, so it has to be in the
 * page before there is anything to say. Rendering nothing while idle meant "Working…" arrived in a
 * brand-new element that screen readers did not announce.
 */
describe('the working indicator', () => {
  it('GivenARequestInFlight_WhenRendering_ThenItIsAnnouncedAsStatus', () => {
    render(<Busy busy />);

    expect(screen.getByRole('status')).toHaveTextContent('Working…');
  });

  it('GivenNothingInFlight_WhenRendering_ThenTheStatusRegionIsPresentButSilent', () => {
    render(<Busy busy={false} />);

    expect(screen.getByRole('status')).toBeEmptyDOMElement();
  });
});
