import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Session } from '../api/session';
import type { Employee } from '../api/workforce';
import { dayLabel, timeLabel } from '../components/localDates';
import { A_TENANT, SignedIn } from '../test-support/session';
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
  zone: 'UTC',
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

/**
 * What checking a draft answers when it breaks no rule, leaves no shift short, and is scored by an
 * organisation that weighs nothing.
 */
const NOTHING_WRONG = {
  roster: 'roster-1',
  constraintVersion: 1,
  violations: [],
  shortfalls: [],
  objectives: [
    { objective: 'FAIRNESS', measure: 0, weight: null },
    { objective: 'PREFERENCES', measure: 0, weight: null },
    { objective: 'DISRUPTION', measure: 0, weight: null },
  ],
  weightedTotal: 0,
};

/**
 * A draft with Ada on its one shift, and Grace free to be assigned, and a check that finds nothing
 * wrong with it.
 */
function aDraft(overrides: Record<string, Answer> = {}) {
  return routeTo({
    '/api/rosters': { status: 200, body: [ROSTER] },
    '/api/rosters/roster-1': { status: 200, body: ROSTER },
    '/api/rosters/roster-1/assessment': { status: 200, body: NOTHING_WRONG },
    '/api/shifts': { status: 200, body: [SHIFT] },
    '/api/qualifications': { status: 200, body: [FORKLIFT] },
    ...overrides,
  });
}

/** The people come from the shell, as App passes them, so the page itself never asks. */
function renderPage(people: readonly Employee[] = [ADA, GRACE], session: Session = A_TENANT) {
  return render(
    <SignedIn session={session}>
      <AsTheShellHoldsIt people={people} />
    </SignedIn>,
  );
}

