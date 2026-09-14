import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { dayLabel, timeLabel } from '../components/localDates';
import { ActingAsProvider, useActingAs } from '../identity/ActingAs';
import { callsTo, routeTo, type Answer } from '../test-support/routeTo';
import { RostersPage } from './RostersPage';

/**
 * The publication refusal is the behaviour this product is sold on, so it is asserted on directly:
 * a roster that breaks the rules must show every rule it broke, and must not be dismissible as a
 * generic error.
 *
 * Editing a draft by hand (F2) is asserted through what a scheduler can reach: the picker, the
 * buttons, and what the board shows afterwards. The picker's contents are asserted too, because
 * offering only the people the UI believes are eligible would be the UI enforcing a rule (ADR-025).
 */

const SPAN = { from: '2026-10-05T00:00:00.000Z', to: '2026-10-12T00:00:00.000Z' };

const ROSTER = {
  id: 'roster-1',
  // Deliberately outside SPAN: a roster must resolve against its own period, not the span the
  // scheduler happens to be looking at.
  start: '2026-11-02T00:00:00Z',
  end: '2026-11-09T00:00:00Z',
  status: 'DRAFT',
  assignments: [{ shift: 'shift-1', employee: 'emp-1' }],
  validatedAgainstConstraintVersion: null,
};

const SHIFT = {
  id: 'shift-1',
  start: '2026-11-03T08:00:00Z',
  end: '2026-11-03T16:00:00Z',
  requiredHeadcount: 1,
  requiredQualifications: [],
};

const ADA = {
  id: 'emp-1',
  name: 'Ada',
  qualifications: [],
  unavailability: [],
  weeklyContractedMinutes: null,
};

const GRACE = { ...ADA, id: 'emp-2', name: 'Grace' };

const FORKLIFT = { id: 'q-1', name: 'Forklift' };

/** The draft as the server returns it once Grace has been put on the shift beside Ada. */
const WITH_GRACE = {
  ...ROSTER,
  assignments: [...ROSTER.assignments, { shift: 'shift-1', employee: 'emp-2' }],
};

/** A draft with Ada on its one shift, and Grace free to be assigned. */
function aDraft(overrides: Record<string, Answer> = {}) {
  return routeTo({
    '/api/rosters': { status: 200, body: [ROSTER] },
    '/api/rosters/roster-1': { status: 200, body: ROSTER },
    '/api/shifts': { status: 200, body: [SHIFT] },
    '/api/employees': { status: 200, body: [ADA, GRACE] },
    '/api/qualifications': { status: 200, body: [FORKLIFT] },
    ...overrides,
  });
}

function renderPage() {
  return render(
    <ActingAsProvider>
      <RostersPage span={SPAN} />
    </ActingAsProvider>,
  );
}

/** Changes the acting role from inside the provider, as the identity bar does. */
function BecomeAnEmployee() {
  const { setRole } = useActingAs();
  return (
    <button type="button" onClick={() => setRole('EMPLOYEE')}>
      Become an employee
    </button>
  );
}

async function openBoard(): Promise<HTMLElement> {
  await userEvent.click(await screen.findByRole('button', { name: 'Open' }));
  return screen.findByRole('region', { name: /^Roster/ });
}

async function assignGrace(board: HTMLElement) {
  const picker = await within(board).findByLabelText(/^Who to assign/);
  await waitFor(() => expect(within(picker).getAllByRole('option')).toHaveLength(3));
  await userEvent.selectOptions(picker, 'emp-2');
  await userEvent.click(within(board).getByRole('button', { name: /^Assign to/ }));
}

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
});

