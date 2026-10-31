import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Session } from '../api/session';
import type { Employee } from '../api/workforce';
import { dayLabel, timeLabel } from '../components/localDates';
import { A_TENANT, SignedIn } from '../test-support/session';
import { callsTo, holdingWrites, routeTo, writes, type Answer } from '../test-support/routeTo';
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

/**
 * How a row names its roster: the period, in local dates, as the row shows it. Each repeated button
 * starts with its visible text and then names the roster by this (C7).
 */
function labelOf(roster: { start: string; end: string }): string {
  return `${new Date(roster.start).toLocaleDateString()} to ${new Date(roster.end).toLocaleDateString()}`;
}

/** Text as a pattern source that matches it literally. */
function literally(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** A pattern matching text that starts with these parts, in order, read literally. */
function startingWith(...parts: string[]): RegExp {
  return new RegExp(`^${parts.map(literally).join('')}`);
}

/**
 * The board's region. `\b` keeps out a region named "Rosters", which the rosters table may sit in
 * once it scrolls on its own (C8).
 */
const THE_BOARD = /^Roster\b/;

/**
 * C1: a control waiting for a request is marked so, and is not `disabled`, which would drop the
 * keyboard focus to the body. jsdom does not drop it either way, so the attribute is the proof.
 */
function expectWaiting(control: HTMLElement) {
  expect(control, 'aria-disabled while the request is in flight').toHaveAttribute(
    'aria-disabled',
    'true',
  );
  expect(control, 'not disabled, which would drop the keyboard focus').toBeEnabled();
}

/** A control that may act again: neither disabled nor marked as unable to act. */
function expectActionable(control: HTMLElement) {
  expect(control).toBeEnabled();
  expect(control, 'no longer aria-disabled').not.toHaveAttribute('aria-disabled', 'true');
}

/** C3: the live region that says this. Several are on the page, so each is looked at. */
function findStatus(saying: RegExp): Promise<HTMLElement> {
  return waitFor(() => {
    const statuses = screen.getAllByRole('status');
    const found = statuses.find((status) => saying.test(status.textContent ?? ''));
    if (found === undefined) {
      throw new Error(
        `no status says ${String(saying)}; the statuses say ${JSON.stringify(statuses.map((status) => status.textContent))}`,
      );
    }
    return found;
  });
}

async function openBoard(): Promise<HTMLElement> {
  await userEvent.click(await screen.findByRole('button', { name: /^Open roster/ }));
  return screen.findByRole('region', { name: THE_BOARD });
}

async function assignGrace(board: HTMLElement) {
  const picker = await within(board).findByLabelText(/^Who to assign/);
  await waitFor(() => expect(within(picker).getAllByRole('option')).toHaveLength(3));
  await userEvent.selectOptions(picker, 'emp-2');
  await userEvent.click(within(board).getByRole('button', { name: /^Assign to/ }));
}

afterEach(() => {
  vi.unstubAllGlobals();
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

    await userEvent.click(await screen.findByRole('button', { name: /^Publish roster/ }));

    const alert = await screen.findByRole('alert');
    // Resolved to a name: the reason the 422 payload is structured at all.
    expect(alert).toHaveTextContent('Ada');
    expect(alert).toHaveTextContent('maximum daily working time');
    expect(alert).toHaveTextContent('exceeds the maximum of PT1H');
    expect(alert).toHaveTextContent('There is no override');
    await waitFor(() => expectActionable(screen.getByRole('button', { name: /^Publish roster/ })));
  });

  /** F3: a draft that breaks no rule is published, and the list then says so, as the server does. */
  it('GivenADraftThatBreaksNoRule_WhenPublished_ThenItIsListedAsPublished', async () => {
    const published = { ...ROSTER, status: 'PUBLISHED', validatedAgainstConstraintVersion: 1 };
    const server: Record<string, Answer> = {
      '/api/rosters': { status: 200, body: [ROSTER] },
      '/api/shifts': { status: 200, body: [SHIFT] },
      'POST /api/rosters/roster-1/publication': { status: 200, body: published },
    };
    const stub = routeTo(server);
    renderPage([ADA]);
    const publish = await screen.findByRole('button', { name: /^Publish roster/ });
    // What the server holds once the publication has landed; the page is told only by reading again.
    server['/api/rosters'] = { status: 200, body: [published] };

    await userEvent.click(publish);

    expect(await screen.findByRole('cell', { name: 'PUBLISHED' })).toBeInTheDocument();
    expect(callsTo(stub, 'POST', '/api/rosters/roster-1/publication')).toHaveLength(1);
    expect(screen.queryByRole('button', { name: /^Publish roster/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  /** A second publication sent meanwhile is refused as a conflict, beside the first one's success. */
  it('GivenAPublicationInFlight_WhenPublishIsPressedAgain_ThenOnlyOneIsSent', async () => {
    const published = { ...ROSTER, status: 'PUBLISHED', validatedAgainstConstraintVersion: 1 };
    // A second draft, whose Publish button is still there once the first is published.
    const anotherDraft = { ...ROSTER, id: 'roster-2' };
    const server: Record<string, Answer> = {
      '/api/rosters': { status: 200, body: [ROSTER, anotherDraft] },
      '/api/shifts': { status: 200, body: [SHIFT] },
      'POST /api/rosters/roster-1/publication': { status: 200, body: published },
    };
    const routed = routeTo(server);
    const release = holdingWrites(routed);
    renderPage([ADA]);
    const [publish] = await screen.findAllByRole('button', { name: /^Publish roster/ });
    if (publish === undefined) {
      throw new Error('no draft offers to be published');
    }
    server['/api/rosters'] = { status: 200, body: [published, anotherDraft] };

    await userEvent.click(publish);
    await userEvent.click(publish);

    expectWaiting(publish);
    expect(publish, 'the pressed Publish keeps the keyboard focus').toHaveFocus();
    release();
    expect(await screen.findByRole('cell', { name: 'PUBLISHED' })).toBeInTheDocument();
    expect(callsTo(routed, 'POST', '/api/rosters/roster-1/publication')).toHaveLength(1);
    await waitFor(() => expectActionable(screen.getByRole('button', { name: /^Publish roster/ })));
  });

  it('GivenTheRosterTable_WhenRendered_ThenEveryColumnHasAHeader', async () => {
    aDraft();
    renderPage();

    await screen.findByRole('button', { name: /^Open roster/ });

    const table = screen.getByRole('table');
    const headers = within(table).getAllByRole('columnheader');
    // One roster is listed, so every cell is in its row: its data cells and its header cell (C2).
    expect(headers, 'one header per column of the roster row').toHaveLength(
      within(table).getAllByRole('cell').length + within(table).queryAllByRole('rowheader').length,
    );
    for (const header of headers) {
      expect(header, 'every column header has a name').toHaveAccessibleName();
    }
    expect(headers.map((header) => header.textContent)).toEqual([
      'Roster',
      'Identifier',
      'Status',
      'Assignments',
      'Approved under',
      'Actions',
    ]);
  });

  /** A screen reader reads the row header on every move along the row, so it holds no UUID. */
  it('GivenARoster_WhenListed_ThenItsRowHeaderIsThePeriodAndItsIdentifierHasItsOwnColumn', async () => {
    aDraft();
    renderPage();

    const table = await theRostersTable();

    expect(within(table).getByRole('rowheader')).toHaveAccessibleName(labelOf(ROSTER));
    expect(within(table).getByRole('cell', { name: ROSTER.id })).toBeInTheDocument();
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

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Not permitted.'));
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

    await userEvent.click(await screen.findByRole('button', { name: /^Version 2\b/ }));

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

    await userEvent.click(await screen.findByRole('button', { name: /^Version 1\b/ }));

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

    await userEvent.click(await screen.findByRole('button', { name: /^Version 3\b/ }));

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

    await userEvent.click(await screen.findByRole('button', { name: /^Version 2\b/ }));

    const rules = await screen.findByRole('region', { name: 'Rules of version 2' });
    expect(await within(rules).findByRole('alert')).toHaveTextContent(
      'The rule store is unavailable.',
    );
  });

  it('GivenADraft_WhenListed_ThenItOffersNoRulesToOpen', async () => {
    aDraft();
    renderPage();

    expect(await screen.findByRole('button', { name: /^Open roster/ })).toBeInTheDocument();
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

    const assign = within(board).getByRole('button', { name: /^Assign to/ });
    const remove = within(board).getByRole('button', { name: /^Remove Ada from/ });
    expectWaiting(remove);
    expectWaiting(assign);
    expectWaiting(within(board).getByRole('combobox', { name: /^Who to assign/ }));
    expect(assign, 'the pressed Assign keeps the keyboard focus').toHaveFocus();
    // aria-disabled stops nothing by itself (C1): pressing a waiting control must send nothing.
    await userEvent.click(remove);
    await userEvent.click(assign);
    release();
    expectActionable(await within(board).findByRole('button', { name: /^Remove Grace from/ }));
    expect(writes(routed), 'only the first change was sent').toHaveLength(1);
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

  /**
   * The same assignment pressed twice: the first was stored, and the backend refuses the second as
   * a conflict. The board reloads and shows who is now on the shift, rather than leaving a refusal
   * beside a roster that does not yet show the assignment it refers to.
   */
  it('GivenAnAssignmentAlreadyStored_WhenAssigningItAgain_ThenTheBoardShowsItStored', async () => {
    aDraft();
    renderPage();
    const board = await openBoard();
    aDraft({
      '/api/rosters': { status: 200, body: [WITH_GRACE] },
      '/api/rosters/roster-1': { status: 200, body: WITH_GRACE },
      'POST /api/rosters/roster-1/assignments': {
        status: 409,
        body: {
          title: 'Already assigned',
          detail: 'That employee is already assigned to that shift on this roster.',
        },
      },
    });

    await assignGrace(board);

    expect(
      await within(board).findByRole('button', { name: /^Remove Grace from/ }),
    ).toBeInTheDocument();
    expect(within(board).getByRole('alert')).toHaveTextContent(
      'That employee is already assigned to that shift on this roster.',
    );
  });

  /** How the board names the fixture's one shift, as its controls do. */
  const SHIFT_LABEL = `${timeLabel(SHIFT.start)} to ${timeLabel(SHIFT.end)} on ${dayLabel(SHIFT.start)}`;

  /**
   * A conflict's detail is the backend's, and names neither the person nor the shift, so the board
   * says which change was refused, and that what it now shows was read again after the refusal.
   */
  it('GivenAConflict_WhenAssigning_ThenTheBoardSaysWhoAndWhichShiftAndThatItWasReadAgain', async () => {
    aDraft();
    renderPage();
    const board = await openBoard();
    aDraft({
      '/api/rosters': { status: 200, body: [WITH_GRACE] },
      '/api/rosters/roster-1': { status: 200, body: WITH_GRACE },
      'POST /api/rosters/roster-1/assignments': {
        status: 409,
        body: {
          title: 'Already assigned',
          detail: 'That employee is already assigned to that shift on this roster.',
        },
      },
    });

    await assignGrace(board);

    expect(
      await within(board).findByText(`Could not assign Grace to ${SHIFT_LABEL}.`, { exact: false }),
    ).toHaveTextContent(
      `Could not assign Grace to ${SHIFT_LABEL}. The board was read again, and shows the roster as it now is.`,
    );
  });

  it('GivenAConflict_WhenRemoving_ThenTheBoardSaysWhoAndWhichShift', async () => {
    aDraft();
    renderPage();
    const board = await openBoard();
    const published = { ...ROSTER, status: 'PUBLISHED', validatedAgainstConstraintVersion: 1 };
    aDraft({
      '/api/rosters': { status: 200, body: [published] },
      '/api/rosters/roster-1': { status: 200, body: published },
      'DELETE /api/rosters/roster-1/assignments/shift-1/emp-1': {
        status: 409,
        body: {
          title: 'Roster status forbids this',
          detail: 'Roster roster-1 is PUBLISHED: a roster can only be changed while a draft',
        },
      },
    });

    await userEvent.click(await within(board).findByRole('button', { name: /^Remove Ada from/ }));

    expect(
      await within(board).findByText(`Could not remove Ada from ${SHIFT_LABEL}.`, { exact: false }),
    ).toBeInTheDocument();
  });

  /** The roster read again may be a different draft, so what the check said of the old one is stale. */
  it('GivenAConflict_WhenAssigning_ThenTheDraftIsCheckedAgain', async () => {
    const stub = aDraft({
      'POST /api/rosters/roster-1/assignments': {
        status: 409,
        body: {
          title: 'Already assigned',
          detail: 'That employee is already assigned to that shift on this roster.',
        },
      },
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

  /**
   * An objective a newer server scores still counts towards the total shown, so it is listed by
   * its name rather than dropped. The objective and its numbers are invented for the test.
   */
  it('GivenAnObjectiveThisBuildDoesNotKnow_WhenTheDraftIsChecked_ThenItIsShownByName', async () => {
    aDraft({
      '/api/rosters/roster-1/assessment': {
        status: 200,
        body: {
          ...NOTHING_WRONG,
          objectives: [...NOTHING_WRONG.objectives, { objective: 'TRAVEL', measure: 5, weight: 2 }],
          weightedTotal: 10,
        },
      },
    });
    renderPage();

    const check = await theCheck(await openBoard());
    const travel = await within(check).findByRole('row', { name: /TRAVEL/ });

    expect(
      within(travel)
        .getAllByRole('cell')
        .map((cell) => cell.textContent),
    ).toEqual(['TRAVEL: an objective this version of HrSched cannot describe', '5', '2']);
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
      await screen.findByRole('button', { name: /^Open roster/ });
      const listedBefore = callsTo(stub, 'GET', '/api/rosters').length;

      await user.click(screen.getByRole('button', { name: 'Generate a draft for this span' }));
      expect(await screen.findByText('Generating a draft…')).toBeInTheDocument();
      await vi.advanceTimersByTimeAsync(1000);

      await waitFor(() =>
        expect(callsTo(stub, 'GET', '/api/rosters').length).toBeGreaterThan(listedBefore),
      );
      expect(callsTo(stub, 'GET', '/api/rosters/generations/gen-1')).toHaveLength(1);
      expect(await screen.findByRole('region', { name: THE_BOARD })).toBeInTheDocument();
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
      // The poll's pause is scheduled only once the POST has answered: advancing sooner races it.
      await screen.findByText('Generating a draft…');
      await vi.advanceTimersByTimeAsync(1000);

      expect(await screen.findByText(/asking again/)).toBeInTheDocument();
      expectWaiting(screen.getByRole('button', { name: 'Generate a draft for this span' }));

      aDraft({
        ...starting,
        'GET /api/rosters/generations/gen-1': {
          status: 200,
          body: { ...pending, status: 'SUCCEEDED', roster: 'roster-1' },
        },
      });
      await vi.advanceTimersByTimeAsync(1000);

      expect(await screen.findByRole('region', { name: THE_BOARD })).toBeInTheDocument();
      expectActionable(screen.getByRole('button', { name: 'Generate a draft for this span' }));
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
      expectActionable(screen.getByRole('button', { name: 'Generate a draft for this span' }));
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
      // Answered as already ended, so the page starts no wait for it to finish.
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

  /**
   * A newer server may end a generation in a way this build has never heard of. Waiting stops, so
   * the page has to say why, by name, rather than stop without a word. Invented for the test.
   */
  it('GivenAGenerationEndingInAStatusThisBuildDoesNotKnow_WhenItEnds_ThenItSaysSoByName', async () => {
    const stub = routeTo({
      'POST /api/rosters/generations': {
        status: 202,
        body: { id: 'gen-1', status: 'CANCELLED', roster: null, violations: [] },
      },
      '/api/rosters': { status: 200, body: [] },
    });
    renderPage();
    const generate = await screen.findByRole('button', { name: 'Generate a draft for this span' });
    const listedBefore = callsTo(stub, 'GET', '/api/rosters').length;

    await userEvent.click(generate);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      /^The generation ended as CANCELLED, which this version of HrSched cannot describe\./,
    );
    // The message points at the rosters below, so they are read again to hold any draft it made.
    await waitFor(() =>
      expect(callsTo(stub, 'GET', '/api/rosters').length).toBeGreaterThan(listedBefore),
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
    expectActionable(screen.getByRole('button', { name: 'Generate a draft for this span' }));
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

// --- #257 (WCAG 2.2 AA) and #185: the page as a keyboard and screen-reader user meets it --------

/** A second draft over the following week, so its row and its buttons are told apart by name. */
const NEXT_WEEK = {
  ...ROSTER,
  id: 'roster-2',
  start: '2026-11-09T00:00:00Z',
  end: '2026-11-16T00:00:00Z',
  assignments: [],
};

/** The rosters table, found before anything is opened, while it is the only table on the page. */
async function theRostersTable(): Promise<HTMLElement> {
  await screen.findAllByRole('button', { name: /^Open roster/ });
  return screen.getByRole('table');
}

/** The row right after the one headed by this roster's period: where a refusal of it belongs (C6). */
function rowAfterTheRowOf(table: HTMLElement, roster: { start: string; end: string }): HTMLElement {
  const rows = within(table).getAllByRole('row');
  const index = rows.findIndex(
    (row) => within(row).queryByRole('rowheader', { name: startingWith(labelOf(roster)) }) !== null,
  );
  const next = rows[index + 1];
  if (index < 0 || next === undefined) {
    throw new Error(`no row follows the row of ${labelOf(roster)}`);
  }
  return next;
}

/** The board's own heading, which is what focus lands on when the board appears (C4). */
function headingOf(board: HTMLElement): HTMLElement {
  return within(board).getByRole('heading', { level: 3 });
}

describe('generating, for a keyboard and screen-reader user', () => {
  const PENDING_GENERATION = { id: 'gen-1', status: 'PENDING', roster: null, violations: [] };

  it('GivenAGenerationInFlight_WhenGenerateIsPressed_ThenItIsAriaDisabledKeepsFocusAndSendsOnce', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const stub = aDraft({
        'POST /api/rosters/generations': { status: 202, body: PENDING_GENERATION },
        'GET /api/rosters/generations/gen-1': { status: 200, body: PENDING_GENERATION },
      });
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
      renderPage();
      const generate = await screen.findByRole('button', {
        name: 'Generate a draft for this span',
      });

      await user.click(generate);
      await screen.findByText('Generating a draft…');
      await user.click(generate);

      expectWaiting(generate);
      expect(generate, 'Generate keeps the keyboard focus while it waits').toHaveFocus();
      expect(callsTo(stub, 'POST', '/api/rosters/generations')).toHaveLength(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it('GivenAGenerationThatSucceeds_WhenItsDraftOpens_ThenAStatusSaysDraftReadyAndFocusMovesToTheBoard', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      aDraft({
        'POST /api/rosters/generations': { status: 202, body: PENDING_GENERATION },
        'GET /api/rosters/generations/gen-1': {
          status: 200,
          body: { ...PENDING_GENERATION, status: 'SUCCEEDED', roster: 'roster-1' },
        },
      });
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
      renderPage();

      await user.click(
        await screen.findByRole('button', { name: 'Generate a draft for this span' }),
      );
      // The poll is scheduled only once the POST has answered, so the clock waits for that.
      await screen.findByText('Generating a draft…');
      await vi.advanceTimersByTimeAsync(1000);

      const board = await screen.findByRole('region', { name: THE_BOARD });
      await findStatus(new RegExp(`^${literally(`Draft ready: ${labelOf(ROSTER)}.`)}$`));
      await waitFor(() =>
        expect(headingOf(board), 'focus follows the draft that opened').toHaveFocus(),
      );
    } finally {
      vi.useRealTimers();
    }
  });

  /**
   * C4: the draft opens only from Generate. Whoever moved on meanwhile keeps both their focus and
   * the board in front of them, and is told where to find the draft.
   */
  it('GivenFocusMovedElsewhereWhileAGenerationRuns_WhenItsDraftIsReady_ThenNothingOpensAndFocusIsNotTaken', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      aDraft({
        'POST /api/rosters/generations': { status: 202, body: PENDING_GENERATION },
        'GET /api/rosters/generations/gen-1': {
          status: 200,
          body: { ...PENDING_GENERATION, status: 'SUCCEEDED', roster: 'roster-1' },
        },
      });
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
      renderPage();
      const elsewhere = await screen.findByRole('button', { name: /^Publish roster/ });
      await user.click(screen.getByRole('button', { name: 'Generate a draft for this span' }));
      await screen.findByText('Generating a draft…');
      elsewhere.focus();

      await vi.advanceTimersByTimeAsync(1000);

      await findStatus(
        new RegExp(`^${literally(`Draft ready: ${labelOf(ROSTER)}. Open it from the list.`)}$`),
      );
      expect(screen.queryByRole('region', { name: THE_BOARD })).not.toBeInTheDocument();
      expect(elsewhere, 'focus stays where the user moved it').toHaveFocus();
    } finally {
      vi.useRealTimers();
    }
  });

  /** A finished generation does not tear down the board somebody is working in, or their focus. */
  it('GivenFocusInAnOpenBoard_WhenAGenerationSucceeds_ThenTheBoardAndItsFocusStay', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      aDraft({
        '/api/rosters': { status: 200, body: [ROSTER, NEXT_WEEK] },
        'POST /api/rosters/generations': { status: 202, body: PENDING_GENERATION },
        'GET /api/rosters/generations/gen-1': {
          status: 200,
          body: { ...PENDING_GENERATION, status: 'SUCCEEDED', roster: NEXT_WEEK.id },
        },
      });
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
      renderPage();
      await user.click(
        await screen.findByRole('button', { name: startingWith('Open roster ', labelOf(ROSTER)) }),
      );
      const board = await screen.findByRole('region', { name: THE_BOARD });
      const picker = await within(board).findByRole('combobox', { name: /^Who to assign/ });
      await user.click(screen.getByRole('button', { name: 'Generate a draft for this span' }));
      await screen.findByText('Generating a draft…');
      picker.focus();

      await vi.advanceTimersByTimeAsync(1000);

      await findStatus(
        new RegExp(`^${literally(`Draft ready: ${labelOf(NEXT_WEEK)}. Open it from the list.`)}$`),
      );
      expect(picker, 'the board was not rebuilt under the user').toBeInTheDocument();
      expect(picker, 'focus stays in the board').toHaveFocus();
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('opening and closing what the list opens (#185)', () => {
  const PUBLISHED_UNDER_2 = {
    ...ROSTER,
    status: 'PUBLISHED',
    validatedAgainstConstraintVersion: 2,
  };

  /** A roster published under version 2, whose one rule has an invented limit. */
  function publishedUnderVersion2() {
    return aDraft({
      '/api/rosters': { status: 200, body: [PUBLISHED_UNDER_2] },
      '/api/rosters/roster-1': { status: 200, body: PUBLISHED_UNDER_2 },
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
  }

  it('GivenARoster_WhenListed_ThenItsOpenButtonNamesTheRosterAndIsCollapsed', async () => {
    aDraft();
    renderPage();

    const open = await screen.findByRole('button', {
      name: startingWith('Open roster ', labelOf(ROSTER)),
    });

    expect(open).toHaveAttribute('aria-expanded', 'false');
  });

  it('GivenARoster_WhenOpened_ThenItsButtonIsExpandedAndFocusMovesToTheBoardsHeading', async () => {
    aDraft();
    renderPage();
    const open = await screen.findByRole('button', { name: /^Open roster/ });

    await userEvent.click(open);

    const board = await screen.findByRole('region', { name: THE_BOARD });
    expect(open).toHaveAttribute('aria-expanded', 'true');
    await waitFor(() => expect(headingOf(board)).toHaveFocus());
  });

  it('GivenAnOpenBoard_WhenClosed_ThenItIsGoneCollapsedAndFocusIsBackOnItsOpenButton', async () => {
    aDraft();
    renderPage();
    const board = await openBoard();
    const open = screen.getByRole('button', { name: /^Open roster/ });

    await userEvent.click(within(board).getByRole('button', { name: /^Close/ }));

    expect(screen.queryByRole('region', { name: THE_BOARD })).not.toBeInTheDocument();
    expect(open).toHaveAttribute('aria-expanded', 'false');
    expect(open, 'focus returns to the button that opened the board').toHaveFocus();
  });

  it('GivenAPublishedRoster_WhenListed_ThenItsVersionButtonNamesTheRosterAndIsCollapsed', async () => {
    publishedUnderVersion2();
    renderPage();

    const version = await screen.findByRole('button', {
      // Starts with its visible text, then names the roster whose approval it opens (C7).
      name: new RegExp(`^Version 2\\b.*${literally(labelOf(ROSTER))}`),
    });

    expect(version).toHaveAttribute('aria-expanded', 'false');
  });

  it('GivenAPublishedRoster_WhenItsVersionIsOpened_ThenItIsExpandedAndFocusMovesToTheRulesHeading', async () => {
    publishedUnderVersion2();
    renderPage();
    const version = await screen.findByRole('button', { name: /^Version 2\b/ });

    await userEvent.click(version);

    const rules = await screen.findByRole('region', { name: 'Rules of version 2' });
    expect(version).toHaveAttribute('aria-expanded', 'true');
    await waitFor(() =>
      expect(within(rules).getByRole('heading', { name: 'Rules of version 2' })).toHaveFocus(),
    );
  });

  it('GivenOpenRules_WhenClosed_ThenTheyAreGoneCollapsedAndFocusIsBackOnTheVersionButton', async () => {
    publishedUnderVersion2();
    renderPage();
    const version = await screen.findByRole('button', { name: /^Version 2\b/ });
    await userEvent.click(version);
    const rules = await screen.findByRole('region', { name: 'Rules of version 2' });

    await userEvent.click(within(rules).getByRole('button', { name: /^Close/ }));

    expect(screen.queryByRole('region', { name: 'Rules of version 2' })).not.toBeInTheDocument();
    expect(version).toHaveAttribute('aria-expanded', 'false');
    expect(version, 'focus returns to the button that opened the rules').toHaveFocus();
  });

  it('GivenAnOpenBoard_WhenItsOpenButtonIsPressedAgain_ThenItClosesAndFocusStaysOnTheButton', async () => {
    aDraft();
    renderPage();
    await openBoard();
    const open = screen.getByRole('button', { name: /^Open roster/ });

    await userEvent.click(open);

    expect(screen.queryByRole('region', { name: THE_BOARD })).not.toBeInTheDocument();
    expect(open).toHaveAttribute('aria-expanded', 'false');
    expect(open, 'APG Disclosure: the button keeps the focus').toHaveFocus();
  });

  it('GivenOpenRules_WhenTheirVersionButtonIsPressedAgain_ThenTheyCloseAndFocusStaysOnTheButton', async () => {
    publishedUnderVersion2();
    renderPage();
    const version = await screen.findByRole('button', { name: /^Version 2\b/ });
    await userEvent.click(version);
    await screen.findByRole('region', { name: 'Rules of version 2' });

    await userEvent.click(version);

    expect(screen.queryByRole('region', { name: 'Rules of version 2' })).not.toBeInTheDocument();
    expect(version).toHaveAttribute('aria-expanded', 'false');
    expect(version, 'APG Disclosure: the button keeps the focus').toHaveFocus();
  });

  it('GivenTheOpenAndVersionButtons_WhenWhatTheyOpenIsShown_ThenEachControlsThatRegion', async () => {
    publishedUnderVersion2();
    renderPage();
    const version = await screen.findByRole('button', { name: /^Version 2\b/ });
    const open = screen.getByRole('button', { name: /^Open roster/ });

    await userEvent.click(version);
    await userEvent.click(open);

    const rules = await screen.findByRole('region', { name: 'Rules of version 2' });
    const board = await screen.findByRole('region', { name: THE_BOARD });
    expect(version).toHaveAttribute('aria-controls', rules.id);
    expect(open).toHaveAttribute('aria-controls', board.id);
  });

  /** Closing hands focus back to Version; once that row is gone, Generate is the nearest opener. */
  it('GivenOpenRulesWhoseVersionButtonIsGone_WhenClosed_ThenFocusIsOnGenerate', async () => {
    const server: Record<string, Answer> = {
      '/api/rosters': { status: 200, body: [PUBLISHED_UNDER_2, NEXT_WEEK] },
      '/api/shifts': { status: 200, body: [SHIFT] },
      'POST /api/rosters/roster-2/publication': {
        status: 200,
        body: { ...NEXT_WEEK, status: 'PUBLISHED', validatedAgainstConstraintVersion: 1 },
      },
      '/api/constraints/2': {
        status: 200,
        body: {
          version: 2,
          constraints: [],
          minimumRestMinutes: null,
          maximumDailyMinutes: null,
          maximumWeeklyMinutes: null,
          maximumConsecutiveDays: null,
        },
      },
    };
    routeTo(server);
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /^Version 2\b/ }));
    const rules = await screen.findByRole('region', { name: 'Rules of version 2' });
    // The list read again after the next publication fails, which empties the table.
    server['/api/rosters'] = { status: 500, body: { detail: 'The server failed.' } };
    await userEvent.click(screen.getByRole('button', { name: /^Publish roster/ }));
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /^Version 2\b/ })).not.toBeInTheDocument(),
    );

    await userEvent.click(within(rules).getByRole('button', { name: /^Close/ }));

    expect(screen.getByRole('button', { name: 'Generate a draft for this span' })).toHaveFocus();
  });

  /** C4: focus lands on the board's heading at once, so the heading names the roster from the start. */
  it('GivenARosterStillLoading_WhenItsBoardOpens_ThenTheHeadingAlreadyNamesItsPeriod', async () => {
    const routed = aDraft();
    let release!: () => void;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    vi.stubGlobal(
      'fetch',
      vi.fn((path: string, init?: RequestInit) =>
        path === '/api/rosters/roster-1' ? held.then(() => routed(path, init)) : routed(path, init),
      ),
    );
    renderPage();

    const board = await openBoard();

    expect(headingOf(board)).toHaveTextContent(new RegExp(`${literally(labelOf(ROSTER))}$`));
    release();
    await within(board).findByRole('heading', { name: /^Roster draft/ });
  });
});

describe('publishing, for a keyboard and screen-reader user', () => {
  /** A refusal of roster-1's publication: Ada breaks a rule, whose limit is invented. */
  const RULE_BREACH: Answer = {
    status: 422,
    body: {
      detail: 'The roster breaks 1 working-time rule(s)',
      violations: [
        {
          employee: 'emp-1',
          constraint: 'maximum daily working time',
          detail: 'PT8H worked on 2026-11-03 exceeds the maximum of PT1H',
        },
      ],
    },
  };

  /** The published roster-1, as the server holds it once its publication has landed. */
  const PUBLISHED = { ...ROSTER, status: 'PUBLISHED', validatedAgainstConstraintVersion: 1 };

  /** Both drafts listed, and roster-1's publication accepted; the server's list follows it. */
  function twoDraftsPublishingTheFirst() {
    const server: Record<string, Answer> = {
      '/api/rosters': { status: 200, body: [ROSTER, NEXT_WEEK] },
      '/api/rosters/roster-1': { status: 200, body: ROSTER },
      '/api/rosters/roster-1/assessment': { status: 200, body: NOTHING_WRONG },
      '/api/shifts': { status: 200, body: [SHIFT] },
      '/api/qualifications': { status: 200, body: [FORKLIFT] },
      'POST /api/rosters/roster-1/publication': { status: 200, body: PUBLISHED },
      'POST /api/rosters/roster-2/publication': {
        status: 200,
        body: { ...NEXT_WEEK, status: 'PUBLISHED', validatedAgainstConstraintVersion: 1 },
      },
    };
    return { server, routed: routeTo(server) };
  }

  it('GivenAPublicationInFlight_WhenAnotherDraftIsListed_ThenItsPublishSaysAnotherRosterIsBeingPublished', async () => {
    const { routed } = twoDraftsPublishingTheFirst();
    const release = holdingWrites(routed);
    renderPage();
    const publish = await screen.findByRole('button', {
      name: startingWith('Publish roster ', labelOf(ROSTER)),
    });
    const other = screen.getByRole('button', {
      name: startingWith('Publish roster ', labelOf(NEXT_WEEK)),
    });

    await userEvent.click(publish);

    expectWaiting(publish);
    expect(publish, 'the pressed Publish keeps the keyboard focus').toHaveFocus();
    expectWaiting(other);
    expect(other).toHaveAccessibleDescription(/another roster is being published/i);
    // aria-disabled stops nothing by itself (C1): pressing the waiting Publish must send nothing.
    await userEvent.click(other);
    release();
    await waitFor(() => expectActionable(other));
    expect(writes(routed), 'only the first publication was sent').toHaveLength(1);
  });

  /** The pressed Publish is waiting on its own request, not another's, and is told so. */
  it('GivenAPublicationInFlight_WhenThePressedPublishIsRead_ThenItSaysPublishingAndNotAnotherRoster', async () => {
    const release = holdingWrites(aDraft());
    renderPage();
    const publish = await screen.findByRole('button', { name: /^Publish roster/ });

    await userEvent.click(publish);

    expect(await findStatus(/^Publishing…$/)).toBeInTheDocument();
    expect(publish).toHaveAccessibleDescription('Publishing…');
    expect(
      screen.queryByText(/another roster is being published/i),
      'no other roster is listed, so no other roster waits',
    ).not.toBeInTheDocument();
    release();
    await waitFor(() => expectActionable(screen.getByRole('button', { name: /^Publish roster/ })));
  });

  it('GivenADraftThatBreaksNoRule_WhenPublished_ThenAStatusSaysItWasPublished', async () => {
    const server: Record<string, Answer> = {
      '/api/rosters': { status: 200, body: [ROSTER] },
      '/api/shifts': { status: 200, body: [SHIFT] },
      'POST /api/rosters/roster-1/publication': { status: 200, body: PUBLISHED },
    };
    routeTo(server);
    renderPage([ADA]);
    const publish = await screen.findByRole('button', { name: /^Publish roster/ });
    const statusesBefore = screen.getAllByRole('status');
    server['/api/rosters'] = { status: 200, body: [PUBLISHED] };

    await userEvent.click(publish);

    const status = await findStatus(new RegExp(`^${literally(`Published ${labelOf(ROSTER)}.`)}$`));
    // C3: a live region that appeared with its text would not be announced.
    expect(statusesBefore, 'the status was in the page before it said anything').toContain(status);
  });

  /** C2: Publish goes with the draft it published, so focus moves to that row's header cell. */
  it('GivenADraftPublished_WhenItsPublishButtonGoes_ThenFocusIsOnThatRowsHeader', async () => {
    const { server } = twoDraftsPublishingTheFirst();
    renderPage([ADA]);
    const publish = await screen.findByRole('button', {
      name: startingWith('Publish roster ', labelOf(ROSTER)),
    });
    server['/api/rosters'] = { status: 200, body: [PUBLISHED, NEXT_WEEK] };

    await userEvent.click(publish);

    await screen.findByRole('cell', { name: 'PUBLISHED' });
    await waitFor(() =>
      expect(screen.getByRole('rowheader', { name: labelOf(ROSTER) })).toHaveFocus(),
    );
  });

  it('GivenFocusMovedWhileAPublicationIsOut_WhenItLands_ThenFocusIsNotTaken', async () => {
    const { server, routed } = twoDraftsPublishingTheFirst();
    const release = holdingWrites(routed);
    renderPage([ADA]);
    const publish = await screen.findByRole('button', {
      name: startingWith('Publish roster ', labelOf(ROSTER)),
    });
    server['/api/rosters'] = { status: 200, body: [PUBLISHED, NEXT_WEEK] };
    await userEvent.click(publish);
    const elsewhere = screen.getByRole('button', {
      name: startingWith('Open roster ', labelOf(NEXT_WEEK)),
    });
    elsewhere.focus();

    release();

    await screen.findByRole('cell', { name: 'PUBLISHED' });
    await findStatus(/^Published /);
    expect(elsewhere, 'focus stays where the user moved it').toHaveFocus();
  });

  /** Publishing another roster leaves the open board as it was, unsaved choice included. */
  it('GivenAnOpenBoard_WhenAnotherRosterIsPublished_ThenTheBoardIsNotRebuilt', async () => {
    twoDraftsPublishingTheFirst();
    renderPage();
    await userEvent.click(
      await screen.findByRole('button', { name: startingWith('Open roster ', labelOf(ROSTER)) }),
    );
    const board = await screen.findByRole('region', { name: THE_BOARD });
    const picker = await within(board).findByRole('combobox', { name: /^Who to assign/ });
    await waitFor(() => expect(within(picker).getAllByRole('option')).toHaveLength(3));
    await userEvent.selectOptions(picker, 'emp-2');

    await userEvent.click(
      screen.getByRole('button', { name: startingWith('Publish roster ', labelOf(NEXT_WEEK)) }),
    );

    await findStatus(new RegExp(`^${literally(`Published ${labelOf(NEXT_WEEK)}.`)}$`));
    expect(picker, 'the board was not rebuilt').toBeInTheDocument();
    expect(picker, 'the choice not yet assigned is kept').toHaveValue('emp-2');
  });

  /** C6: the refusal is of one row's action, so it is shown at that row, not above the table. */
  it('GivenAPublicationRefusedForBreakingTheRules_WhenShown_ThenItIsInTheRowRightAfterThatRoster', async () => {
    aDraft({
      '/api/rosters': { status: 200, body: [ROSTER, NEXT_WEEK] },
      'POST /api/rosters/roster-1/publication': RULE_BREACH,
    });
    renderPage();
    const table = await theRostersTable();

    await userEvent.click(
      screen.getByRole('button', { name: startingWith('Publish roster ', labelOf(ROSTER)) }),
    );

    const refusal = await within(table).findByRole('alert');
    expect(refusal).toHaveTextContent('There is no override');
    expect(refusal).toHaveTextContent('Ada');
    expect(within(rowAfterTheRowOf(table, ROSTER)).getByRole('alert')).toBe(refusal);
    for (const alert of screen.getAllByRole('alert')) {
      expect(table, 'no copy of the refusal above the table').toContainElement(alert);
    }
  });

  it.each([
    ['Publish', startingWith('Publish roster ', labelOf(ROSTER)), /^Refused: the roster breaks/],
    ['Generate', /^Generate a draft for this span$/, /^Generating a draft…$/],
  ])(
    'GivenAPublicationRefusal_WhenTheNextAttemptIsMade_ThenTheRefusalIsGone (%s)',
    async (_pressed, button, answered) => {
      const routed = aDraft({
        '/api/rosters': { status: 200, body: [ROSTER, NEXT_WEEK] },
        'POST /api/rosters/roster-1/publication': RULE_BREACH,
        'POST /api/rosters/generations': {
          status: 202,
          body: { id: 'gen-1', status: 'PENDING', roster: null, violations: [] },
        },
        'GET /api/rosters/generations/gen-1': {
          status: 200,
          body: { id: 'gen-1', status: 'PENDING', roster: null, violations: [] },
        },
      });
      renderPage();
      const table = await theRostersTable();
      await userEvent.click(
        screen.getByRole('button', { name: startingWith('Publish roster ', labelOf(ROSTER)) }),
      );
      await within(table).findByRole('alert');
      const release = holdingWrites(routed);

      await userEvent.click(screen.getByRole('button', { name: button }));

      expect(within(table).queryByRole('alert')).not.toBeInTheDocument();
      release();
      // Waits for the released request's answer, so it lands inside this test rather than after it.
      await screen.findByText(answered);
    },
  );

  it('GivenAPublicationRefusal_WhenThatRosterIsOpened_ThenTheRefusalStays', async () => {
    aDraft({
      '/api/rosters': { status: 200, body: [ROSTER, NEXT_WEEK] },
      'POST /api/rosters/roster-1/publication': RULE_BREACH,
    });
    renderPage();
    const table = await theRostersTable();
    await userEvent.click(
      screen.getByRole('button', { name: startingWith('Publish roster ', labelOf(ROSTER)) }),
    );
    const refusal = await within(table).findByRole('alert');

    await userEvent.click(
      screen.getByRole('button', { name: startingWith('Open roster ', labelOf(ROSTER)) }),
    );

    await screen.findByRole('region', { name: THE_BOARD });
    expect(refusal, 'the board opened to fix what the refusal names').toBeInTheDocument();
  });

  it('GivenAPublicationRefusal_WhenAnotherRosterIsOpened_ThenTheRefusalIsGone', async () => {
    aDraft({
      '/api/rosters': { status: 200, body: [ROSTER, NEXT_WEEK] },
      '/api/rosters/roster-2': { status: 200, body: NEXT_WEEK },
      '/api/rosters/roster-2/assessment': {
        status: 200,
        body: { ...NOTHING_WRONG, roster: 'roster-2' },
      },
      'POST /api/rosters/roster-1/publication': RULE_BREACH,
    });
    renderPage();
    const table = await theRostersTable();
    await userEvent.click(
      screen.getByRole('button', { name: startingWith('Publish roster ', labelOf(ROSTER)) }),
    );
    await within(table).findByRole('alert');

    await userEvent.click(
      screen.getByRole('button', { name: startingWith('Open roster ', labelOf(NEXT_WEEK)) }),
    );

    await screen.findByRole('region', { name: THE_BOARD });
    expect(within(table).queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('the rosters table, for a screen-reader user', () => {
  it('GivenTheRostersTable_WhenRendered_ThenItHasAnAccessibleName', async () => {
    aDraft();
    renderPage();

    expect(await theRostersTable()).toHaveAccessibleName('Rosters');
  });

  /** C8: a wide table scrolls in a region a keyboard can reach and a screen reader can name. */
  it('GivenTheRostersTable_WhenRendered_ThenItSitsInANamedFocusableScrollRegion', async () => {
    aDraft();
    renderPage();
    const table = await theRostersTable();

    const region = screen.getByRole('region', { name: 'Rosters' });

    expect(region).toHaveAttribute('tabindex', '0');
    expect(region).toContainElement(table);
  });

  it('GivenNoRosterInTheSpan_WhenListed_ThenItSaysThereAreNoneAndShowsNoEmptyTable', async () => {
    aDraft({ '/api/rosters': { status: 200, body: [] } });
    renderPage();

    expect(await screen.findByText('No rosters yet.')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(
      screen.queryByRole('region', { name: 'Rosters' }),
      'no empty region to tab into',
    ).not.toBeInTheDocument();
  });
});

describe('the board, for a keyboard and screen-reader user', () => {
  const SHIFT_LABEL = `${timeLabel(SHIFT.start)} to ${timeLabel(SHIFT.end)} on ${dayLabel(SHIFT.start)}`;

  /** A second shift, a day later and empty, so a refusal of the first can be told from it. */
  const NEXT_DAY = {
    ...SHIFT,
    id: 'shift-2',
    start: '2026-11-04T08:00:00Z',
    end: '2026-11-04T16:00:00Z',
  };

  /** The picker of the fixture's first shift, by the name only that shift's picker has. */
  function pickerOfTheShift(board: HTMLElement): Promise<HTMLElement> {
    return within(board).findByRole('combobox', { name: `Who to assign to ${SHIFT_LABEL}` });
  }

  /** The first shift's list item: the one item of the board that holds its picker. */
  async function itemOfTheShift(board: HTMLElement): Promise<HTMLElement> {
    const picker = await pickerOfTheShift(board);
    const holding = within(board)
      .getAllByRole('listitem')
      .filter((item) => item.contains(picker));
    // The innermost item that holds it: a shift is an item of its day's list.
    const item = holding.at(-1);
    if (item === undefined) {
      throw new Error('the shift is not a list item');
    }
    return item;
  }

  /** Puts Grace on the first shift of a board that has two. */
  async function assignGraceToTheShift(board: HTMLElement) {
    const picker = await pickerOfTheShift(board);
    await waitFor(() => expect(within(picker).getAllByRole('option')).toHaveLength(3));
    await userEvent.selectOptions(picker, 'emp-2');
    await userEvent.click(within(board).getByRole('button', { name: `Assign to ${SHIFT_LABEL}` }));
  }

  it('GivenARemovalInFlight_WhenRemoveIsPressed_ThenTheBoardsControlsAreAriaDisabledAndRemoveKeepsFocus', async () => {
    const routed = aDraft({
      'DELETE /api/rosters/roster-1/assignments/shift-1/emp-1': {
        status: 200,
        body: { ...ROSTER, assignments: [] },
      },
    });
    const release = holdingWrites(routed);
    renderPage();
    const board = await openBoard();
    const remove = await within(board).findByRole('button', { name: /^Remove Ada from/ });

    await userEvent.click(remove);
    await userEvent.click(remove);

    expectWaiting(remove);
    expect(remove, 'the pressed Remove keeps the keyboard focus').toHaveFocus();
    expectWaiting(within(board).getByRole('button', { name: /^Assign to/ }));
    expectWaiting(within(board).getByRole('combobox', { name: /^Who to assign/ }));
    release();
    await within(board).findByText(/0 of 1 assigned/);
    expect(
      callsTo(routed, 'DELETE', '/api/rosters/roster-1/assignments/shift-1/emp-1'),
    ).toHaveLength(1);
  });

  /** C2: Remove goes with the person it removed, so focus moves to where the next one is chosen. */
  it('GivenARemovalStored_WhenItsButtonGoes_ThenFocusIsOnThatShiftsPicker', async () => {
    aDraft({
      '/api/shifts': { status: 200, body: [SHIFT, NEXT_DAY] },
      'DELETE /api/rosters/roster-1/assignments/shift-1/emp-1': {
        status: 200,
        body: { ...ROSTER, assignments: [] },
      },
    });
    renderPage();
    const board = await openBoard();
    const picker = await pickerOfTheShift(board);

    await userEvent.click(await within(board).findByRole('button', { name: /^Remove Ada from/ }));

    await waitFor(() =>
      expect(
        within(board).queryByRole('button', { name: /^Remove Ada from/ }),
      ).not.toBeInTheDocument(),
    );
    await waitFor(() => expect(picker).toHaveFocus());
  });

  it('GivenARemovalStored_WhenTheServerAnswers_ThenAStatusSaysWhoWasRemovedFromWhat', async () => {
    aDraft({
      'DELETE /api/rosters/roster-1/assignments/shift-1/emp-1': {
        status: 200,
        body: { ...ROSTER, assignments: [] },
      },
    });
    renderPage();
    const board = await openBoard();

    await userEvent.click(await within(board).findByRole('button', { name: /^Remove Ada from/ }));

    expect(await findStatus(/removed .* from/i)).toHaveTextContent('Ada');
  });

  it('GivenAnAssignmentStored_WhenTheServerAnswers_ThenAStatusSaysWhoWasAssignedToWhat', async () => {
    aDraft({ 'POST /api/rosters/roster-1/assignments': { status: 200, body: WITH_GRACE } });
    renderPage();
    const board = await openBoard();

    await assignGrace(board);

    expect(await findStatus(/assigned .* to/i)).toHaveTextContent('Grace');
  });

  it('GivenAnAssignmentRefused_WhenShown_ThenTheRefusalIsInsideThatShift', async () => {
    aDraft({
      '/api/shifts': { status: 200, body: [SHIFT, NEXT_DAY] },
      'POST /api/rosters/roster-1/assignments': {
        status: 422,
        body: { detail: 'Grace does not hold every qualification the shift requires' },
      },
    });
    renderPage();
    const board = await openBoard();

    await assignGraceToTheShift(board);

    const item = await itemOfTheShift(board);
    expect(await within(item).findByRole('alert')).toHaveTextContent(
      'does not hold every qualification the shift requires',
    );
    for (const alert of within(board).getAllByRole('alert')) {
      expect(item, 'no copy of the refusal at the top of the board').toContainElement(alert);
    }
  });

  it('GivenAnAssignmentConflict_WhenShown_ThenTheRefusalIsInsideThatShift', async () => {
    aDraft({ '/api/shifts': { status: 200, body: [SHIFT, NEXT_DAY] } });
    renderPage();
    const board = await openBoard();
    aDraft({
      '/api/shifts': { status: 200, body: [SHIFT, NEXT_DAY] },
      '/api/rosters': { status: 200, body: [WITH_GRACE] },
      '/api/rosters/roster-1': { status: 200, body: WITH_GRACE },
      'POST /api/rosters/roster-1/assignments': {
        status: 409,
        body: {
          title: 'Already assigned',
          detail: 'That employee is already assigned to that shift on this roster.',
        },
      },
    });

    await assignGraceToTheShift(board);

    await within(board).findByRole('button', { name: /^Remove Grace from/ });
    const item = await itemOfTheShift(board);
    expect(within(item).getByRole('alert')).toHaveTextContent(
      'That employee is already assigned to that shift on this roster.',
    );
    for (const alert of within(board).getAllByRole('alert')) {
      expect(item, 'no copy of the refusal at the top of the board').toContainElement(alert);
    }
  });

  /** N5: the form that had focus went with the draft, so focus goes to the board's heading. */
  it('GivenADraftPublishedMeanwhile_WhenTheAssignFormGoes_ThenFocusMovesToTheBoardsHeading', async () => {
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

    await within(board).findByRole('heading', { name: /^Roster published/ });
    expect(within(board).getByRole('alert')).toHaveTextContent(
      'a roster can only be changed while a draft',
    );
    await waitFor(() => expect(headingOf(board)).toHaveFocus());
  });

  it('GivenAnAssignmentRefused_WhenTheAnswerArrives_ThenTheControlsCanActAgainAndAssignKeepsFocus', async () => {
    aDraft({
      'POST /api/rosters/roster-1/assignments': {
        status: 422,
        body: { detail: 'Grace does not hold every qualification the shift requires' },
      },
    });
    renderPage();
    const board = await openBoard();

    await assignGrace(board);

    await within(board).findByRole('alert');
    const assign = within(board).getByRole('button', { name: /^Assign to/ });
    expectActionable(assign);
    expectActionable(within(board).getByRole('button', { name: /^Remove Ada from/ }));
    expectActionable(within(board).getByRole('combobox', { name: /^Who to assign/ }));
    expect(assign, 'a refusal leaves focus where it was').toHaveFocus();
  });

  it('GivenARefusedAssignment_WhenTheNextChangeIsStored_ThenTheShiftsRefusalIsGone', async () => {
    const server: Record<string, Answer> = {
      '/api/rosters': { status: 200, body: [ROSTER] },
      '/api/rosters/roster-1': { status: 200, body: ROSTER },
      '/api/rosters/roster-1/assessment': { status: 200, body: NOTHING_WRONG },
      '/api/shifts': { status: 200, body: [SHIFT] },
      '/api/qualifications': { status: 200, body: [FORKLIFT] },
      'POST /api/rosters/roster-1/assignments': {
        status: 422,
        body: { detail: 'Grace does not hold every qualification the shift requires' },
      },
      'DELETE /api/rosters/roster-1/assignments/shift-1/emp-1': {
        status: 200,
        body: { ...ROSTER, assignments: [] },
      },
    };
    routeTo(server);
    renderPage();
    const board = await openBoard();
    await assignGrace(board);
    const item = await itemOfTheShift(board);
    await within(item).findByRole('alert');

    await userEvent.click(within(board).getByRole('button', { name: /^Remove Ada from/ }));

    await findStatus(/^Removed Ada from /);
    expect(within(item).queryByRole('alert')).not.toBeInTheDocument();
  });

  /** C9: picking the next person starts the next change, so the last one's status no longer stands. */
  it('GivenAnAssignmentStored_WhenTheShiftsPickerIsChangedAgain_ThenTheAssignedStatusIsCleared', async () => {
    aDraft({ 'POST /api/rosters/roster-1/assignments': { status: 200, body: WITH_GRACE } });
    renderPage();
    const board = await openBoard();
    await assignGrace(board);
    const status = await findStatus(/^Assigned Grace to /);

    await userEvent.selectOptions(
      within(board).getByRole('combobox', { name: /^Who to assign/ }),
      'emp-1',
    );

    expect(status, 'the live region stays, so the next outcome is announced').toHaveAttribute(
      'role',
      'status',
    );
    expect(status, 'the stored change is no longer claimed').toBeEmptyDOMElement();
  });

  /** C5: a control waiting for another's answer says why it cannot act yet. */
  it('GivenAChangeInFlight_WhenTheOtherControlsAreRead_ThenAVisibleHintSaysWhyTheyWait', async () => {
    const release = holdingWrites(
      aDraft({ 'POST /api/rosters/roster-1/assignments': { status: 200, body: WITH_GRACE } }),
    );
    renderPage();
    const board = await openBoard();

    await assignGrace(board);

    const hint = within(board).getByText(/^A change is on its way/);
    expect(hint).toBeVisible();
    const remove = within(board).getByRole('button', { name: /^Remove Ada from/ });
    expect(remove).toHaveAccessibleDescription(hint.textContent ?? '');
    expect(
      within(board).getByRole('combobox', { name: /^Who to assign/ }),
    ).toHaveAccessibleDescription(hint.textContent ?? '');
    release();
    await within(board).findByRole('button', { name: /^Remove Grace from/ });
    expect(within(board).queryByText(/^A change is on its way/)).not.toBeInTheDocument();
  });

  it('GivenFocusMovedWhileARemovalIsOut_WhenItIsStored_ThenFocusIsNotTaken', async () => {
    const release = holdingWrites(
      aDraft({
        'DELETE /api/rosters/roster-1/assignments/shift-1/emp-1': {
          status: 200,
          body: { ...ROSTER, assignments: [] },
        },
      }),
    );
    renderPage();
    const board = await openBoard();
    await userEvent.click(await within(board).findByRole('button', { name: /^Remove Ada from/ }));
    const elsewhere = within(board).getByRole('button', { name: /^Close/ });
    elsewhere.focus();

    release();

    await findStatus(/^Removed Ada from /);
    expect(elsewhere, 'focus stays where the user moved it').toHaveFocus();
  });
});
