import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { dayLabel, timeLabel } from '../components/localDates';
import { A_TENANT, SignedIn } from '../test-support/session';
import { MePage } from './MePage';

/**
 * Employee self-service, which is the one place the acting identity has to carry an employee link
 * (ADR-012). Without it the backend cannot tell that the caller is the subject of their own record,
 * so the page has to explain that rather than showing a form that will always be refused.
 *
 * The span is validated here rather than by the browser: neither availability button submits the
 * form, so the `required` attributes never run and an empty field would otherwise reach
 * `new Date('')` and surface to the user as a bare "Invalid time value".
 *
 * Their own work is published rosters only (F5). A shift is handed to a colleague by pasting that
 * colleague's employee identifier, because an employee may not list colleagues and so has no names
 * to pick from (Q25).
 */

const EMPLOYEE = '11111111-1111-4111-8111-111111111111';
const COLLEAGUE = '44444444-4444-4444-8444-444444444444';

const SPAN = { from: '2026-10-05T00:00:00.000Z', to: '2026-10-19T00:00:00.000Z' };

const WORK = {
  shift: 'shift-1',
  start: '2026-10-06T08:00:00Z',
  end: '2026-10-06T16:00:00Z',
  roster: 'roster-1',
};

interface Answer {
  readonly status: number;
  readonly body: unknown;
}

/**
 * Reading one's own schedule is answered with `schedule` and every write with `write`. Any other read
 * — the inbox on the same page — gets an empty list: what it does with one is Inbox.test's business.
 */
function stubbed({
  schedule = { status: 200, body: [] },
  write = { status: 204, body: null },
}: { schedule?: Answer; write?: Answer } = {}) {
  const stub = vi.fn((path: string, init?: RequestInit) => {
    const answer =
      (init?.method ?? 'GET') !== 'GET'
        ? write
        : path.startsWith('/api/me/schedule')
          ? schedule
          : { status: 200, body: [] };
    return Promise.resolve({
      ok: answer.status < 400,
      status: answer.status,
      headers: new Headers(),
      text: () => Promise.resolve(answer.body === null ? '' : JSON.stringify(answer.body)),
    });
  });
  vi.stubGlobal('fetch', stub);
  return stub;
}

function writes(stub: ReturnType<typeof stubbed>) {
  return stub.mock.calls.filter(([, init]) => (init?.method ?? 'GET') !== 'GET');
}

function bodyOfFirstWrite(stub: ReturnType<typeof stubbed>): Record<string, unknown> {
  const init = writes(stub)[0]?.[1];
  if (typeof init?.body !== 'string') {
    throw new Error('nothing was sent');
  }
  return JSON.parse(init.body) as Record<string, unknown>;
}

/**
 * Which personnel record the signed-in account is, if any.
 *
 * It comes from the session rather than from anything typed: a user is not an employee, and the
 * two are linked by the account (ADR-012). An account with no link has nobody for "my" schedule to
 * be about, which is the case several tests below are here for. Only a Scheduler or an Admin can be
 * that account: an Employee account is always linked (R34).
 */
let employeeLink: string | null = null;

function actingAsEmployee(employee: string | null) {
  employeeLink = employee;
}

function show() {
  render(
    <SignedIn
      session={{
        ...A_TENANT,
        role: employeeLink === null ? 'SCHEDULER' : 'EMPLOYEE',
        employee: employeeLink,
      }}
    >
      <MePage span={SPAN} people={[]} />
    </SignedIn>,
  );
}

async function fillSpan(from: string, to: string) {
  if (from !== '') {
    await userEvent.type(screen.getByLabelText('From'), from);
  }
  if (to !== '') {
    await userEvent.type(screen.getByLabelText('To'), to);
  }
}

beforeEach(() => {
  window.localStorage.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
});