describe('the rosters page', () => {
  it('GivenARosterOutsideTheSelectedSpan_WhenOpened_ThenItsShiftsStillResolve', async () => {
    const stub = aDraft();
    renderPage();

    const board = await openBoard();

    // Joined client-side against the roster's own period, not the span on screen.
    expect(
      await within(board).findByRole('button', { name: /^Remove Ada from/ }),
    ).toBeInTheDocument();
    expect(board).not.toHaveTextContent('shift-1');
    const shiftCalls = stub.mock.calls
      .map((call) => String(call[0]))
      .filter((path) => path.startsWith('/api/shifts'));
    expect(shiftCalls.at(-1)).toContain(encodeURIComponent(ROSTER.start));
  });

  it('GivenARosterThatBreaksTheRules_WhenPublishing_ThenEveryViolationIsShown', async () => {
    routeTo({
      '/api/rosters/roster-1/publication': {
        status: 422,
        body: {
          detail: 'The roster breaks 1 working-time rule(s)',
          violations: [
            {
              employee: 'emp-1',
              constraint: 'maximum daily working time',
              detail: 'PT8H worked on 2026-10-05 exceeds the maximum of PT1H',
            },
          ],
        },
      },
      '/api/rosters': { status: 200, body: [ROSTER] },
      '/api/shifts': { status: 200, body: [SHIFT] },
      '/api/employees': { status: 200, body: [ADA] },
    });
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Publish' }));

    const alert = await screen.findByRole('alert');
    // Resolved to a name: the reason the 422 payload is structured at all.
    expect(alert).toHaveTextContent('Ada');
    expect(alert).toHaveTextContent('maximum daily working time');
    expect(alert).toHaveTextContent('exceeds the maximum of PT1H');
    expect(alert).toHaveTextContent('There is no override');
  });

  it('GivenTheRosterTable_WhenRendered_ThenEveryColumnHasAHeader', async () => {
    aDraft();
    renderPage();

    await screen.findByRole('button', { name: 'Open' });

    expect(screen.getByRole('columnheader', { name: 'Actions' })).toBeInTheDocument();
  });

  it('GivenAnEmptyDraft_WhenOpened_ThenTheGapIsExplainedRatherThanLeftBlank', async () => {
    const empty = { ...ROSTER, assignments: [] };
    aDraft({
      '/api/rosters': { status: 200, body: [empty] },
      '/api/rosters/roster-1': { status: 200, body: empty },
      '/api/shifts': { status: 200, body: [] },
    });
    renderPage();

    const board = await openBoard();

    expect(
      await within(board).findByText(/left short rather than filled illegally/),
    ).toBeInTheDocument();
  });

  it('GivenARoleThatMayNotRoster_WhenGenerating_ThenTheRefusalIsShown', async () => {
    routeTo({
      '/api/rosters': { status: 200, body: [] },
      '/api/shifts': { status: 200, body: [] },
      '/api/employees': { status: 200, body: [] },
    });
    renderPage();
    routeTo({
      '/api/rosters': { status: 403, body: { detail: 'Your role does not permit this.' } },
      '/api/shifts': { status: 200, body: [] },
      '/api/employees': { status: 200, body: [] },
    });

    await userEvent.click(await screen.findByRole('button', { name: /Generate a draft/ }));

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Your role may not do this'),
    );
  });
});