/** The page, with the generation it waits for held above it, as App holds it. */
function AsTheShellHoldsIt({ people }: { people: readonly Employee[] }) {
  const [waitingFor, setWaitingFor] = useState<string | null>(null);
  return (
    <RostersPage span={SPAN} people={people} waitingFor={waitingFor} onWaitingFor={setWaitingFor} />
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
      'POST /api/rosters/roster-1/publication': {
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
    });
    renderPage([ADA]);

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

  /**
   * The shell already holds the people, and already knows which refusal of them to keep quiet about.
   * Asking again doubled the request and showed an EMPLOYEE the 403 the shell deliberately swallows.
   */
  it('GivenPeopleFromTheShell_WhenTheBoardIsOpened_ThenTheyAreNotAskedForAgain', async () => {
    const stub = aDraft();
    renderPage();

    const board = await openBoard();

    await within(board).findByRole('button', { name: /^Remove Ada from/ });
    expect(callsTo(stub, 'GET', '/api/employees')).toHaveLength(0);
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
      await within(board).findByText(/No shift falls in this roster's period/),
    ).toBeInTheDocument();
  });

  it('GivenARoleThatMayNotRoster_WhenGenerating_ThenTheRefusalIsShown', async () => {
    routeTo({
      '/api/rosters': { status: 200, body: [] },
      '/api/shifts': { status: 200, body: [] },
      '/api/employees': { status: 200, body: [] },
      'POST /api/rosters/generations': {
        status: 403,
        body: { detail: 'Your role does not permit this.' },
      },
    });
    renderPage([ADA, GRACE], { ...A_TENANT, role: 'EMPLOYEE', employee: ADA.id });

    await userEvent.click(await screen.findByRole('button', { name: /Generate a draft/ }));

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Your role may not do this'),
    );
  });

  /**
   * Q9: a published roster keeps the version that approved it. The number alone tells a reader
   * nothing once the rules have been revised, so it opens the rules as they were under it.
   */
  it('GivenAPublishedRoster_WhenItsVersionIsOpened_ThenTheRulesThatApprovedItAreShown', async () => {
    const published = { ...ROSTER, status: 'PUBLISHED', validatedAgainstConstraintVersion: 2 };
    aDraft({
      '/api/rosters': { status: 200, body: [published] },
      // Invented, and recognisable. The current rules may be anything; only version 2 is asked for.
      '/api/constraints/2': {
        status: 200,
        body: {
          version: 2,
          constraints: ['maximum daily working time'],
          minimumRestMinutes: null,
          maximumDailyMinutes: 601,
          maximumWeeklyMinutes: null,
          maximumConsecutiveDays: null,
        },
      },
    });
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Version 2' }));

    const rules = await screen.findByRole('region', { name: 'Rules of version 2' });
    expect(await within(rules).findByText('601')).toBeInTheDocument();
    expect(rules).toHaveTextContent('Maximum daily working time (minutes)');
    expect(rules).not.toHaveTextContent('Minimum rest');
  });

  it('GivenAVersionThatEnforcedNothing_WhenOpened_ThenItSaysSoRatherThanShowingNothing', async () => {
    const published = { ...ROSTER, status: 'PUBLISHED', validatedAgainstConstraintVersion: 1 };
    aDraft({
      '/api/rosters': { status: 200, body: [published] },
      '/api/constraints/1': {
        status: 200,
        body: {
          version: 1,
          constraints: [],
          minimumRestMinutes: null,
          maximumDailyMinutes: null,
          maximumWeeklyMinutes: null,
          maximumConsecutiveDays: null,
        },
      },
    });
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Version 1' }));

    const rules = await screen.findByRole('region', { name: 'Rules of version 1' });
    expect(
      await within(rules).findByText('No working-time rule was enforced under this version.'),
    ).toBeInTheDocument();
  });

  /**
   * What a version enforced is the server's to say. A rule this page has no label for yet must
   * still be named rather than read as "nothing was enforced", which would be a false account of
   * what a roster was judged against.
   */
  it('GivenARuleThePageHasNoLabelFor_WhenItsVersionIsOpened_ThenTheRuleIsStillNamed', async () => {
    const published = { ...ROSTER, status: 'PUBLISHED', validatedAgainstConstraintVersion: 3 };
    aDraft({
      '/api/rosters': { status: 200, body: [published] },
      '/api/constraints/3': {
        status: 200,
        body: {
          version: 3,
          constraints: ['a rule added after this page was written'],
          minimumRestMinutes: null,
          maximumDailyMinutes: null,
          maximumWeeklyMinutes: null,
          maximumConsecutiveDays: null,
        },
      },
    });
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Version 3' }));

    const rules = await screen.findByRole('region', { name: 'Rules of version 3' });
    expect(
      await within(rules).findByText('a rule added after this page was written'),
    ).toBeInTheDocument();
    expect(rules).not.toHaveTextContent('No working-time rule was enforced');
  });

  it('GivenTheRulesCannotBeRead_WhenAVersionIsOpened_ThenTheFailureIsShown', async () => {
    const published = { ...ROSTER, status: 'PUBLISHED', validatedAgainstConstraintVersion: 2 };
    aDraft({
      '/api/rosters': { status: 200, body: [published] },
      '/api/constraints/2': { status: 500, body: { detail: 'The rule store is unavailable.' } },
    });
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Version 2' }));

    const rules = await screen.findByRole('region', { name: 'Rules of version 2' });
    expect(await within(rules).findByRole('alert')).toHaveTextContent(
      'The rule store is unavailable.',
    );
  });

  it('GivenADraft_WhenListed_ThenItOffersNoRulesToOpen', async () => {
    aDraft();
    renderPage();

    expect(await screen.findByRole('button', { name: 'Open' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Version/ })).not.toBeInTheDocument();
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
    // A draft's board also carries its check, under a heading of the same level; the days are the
    // rest of them.
    const check = await theCheck(board);

    await waitFor(() =>
      expect(
        within(board)
          .getAllByRole('heading', { level: 4 })
          .filter((heading) => !check.contains(heading))
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

  /** Since a Scheduler can empty a draft by hand, generation is not the only way it gets empty. */
  it('GivenTheLastAssignmentRemovedByHand_WhenTheEmptyDraftIsShown_ThenGenerationIsNotBlamed', async () => {
    aDraft({
      'DELETE /api/rosters/roster-1/assignments/shift-1/emp-1': {
        status: 200,
        body: { ...ROSTER, assignments: [] },
      },
    });
    renderPage();
    const board = await openBoard();

    await userEvent.click(await within(board).findByRole('button', { name: /^Remove Ada from/ }));

    expect(await within(board).findByText(/0 of 1 assigned/)).toBeInTheDocument();
    expect(board).not.toHaveTextContent(/nobody eligible/i);
    expect(board).not.toHaveTextContent(/left short rather than filled illegally/i);
    // The one thing the board may still say about an empty draft, and here the shift is listed.
    expect(board).not.toHaveTextContent(/No shift falls/);
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
  it('GivenAnEditedBoard_WhenTheSessionChanges_ThenWhatItShowedIsNotKept', async () => {
    aDraft({ 'POST /api/rosters/roster-1/assignments': { status: 200, body: WITH_GRACE } });
    const { rerender } = render(
      <SignedIn session={A_TENANT}>
        <AsTheShellHoldsIt people={[ADA, GRACE]} />
      </SignedIn>,
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

    rerender(
      <SignedIn
        session={{
          ...A_TENANT,
          userId: '99999999-9999-4999-8999-999999999999',
          role: 'EMPLOYEE',
        }}
      >
        <AsTheShellHoldsIt people={[ADA, GRACE]} />
      </SignedIn>,
    );

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

  /** A listing that failed says nothing about the period, so the board may not either. */
  it('GivenAnEmptyDraftWhoseShiftsCannotBeLoaded_WhenOpened_ThenItDoesNotClaimThereAreNone', async () => {
    const empty = { ...ROSTER, assignments: [] };
    aDraft({
      '/api/rosters': { status: 200, body: [empty] },
      '/api/rosters/roster-1': { status: 200, body: empty },
      '/api/shifts': { status: 500, body: { detail: 'The shift store is unavailable.' } },
    });
    renderPage();

    const board = await openBoard();

    expect(await within(board).findByRole('alert')).toHaveTextContent(
      'The shift store is unavailable.',
    );
    expect(board).not.toHaveTextContent(/No shift falls/);
  });

  /**
   * Somebody published the draft while this board was open. The assignment is refused as a
   * conflict, and the board then shows the roster as it now is — published, with nothing left to
   * edit — rather than leaving a draft's controls on a roster that is no longer one (ADR-041).
   */
  it('GivenADraftPublishedMeanwhile_WhenAssigning_ThenTheBoardShowsItPublished', async () => {
    aDraft();
    renderPage();
    const board = await openBoard();
    const published = { ...ROSTER, status: 'PUBLISHED', validatedAgainstConstraintVersion: 1 };
    aDraft({
      '/api/rosters': { status: 200, body: [published] },
      '/api/rosters/roster-1': { status: 200, body: published },
      'POST /api/rosters/roster-1/assignments': {
        status: 409,
        body: {
          title: 'Roster status forbids this',
          detail: 'Roster roster-1 is PUBLISHED: a roster can only be changed while a draft',
        },
      },
    });

    await assignGrace(board);

    expect(
      await within(board).findByRole('heading', { name: /^Roster published/ }),
    ).toBeInTheDocument();
    expect(within(board).queryByLabelText(/^Who to assign/)).not.toBeInTheDocument();
    expect(within(board).getByRole('alert')).toHaveTextContent(
      'a roster can only be changed while a draft',
    );
  });

  // --- #205: a draft is checked against every rule as it is edited, without publishing it -------

  function theCheck(board: HTMLElement): Promise<HTMLElement> {
    return within(board).findByRole('region', { name: 'Checked against the rules' });
  }

  it('GivenADraftBreakingARule_WhenItsBoardOpens_ThenTheViolationIsShownWithoutPublishing', async () => {
    const stub = aDraft({
      '/api/rosters/roster-1/assessment': {
        status: 200,
        body: {
          ...NOTHING_WRONG,
          violations: [
            {
              employee: 'emp-1',
              constraint: 'declared unavailability',
              detail: 'shift shift-1 runs through time they declared they cannot work',
            },
          ],
        },
      },
    });
    renderPage();

    const check = await theCheck(await openBoard());

    // Resolved to a name, as a publication refusal is: the two are shown alike on purpose.
    expect(await within(check).findByText('Ada')).toBeInTheDocument();
    expect(check).toHaveTextContent('declared unavailability');
    expect(callsTo(stub, 'POST', '/api/rosters/roster-1/publication')).toHaveLength(0);
  });

  it('GivenACompliantDraft_WhenItsBoardOpens_ThenItSaysNoRuleIsBroken', async () => {
    aDraft();
    renderPage();

    const check = await theCheck(await openBoard());

    expect(await within(check).findByText('No rule is broken.')).toBeInTheDocument();
    expect(check).toHaveTextContent('Every shift has the people it needs.');
    // It is advice: what publication would say a moment ago, not a promise about what it will say.
    expect(check).toHaveTextContent(/Publication still decides/);
  });

  it('GivenAChangeStored_WhenTheServerAnswers_ThenTheDraftIsCheckedAgain', async () => {
    const stub = aDraft({
      'POST /api/rosters/roster-1/assignments': { status: 200, body: WITH_GRACE },
    });
    renderPage();
    const board = await openBoard();
    await within(await theCheck(board)).findByText('No rule is broken.');
    const checkedBefore = callsTo(stub, 'GET', '/api/rosters/roster-1/assessment').length;

    await assignGrace(board);

    await waitFor(() =>
      expect(callsTo(stub, 'GET', '/api/rosters/roster-1/assessment').length).toBeGreaterThan(
        checkedBefore,
      ),
    );
  });

  /**
   * The check after an edit asks the same question again, so its last answer stays on screen until
   * the new one arrives, as every other re-read here does. Dropping it would blank the section
   * after every assignment, and take away what the Scheduler was fixing while they fixed it.
   */
  it('GivenARecheckInFlight_WhenTheDraftIsEdited_ThenTheLastAnswerStaysUntilTheNewOneArrives', async () => {
    const routed = aDraft({
      '/api/rosters/roster-1/assessment': {
        status: 200,
        body: {
          ...NOTHING_WRONG,
          violations: [
            {
              employee: 'emp-1',
              constraint: 'declared unavailability',
              detail: 'shift shift-1 runs through time they declared they cannot work',
            },
          ],
        },
      },
      'POST /api/rosters/roster-1/assignments': { status: 200, body: WITH_GRACE },
    });
    renderPage();
    const board = await openBoard();
    const check = await theCheck(board);
    await within(check).findByText('Ada');
    let release!: () => void;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    vi.stubGlobal(
      'fetch',
      vi.fn((path: string, init?: RequestInit) =>
        path.endsWith('/assessment') ? held.then(() => routed(path, init)) : routed(path, init),
      ),
    );

    await assignGrace(board);
    await within(board).findByRole('button', { name: /^Remove Grace from/ });

    expect(within(check).getByText('Ada')).toBeInTheDocument();
    expect(check).toHaveTextContent('declared unavailability');
    release();
  });

  it('GivenShortShifts_WhenTheDraftIsChecked_ThenTheShortfallIsSummarised', async () => {
    aDraft({
      '/api/rosters/roster-1/assessment': {
        status: 200,
        body: {
          ...NOTHING_WRONG,
          shortfalls: [{ shift: 'shift-1', required: 2, assigned: 1, shortBy: 1 }],
        },
      },
    });
    renderPage();

    const check = await theCheck(await openBoard());

    expect(await within(check).findByText('1 shift is short of people.')).toBeInTheDocument();
  });

  /** ADR-010: each count beside the weight on it, and what the weighted ones come to. Invented. */
  it('GivenWeightedObjectives_WhenTheDraftIsChecked_ThenEachCountAndTheTotalAreShown', async () => {
    aDraft({
      '/api/rosters/roster-1/assessment': {
        status: 200,
        body: {
          ...NOTHING_WRONG,
          objectives: [
            { objective: 'FAIRNESS', measure: 2, weight: 3 },
            { objective: 'PREFERENCES', measure: 1, weight: null },
            { objective: 'DISRUPTION', measure: 4, weight: 1 },
          ],
          weightedTotal: 10,
        },
      },
    });
    renderPage();

    const check = await theCheck(await openBoard());
    const fairness = await within(check).findByRole('row', { name: /^Fairness/ });

    expect(
      within(fairness)
        .getAllByRole('cell')
        .map((cell) => cell.textContent),
    ).toEqual(['Fairness: the most unpopular shifts any one person carries', '2', '3']);
    expect(within(check).getByRole('row', { name: /^Preferences/ })).toHaveTextContent(
      'not weighed',
    );
    expect(check).toHaveTextContent('Weighted total: 10.');
  });

  it('GivenNothingWeighed_WhenTheDraftIsChecked_ThenItSaysEveryDraftTotalsZero', async () => {
    aDraft();
    renderPage();

    const check = await theCheck(await openBoard());

    expect(
      await within(check).findByText('No objective is weighed, so every draft totals zero.'),
    ).toBeInTheDocument();
  });

  /** A published roster was judged when it was published (Q9), so there is nothing to check. */
  it('GivenAPublishedRoster_WhenOpened_ThenItIsNotChecked', async () => {
    const published = { ...ROSTER, status: 'PUBLISHED', validatedAgainstConstraintVersion: 1 };
    const stub = aDraft({
      '/api/rosters': { status: 200, body: [published] },
      '/api/rosters/roster-1': { status: 200, body: published },
    });
    renderPage();

    const board = await openBoard();

    expect(await within(board).findByText('Ada')).toBeInTheDocument();
    expect(
      within(board).queryByRole('region', { name: 'Checked against the rules' }),
    ).not.toBeInTheDocument();
    expect(callsTo(stub, 'GET', '/api/rosters/roster-1/assessment')).toHaveLength(0);
  });

  it('GivenTheCheckIsRefused_WhenTheBoardOpens_ThenTheRefusalIsShown', async () => {
    aDraft({
      '/api/rosters/roster-1/assessment': {
        status: 422,
        body: { detail: 'Roster roster-1 assigns shift shift-9, which cannot be resolved' },
      },
    });
    renderPage();

    const check = await theCheck(await openBoard());

    expect(await within(check).findByRole('alert')).toHaveTextContent('cannot be resolved');
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

  /** ADR-046: the Scheduler waited minutes for that draft, so it opens rather than hiding in the list. */
  it('GivenAGenerationStillPending_WhenItEnds_ThenItsDraftIsListedAndOpened', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const stub = aDraft({
        'POST /api/rosters/generations': {
          status: 202,
          body: { id: 'gen-1', status: 'PENDING', roster: null, violations: [] },
        },
        'GET /api/rosters/generations/gen-1': {
          status: 200,
          body: { id: 'gen-1', status: 'SUCCEEDED', roster: 'roster-1', violations: [] },
        },
      });
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
      renderPage();
      await screen.findByRole('button', { name: 'Open' });
      const listedBefore = callsTo(stub, 'GET', '/api/rosters').length;

      await user.click(screen.getByRole('button', { name: 'Generate a draft for this span' }));
      expect(await screen.findByText('Generating a draft…')).toBeInTheDocument();
      await vi.advanceTimersByTimeAsync(1000);

      await waitFor(() =>
        expect(callsTo(stub, 'GET', '/api/rosters').length).toBeGreaterThan(listedBefore),
      );
      expect(callsTo(stub, 'GET', '/api/rosters/generations/gen-1')).toHaveLength(1);
      expect(await screen.findByRole('region', { name: /^Roster/ })).toBeInTheDocument();
      expect(screen.queryByText('Generating a draft…')).not.toBeInTheDocument();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  /**
   * ADR-046: a deployment replaces the server mid-solve, so asking how a generation stands can fail
   * for a moment while the solve carries on elsewhere. That failure is shown, and asked past.
   */
  it('GivenAskingHowAGenerationStandsFailsOnce_WhenAskedAgain_ThenTheWaitCarriesOnToItsEnd', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const pending = { id: 'gen-1', status: 'PENDING', roster: null, violations: [] };
      const starting = { 'POST /api/rosters/generations': { status: 202, body: pending } };
      aDraft({
        ...starting,
        'GET /api/rosters/generations/gen-1': { status: 502, body: null },
      });
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
      renderPage();
      await user.click(
        await screen.findByRole('button', { name: 'Generate a draft for this span' }),
      );
      await vi.advanceTimersByTimeAsync(1000);

      expect(await screen.findByText(/asking again/)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Generate a draft for this span' })).toBeDisabled();

      aDraft({
        ...starting,
        'GET /api/rosters/generations/gen-1': {
          status: 200,
          body: { ...pending, status: 'SUCCEEDED', roster: 'roster-1' },
        },
      });
      await vi.advanceTimersByTimeAsync(1000);

      expect(await screen.findByRole('region', { name: /^Roster/ })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Generate a draft for this span' })).toBeEnabled();
      expect(screen.queryByText(/asking again/)).not.toBeInTheDocument();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  /** A refusal to answer is no passing failure: the wait stops, and says why. */
  it('GivenAGenerationTheServerDoesNotFind_WhenAsked_ThenTheWaitStopsAndSaysWhy', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const stub = routeTo({
        'POST /api/rosters/generations': {
          status: 202,
          body: { id: 'gen-1', status: 'PENDING', roster: null, violations: [] },
        },
        'GET /api/rosters/generations/gen-1': {
          status: 404,
          body: { title: 'Not found', detail: 'No such generation in this organisation' },
        },
        '/api/rosters': { status: 200, body: [] },
      });
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
      renderPage();
      await user.click(
        await screen.findByRole('button', { name: 'Generate a draft for this span' }),
      );
      await vi.advanceTimersByTimeAsync(1000);

      expect(await screen.findByRole('alert')).toHaveTextContent(/^Nothing here/);
      expect(screen.getByRole('button', { name: 'Generate a draft for this span' })).toBeEnabled();
      const asked = callsTo(stub, 'GET', '/api/rosters/generations/gen-1').length;
      await vi.advanceTimersByTimeAsync(3000);
      expect(callsTo(stub, 'GET', '/api/rosters/generations/gen-1')).toHaveLength(asked);
    } finally {
      vi.useRealTimers();
    }
  });

  /** The span on screen is what is generated for, not a span the page chose itself. */
  it('GivenASpan_WhenGenerating_ThenItsStartAndEndAreSent', async () => {
    const stub = routeTo({
      'POST /api/rosters/generations': {
        status: 202,
        body: { id: 'gen-1', status: 'REFUSED', roster: null, violations: [] },
      },
      '/api/rosters': { status: 200, body: [] },
    });
    renderPage();

    await userEvent.click(
      await screen.findByRole('button', { name: 'Generate a draft for this span' }),
    );

    await waitFor(() => expect(callsTo(stub, 'POST', '/api/rosters/generations')).toHaveLength(1));
    const init = callsTo(stub, 'POST', '/api/rosters/generations')[0]?.[1];
    expect(JSON.parse(String(init?.body))).toEqual({ start: SPAN.from, end: SPAN.to });
  });

  /**
   * #130: work a published roster carries onto these shifts that can no longer stay there refuses
   * the generation, naming who and why, and says it is that published roster that has to change.
   */
  it('GivenAGenerationRefusedForWorkCarriedOver_WhenItEnds_ThenWhoAndWhyAreShown', async () => {
    routeTo({
      'POST /api/rosters/generations': {
        status: 202,
        body: {
          id: 'gen-1',
          status: 'REFUSED',
          roster: null,
          violations: [
            {
              employee: 'emp-1',
              constraint: 'declared unavailability',
              detail: 'shift shift-1 runs through time they declared they cannot work',
            },
          ],
        },
      },
      '/api/rosters': { status: 200, body: [] },
    });
    renderPage();

    await userEvent.click(
      await screen.findByRole('button', { name: 'Generate a draft for this span' }),
    );

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/^Generation refused/);
    expect(alert).toHaveTextContent('Change that published roster first');
    expect(within(alert).getByText('Ada')).toBeInTheDocument();
    expect(alert).toHaveTextContent('declared unavailability');
  });

  it('GivenAGenerationLostWithTheServerSolvingIt_WhenItEnds_ThenItSaysToGenerateAgain', async () => {
    routeTo({
      'POST /api/rosters/generations': {
        status: 202,
        body: { id: 'gen-1', status: 'LOST', roster: null, violations: [] },
      },
      '/api/rosters': { status: 200, body: [] },
    });
    renderPage();

    await userEvent.click(
      await screen.findByRole('button', { name: 'Generate a draft for this span' }),
    );

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The generation was lost: the server running it stopped. Generate again.',
    );
  });

  /** A defect the server logged: there is no draft, and nothing the Scheduler did caused it. */
  it('GivenAGenerationThatFailed_WhenItEnds_ThenItSaysToGenerateAgainAndReportIt', async () => {
    routeTo({
      'POST /api/rosters/generations': {
        status: 202,
        body: { id: 'gen-1', status: 'FAILED', roster: null, violations: [] },
      },
      '/api/rosters': { status: 200, body: [] },
    });
    renderPage();

    await userEvent.click(
      await screen.findByRole('button', { name: 'Generate a draft for this span' }),
    );

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The generation failed. Generate again, and report it if it fails again.',
    );
    expect(screen.getByRole('button', { name: 'Generate a draft for this span' })).toBeEnabled();
  });

  it('GivenEveryRunBusy_WhenGenerating_ThenWhatTheServerSaidIsShown', async () => {
    routeTo({
      'POST /api/rosters/generations': {
        status: 503,
        body: { title: 'Generation is busy', detail: '2 generations are already running' },
      },
      '/api/rosters': { status: 200, body: [] },
    });
    renderPage();

    await userEvent.click(
      await screen.findByRole('button', { name: 'Generate a draft for this span' }),
    );

    expect(await screen.findByRole('alert')).toHaveTextContent('already running');
  });
});