describe('without an employee link', () => {
  it('GivenNoEmployeeLink_WhenOpened_ThenItExplainsWhatToDoRatherThanShowingAForm', () => {
    actingAsEmployee(null);
    stubbed();

    show();

    expect(screen.getByText(/not linked to a personnel record/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'I cannot work this' })).not.toBeInTheDocument();
  });

  it('GivenNoEmployeeLink_WhenOpened_ThenItSaysWhyTheLinkIsNeeded', () => {
    actingAsEmployee(null);
    stubbed();

    show();

    expect(screen.getByText(/different identities on purpose/i)).toBeInTheDocument();
  });

  /** Asking for "my" schedule without saying who "me" is would only earn a refusal. */
  it('GivenNoEmployeeLink_WhenOpened_ThenNothingIsAskedOfTheBackend', () => {
    actingAsEmployee(null);
    const stub = stubbed();

    show();

    expect(stub).not.toHaveBeenCalled();
  });
});

describe('who they are', () => {
  it('GivenAnEmployeeLink_WhenOpened_ThenTheirOwnIdentifierIsShownToShare', () => {
    actingAsEmployee(EMPLOYEE);
    stubbed();

    show();

    expect(screen.getByText(EMPLOYEE)).toBeInTheDocument();
  });
});

describe('their own work', () => {
  it('GivenAPlanningSpan_WhenOpened_ThenTheirScheduleIsAskedForOverIt', async () => {
    actingAsEmployee(EMPLOYEE);
    const stub = stubbed();

    show();

    await waitFor(() =>
      expect(stub.mock.calls.map(([path]) => path)).toContain(
        `/api/me/schedule?from=${encodeURIComponent(SPAN.from)}&to=${encodeURIComponent(SPAN.to)}`,
      ),
    );
  });

  it('GivenPublishedWork_WhenOpened_ThenEachShiftTheyWorkIsListed', async () => {
    actingAsEmployee(EMPLOYEE);
    stubbed({ schedule: { status: 200, body: [WORK] } });

    show();

    expect(
      await screen.findByText(
        `${timeLabel(WORK.start)} to ${timeLabel(WORK.end)} on ${dayLabel(WORK.start)}`,
      ),
    ).toBeInTheDocument();
  });

  it('GivenNoPublishedWork_WhenOpened_ThenItSaysSoRatherThanShowingNothing', async () => {
    actingAsEmployee(EMPLOYEE);
    stubbed();

    show();

    expect(await screen.findByText(/Nothing published names you/)).toBeInTheDocument();
  });

  /**
   * The link is a claim over headers. When the server has no such person, a refusal is the truth and
   * an empty week would be a lie that looks exactly like a quiet fortnight.
   */
  it('GivenNoPersonnelRecordOnTheServer_WhenOpened_ThenTheRefusalIsShownRatherThanAnEmptyWeek', async () => {
    actingAsEmployee(EMPLOYEE);
    stubbed({
      schedule: {
        status: 403,
        body: { detail: 'You have no personnel record in this organisation.' },
      },
    });

    show();

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('no personnel record'));
    expect(screen.queryByText(/Nothing published names you/)).not.toBeInTheDocument();
  });
});