describe('the board of a draft', () => {
  it('GivenADraftOpened_WhenTheBoardRenders_ThenShiftsAreGroupedByLocalDayWithTheirTimes', async () => {
    // A whole day later, so the two fall on different local days wherever the test runs.
    const nextDay = {
      ...SHIFT,
      id: 'shift-2',
      start: '2026-11-04T08:00:00Z',
      end: '2026-11-04T16:00:00Z',
    };
    aDraft({ '/api/shifts': { status: 200, body: [nextDay, SHIFT] } });
    renderPage();

    const board = await openBoard();

    await waitFor(() =>
      expect(
        within(board)
          .getAllByRole('heading', { level: 4 })
          .map((day) => day.textContent),
      ).toEqual([dayLabel(SHIFT.start), dayLabel(nextDay.start)]),
    );
    expect(board).toHaveTextContent(`${timeLabel(SHIFT.start)} to ${timeLabel(SHIFT.end)}`);
  });

  it('GivenAShiftShortOfPeople_WhenShown_ThenTheShortfallIsStatedInWords', async () => {
    aDraft({ '/api/shifts': { status: 200, body: [{ ...SHIFT, requiredHeadcount: 3 }] } });
    renderPage();

    const board = await openBoard();

    expect(await within(board).findByText(/1 of 3 assigned/)).toHaveTextContent('2 short');
  });

  it('GivenThePicker_WhenOpened_ThenEveryEmployeeIsOfferedRegardless', async () => {
    // Nobody holds Forklift and Ada is already on the shift: the backend decides, not the picker.
    aDraft({
      '/api/shifts': { status: 200, body: [{ ...SHIFT, requiredQualifications: ['q-1'] }] },
    });
    renderPage();

    const board = await openBoard();

    const picker = await within(board).findByLabelText(/^Who to assign/);
    await waitFor(() =>
      expect(
        within(picker)
          .getAllByRole('option')
          .map((option) => option.textContent),
      ).toEqual(['choose…', 'Ada', 'Grace']),
    );
    expect(board).toHaveTextContent('Requires Forklift');
  });

  it('GivenADraft_WhenAssigningSomebody_ThenShiftAndEmployeeAreSentAndTheReturnedRosterIsShown', async () => {
    const withGrace = {
      ...ROSTER,
      assignments: [...ROSTER.assignments, { shift: 'shift-1', employee: 'emp-2' }],
    };
    const stub = aDraft({
      'POST /api/rosters/roster-1/assignments': { status: 200, body: withGrace },
    });
    renderPage();
    const board = await openBoard();

    await assignGrace(board);

    expect(
      await within(board).findByRole('button', { name: /^Remove Grace from/ }),
    ).toBeInTheDocument();
    const [posted] = callsTo(stub, 'POST', '/api/rosters/roster-1/assignments');
    expect(JSON.parse(String(posted?.[1]?.body))).toEqual({
      shift: 'shift-1',
      employee: 'emp-2',
    });
  });

  it('GivenADraft_WhenRemovingSomebody_ThenADeleteNamesRosterShiftAndEmployee', async () => {
    const stub = aDraft({
      'DELETE /api/rosters/roster-1/assignments/shift-1/emp-1': {
        status: 200,
        body: { ...ROSTER, assignments: [] },
      },
    });
    renderPage();
    const board = await openBoard();

    await userEvent.click(await within(board).findByRole('button', { name: /^Remove Ada from/ }));

    await waitFor(() =>
      expect(
        within(board).queryByRole('button', { name: /^Remove Ada from/ }),
      ).not.toBeInTheDocument(),
    );
    expect(callsTo(stub, 'DELETE', '/api/rosters/roster-1/assignments/shift-1/emp-1')).toHaveLength(
      1,
    );
  });

  it('GivenAnAssignmentRefused_WhenAssigning_ThenTheServersReasonIsShown', async () => {
    aDraft({
      'POST /api/rosters/roster-1/assignments': {
        status: 422,
        body: { detail: 'Grace does not hold every qualification the shift requires' },
      },
    });
    renderPage();
    const board = await openBoard();

    await assignGrace(board);

    const alert = await within(board).findByRole('alert');
    expect(alert).toHaveTextContent('Refused.');
    expect(alert).toHaveTextContent('does not hold every qualification the shift requires');
  });

  /**
   * Two changes racing each other could show whichever answer arrived last, not the latest roster,
   * so the board takes one change at a time.
   */
  it('GivenAChangeInFlight_WhenTheBoardIsUsedAgain_ThenItsControlsWaitForTheFirstAnswer', async () => {
    const routed = aDraft({
      'POST /api/rosters/roster-1/assignments': { status: 200, body: WITH_GRACE },
    });
    let release!: () => void;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    vi.stubGlobal(
      'fetch',
      vi.fn((path: string, init?: RequestInit) =>
        (init?.method ?? 'GET') === 'GET'
          ? routed(path, init)
          : held.then(() => routed(path, init)),
      ),
    );
    renderPage();
    const board = await openBoard();

    await assignGrace(board);

    expect(within(board).getByRole('button', { name: /^Remove Ada from/ })).toBeDisabled();
    expect(within(board).getByRole('button', { name: /^Assign to/ })).toBeDisabled();
    release();
    expect(await within(board).findByRole('button', { name: /^Remove Grace from/ })).toBeEnabled();
  });

  /** Left selected, the picker would send the same person again on the next press. */
  it('GivenASuccessfulAssignment_WhenItIsShown_ThenThePickerIsReadyForTheNextOne', async () => {
    aDraft({ 'POST /api/rosters/roster-1/assignments': { status: 200, body: WITH_GRACE } });
    renderPage();
    const board = await openBoard();

    await assignGrace(board);

    await within(board).findByRole('button', { name: /^Remove Grace from/ });
    expect(within(board).getByLabelText(/^Who to assign/)).toHaveValue('');
  });

  /**
   * An EMPLOYEE may not read a whole roster (R12). A board that kept what it last showed after the
   * identity changed would put every assignee, and the controls, in front of exactly that role.
   */
  it('GivenAnEditedBoard_WhenTheActingIdentityChanges_ThenWhatItShowedIsNotKept', async () => {
    aDraft({ 'POST /api/rosters/roster-1/assignments': { status: 200, body: WITH_GRACE } });
    render(
      <ActingAsProvider>
        <BecomeAnEmployee />
        <RostersPage span={SPAN} />
      </ActingAsProvider>,
    );
    const board = await openBoard();
    await assignGrace(board);
    await within(board).findByRole('button', { name: /^Remove Grace from/ });
    const refused = { status: 403, body: { detail: 'Your role does not permit this.' } };
    aDraft({
      '/api/rosters': refused,
      '/api/rosters/roster-1': refused,
      '/api/employees': refused,
    });

    await userEvent.click(screen.getByRole('button', { name: 'Become an employee' }));

    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /^Remove/ })).not.toBeInTheDocument(),
    );
    expect(screen.queryByLabelText(/^Who to assign/)).not.toBeInTheDocument();
  });

  /** A Scheduler about to publish must see who is on the draft even when its shifts will not load. */
  it('GivenShiftsThatCannotBeLoaded_WhenOpened_ThenWhoIsAssignedIsStillShown', async () => {
    aDraft({
      '/api/shifts': { status: 500, body: { detail: 'The shift store is unavailable.' } },
    });
    renderPage();

    const board = await openBoard();

    expect(await within(board).findByRole('alert')).toHaveTextContent(
      'The shift store is unavailable.',
    );
    expect(await within(board).findByText('Ada')).toBeInTheDocument();
  });

  it('GivenAPublishedRoster_WhenOpened_ThenThereAreNoEditingControls', async () => {
    const published = { ...ROSTER, status: 'PUBLISHED', validatedAgainstConstraintVersion: 1 };
    aDraft({
      '/api/rosters': { status: 200, body: [published] },
      '/api/rosters/roster-1': { status: 200, body: published },
    });
    renderPage();

    const board = await openBoard();

    expect(await within(board).findByText('Ada')).toBeInTheDocument();
    expect(within(board).queryByRole('button', { name: /^Remove/ })).not.toBeInTheDocument();
    expect(within(board).queryByLabelText(/^Who to assign/)).not.toBeInTheDocument();
  });

  /**
   * The list reloads after every change on the board. When the board was derived from the list it
   * vanished each time and took the keyboard focus with it; this pins that it no longer does.
   */
  it('GivenTheListReloads_WhenAnAssignmentIsMade_ThenTheBoardStaysMounted', async () => {
    const withGrace = {
      ...ROSTER,
      assignments: [...ROSTER.assignments, { shift: 'shift-1', employee: 'emp-2' }],
    };
    const stub = aDraft({
      'POST /api/rosters/roster-1/assignments': { status: 200, body: withGrace },
    });
    renderPage();
    const board = await openBoard();
    const listedBefore = callsTo(stub, 'GET', '/api/rosters').length;

    await assignGrace(board);

    await waitFor(() =>
      expect(callsTo(stub, 'GET', '/api/rosters').length).toBeGreaterThan(listedBefore),
    );
    expect(board).toBeInTheDocument();
    expect(within(board).getByRole('button', { name: /^Remove Grace from/ })).toBeInTheDocument();
  });
});