describe('handing a shift to a colleague', () => {
  it('GivenAColleaguesIdentifier_WhenHandingAShiftOver_ThenRosterShiftRequesterAndCounterpartyAreSent', async () => {
    actingAsEmployee(EMPLOYEE);
    const stub = stubbed({
      schedule: { status: 200, body: [WORK] },
      write: { status: 201, body: { id: 'swap-1', status: 'REQUESTED' } },
    });
    show();

    await userEvent.type(
      await screen.findByLabelText(/^Colleague's employee identifier/),
      COLLEAGUE,
    );
    await userEvent.click(screen.getByRole('button', { name: /^Ask to hand/ }));

    await waitFor(() =>
      expect(bodyOfFirstWrite(stub)).toEqual({
        roster: 'roster-1',
        shift: 'shift-1',
        requester: EMPLOYEE,
        counterparty: COLLEAGUE,
      }),
    );
    expect(writes(stub)[0]?.[0]).toBe('/api/swaps');
    await waitFor(() => expect(screen.getByText(/^Asked\./)).toHaveAttribute('role', 'status'));
  });

  /** Left filled in, the field invites the same request again, and each one is stored. */
  it('GivenAHandOverThatSucceeds_WhenItIsAnnounced_ThenTheFieldIsClearedSoItIsNotAskedTwice', async () => {
    actingAsEmployee(EMPLOYEE);
    stubbed({
      schedule: { status: 200, body: [WORK] },
      write: { status: 201, body: { id: 'swap-1', status: 'REQUESTED' } },
    });
    show();
    const field = await screen.findByLabelText(/^Colleague's employee identifier/);

    await userEvent.type(field, COLLEAGUE);
    await userEvent.click(screen.getByRole('button', { name: /^Ask to hand/ }));

    await screen.findByText(/^Asked\./);
    expect(field).toHaveValue('');
  });

  it('GivenAHandOverInFlight_WhenAskedAgain_ThenOnlyOneRequestIsSent', async () => {
    actingAsEmployee(EMPLOYEE);
    const routed = stubbed({
      schedule: { status: 200, body: [WORK] },
      write: { status: 201, body: { id: 'swap-1', status: 'REQUESTED' } },
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
    show();
    await userEvent.type(
      await screen.findByLabelText(/^Colleague's employee identifier/),
      COLLEAGUE,
    );
    const ask = screen.getByRole('button', { name: /^Ask to hand/ });

    await userEvent.click(ask);
    await userEvent.click(ask);

    expect(ask).toBeDisabled();
    release();
    await screen.findByText(/^Asked\./);
    expect(writes(routed)).toHaveLength(1);
  });

  it('GivenNoColleagueIdentifier_WhenHandingAShiftOver_ThenNothingIsSent', async () => {
    actingAsEmployee(EMPLOYEE);
    const stub = stubbed({ schedule: { status: 200, body: [WORK] } });
    show();

    await userEvent.click(await screen.findByRole('button', { name: /^Ask to hand/ }));

    expect(screen.getByRole('alert')).toHaveTextContent('Give the identifier of the colleague');
    expect(writes(stub)).toHaveLength(0);
  });

  it('GivenTheBackendRefusesTheSwap_WhenHandingAShiftOver_ThenTheRefusalIsShown', async () => {
    actingAsEmployee(EMPLOYEE);
    stubbed({
      schedule: { status: 200, body: [WORK] },
      write: { status: 400, body: { detail: 'Nobody can swap a shift with themself.' } },
    });
    show();

    await userEvent.type(
      await screen.findByLabelText(/^Colleague's employee identifier/),
      EMPLOYEE,
    );
    await userEvent.click(screen.getByRole('button', { name: /^Ask to hand/ }));

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Nobody can swap a shift with themself.'),
    );
    expect(screen.queryByText(/^Asked\./)).not.toBeInTheDocument();
  });
});

describe('validating the span before anything is sent', () => {
  it('GivenBothFieldsBlank_WhenDeclaring_ThenItAsksForBothAndSendsNothing', async () => {
    actingAsEmployee(EMPLOYEE);
    const stub = stubbed();
    show();

    await userEvent.click(screen.getByRole('button', { name: 'I cannot work this' }));

    expect(screen.getByRole('alert')).toHaveTextContent('Give both a start and an end.');
    expect(writes(stub)).toHaveLength(0);
  });

  it('GivenOnlyAStart_WhenDeclaring_ThenItAsksForBothAndSendsNothing', async () => {
    actingAsEmployee(EMPLOYEE);
    const stub = stubbed();
    show();

    await fillSpan('2026-10-05T08:00', '');
    await userEvent.click(screen.getByRole('button', { name: 'I cannot work this' }));

    expect(screen.getByRole('alert')).toHaveTextContent('Give both a start and an end.');
    expect(writes(stub)).toHaveLength(0);
  });

  it('GivenAnEndBeforeItsStart_WhenDeclaring_ThenItIsRefusedBeforeAnyRequest', async () => {
    actingAsEmployee(EMPLOYEE);
    const stub = stubbed();
    show();

    await fillSpan('2026-10-05T16:00', '2026-10-05T08:00');
    await userEvent.click(screen.getByRole('button', { name: 'I cannot work this' }));

    expect(screen.getByRole('alert')).toHaveTextContent('The end has to come after the start.');
    expect(writes(stub)).toHaveLength(0);
  });

  it('GivenAnEndEqualToItsStart_WhenDeclaring_ThenItIsRefusedBeforeAnyRequest', async () => {
    actingAsEmployee(EMPLOYEE);
    const stub = stubbed();
    show();

    await fillSpan('2026-10-05T08:00', '2026-10-05T08:00');
    await userEvent.click(screen.getByRole('button', { name: 'I cannot work this' }));

    expect(screen.getByRole('alert')).toHaveTextContent('The end has to come after the start.');
    expect(writes(stub)).toHaveLength(0);
  });
});

describe('declaring unavailability', () => {
  it('GivenAValidSpan_WhenDeclaring_ThenTheRequestCarriesIsoInstants', async () => {
    actingAsEmployee(EMPLOYEE);
    const stub = stubbed();
    show();

    await fillSpan('2026-10-05T08:00', '2026-10-05T16:00');
    await userEvent.click(screen.getByRole('button', { name: 'I cannot work this' }));

    await waitFor(() => expect(writes(stub)).toHaveLength(1));
    const sent = bodyOfFirstWrite(stub);
    expect(sent.start).toMatch(/^\d{4}-\d{2}-\d{2}T.*Z$/);
    expect(sent.end).toMatch(/^\d{4}-\d{2}-\d{2}T.*Z$/);
  });

  it('GivenAValidSpan_WhenDeclaring_ThenItIsSentToTheEmployeesOwnRecord', async () => {
    actingAsEmployee(EMPLOYEE);
    const stub = stubbed();
    show();

    await fillSpan('2026-10-05T08:00', '2026-10-05T16:00');
    await userEvent.click(screen.getByRole('button', { name: 'I cannot work this' }));

    await waitFor(() => expect(writes(stub)).toHaveLength(1));
    expect(writes(stub)[0]?.[0]).toBe(`/api/employees/${EMPLOYEE}/unavailability`);
  });

  it('GivenTheBackendAccepts_WhenDeclaring_ThenItIsAnnouncedThatNobodyWillBeRosteredThen', async () => {
    actingAsEmployee(EMPLOYEE);
    stubbed();
    show();

    await fillSpan('2026-10-05T08:00', '2026-10-05T16:00');
    await userEvent.click(screen.getByRole('button', { name: 'I cannot work this' }));

    await waitFor(() =>
      expect(screen.getByText('Recorded: you cannot work then.')).toHaveAttribute('role', 'status'),
    );
  });

  it('GivenTheBackendRefuses_WhenDeclaring_ThenTheRefusalIsShownRatherThanASuccess', async () => {
    actingAsEmployee(EMPLOYEE);
    stubbed({
      write: { status: 400, body: { detail: 'That overlaps a span you already declared.' } },
    });
    show();

    await fillSpan('2026-10-05T08:00', '2026-10-05T16:00');
    await userEvent.click(screen.getByRole('button', { name: 'I cannot work this' }));

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent(
        'That overlaps a span you already declared.',
      ),
    );
    expect(screen.queryByText('Recorded: you cannot work then.')).not.toBeInTheDocument();
  });
});

describe('stating a preference', () => {
  it('GivenAChosenStrength_WhenStatingAPreference_ThenThatStrengthIsSent', async () => {
    actingAsEmployee(EMPLOYEE);
    const stub = stubbed();
    show();

    await fillSpan('2026-10-05T08:00', '2026-10-05T16:00');
    await userEvent.selectOptions(screen.getByLabelText('Preference'), 'PREFERS_NOT');
    await userEvent.click(screen.getByRole('button', { name: 'State a preference' }));

    await waitFor(() => expect(writes(stub)).toHaveLength(1));
    expect(bodyOfFirstWrite(stub).strength).toBe('PREFERS_NOT');
  });

  it('GivenAPreferenceRecorded_WhenShown_ThenItSaysItNeverBlocksARoster', async () => {
    actingAsEmployee(EMPLOYEE);
    stubbed();
    show();

    await fillSpan('2026-10-05T08:00', '2026-10-05T16:00');
    await userEvent.click(screen.getByRole('button', { name: 'State a preference' }));

    await waitFor(() =>
      expect(
        screen.getByText('Recorded: a preference, which never blocks a roster.'),
      ).toBeInTheDocument(),
    );
  });
});
