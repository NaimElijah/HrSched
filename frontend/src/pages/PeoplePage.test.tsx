import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { callsTo, holdingWrites, routeTo, writes } from '../test-support/routeTo';
import type { Answer } from '../test-support/routeTo';
import type { Session } from '../api/session';
import { A_SCHEDULER, A_TENANT, SignedIn } from '../test-support/session';
import { PeoplePage } from './PeoplePage';
import { WITHOUT_PAUSES } from '../test-support/typing';

/**
 * Admin: who works here, what they hold, and what they are contracted to.
 *
 * Listing people returns their unavailability, which is personal data, so the backend gates it
 * behind Admin or Scheduler. An EMPLOYEE sees a 403, and the page has to say so rather than show an
 * empty table — an empty table reads as "nobody works here", which is a different and wrong claim.
 *
 * Every contracted number here is invented, and odd on purpose so that it matches no statute.
 */

/** Invented minutes a week, Ada's stored contract. */
const ADAS_CONTRACT = 2101;
/** Invented minutes a week, the contract typed in its place. */
const TYPED_CONTRACT = 2401;

const ABSENCE = { start: '2026-10-07T08:00:00Z', end: '2026-10-07T12:00:00Z' };

/** The full shape the API sends: F4 is exactly the fields a thinner fixture would leave out. */
const PEOPLE = [
  {
    id: 'e-1',
    name: 'Ada',
    qualifications: ['q-1'],
    unavailability: [ABSENCE],
    weeklyContractedMinutes: ADAS_CONTRACT,
  },
  {
    id: 'e-2',
    name: 'Grace',
    qualifications: [],
    unavailability: [],
    weeklyContractedMinutes: null,
  },
];

function rowOf(name: string): HTMLElement {
  const row = screen.getByText(name).closest('tr');
  if (row === null) {
    throw new Error(`${name} is not in a table row`);
  }
  return row;
}

const QUALIFICATIONS = [{ id: 'q-1', name: 'Forklift' }];

/** Answers the two listings, and accepts every change the tests below make, unless overridden. */
function theOrganisation(overrides: Record<string, Answer> = {}) {
  return routeTo({
    '/api/employees': { status: 200, body: PEOPLE },
    '/api/qualifications': { status: 200, body: QUALIFICATIONS },
    'POST /api/employees': {
      status: 201,
      body: {
        id: 'e-3',
        name: 'Marie',
        qualifications: [],
        unavailability: [],
        weeklyContractedMinutes: null,
      },
    },
    'PUT /api/employees/e-1/qualifications/q-1': { status: 204, body: null },
    'PUT /api/employees/e-1/contract': { status: 204, body: null },
    ...overrides,
  });
}

function show(session: Session = A_TENANT) {
  render(
    <SignedIn session={session}>
      <PeoplePage />
    </SignedIn>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('listing people', () => {
  it('GivenPeople_WhenOpened_ThenEachIsListedWithTheirIdentifier', async () => {
    theOrganisation();

    show();

    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());
    expect(screen.getByText('e-1')).toBeInTheDocument();
    expect(screen.getByText('Grace')).toBeInTheDocument();
  });

  it('GivenTheListingIs403_WhenOpened_ThenTheRefusalIsShownRatherThanAnEmptyTable', async () => {
    theOrganisation({
      '/api/employees': { status: 403, body: { detail: 'Your role does not permit this.' } },
    });

    show();

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Your role may not do this.'),
    );
  });
});

/**
 * Requirement F4: a Scheduler sees the availability of every employee. The listing always carried
 * it; the page counted qualifications and showed nothing else.
 */
describe('what is already known about each person', () => {
  it('GivenQualificationsHeld_WhenListed_ThenTheirNamesAreShown', async () => {
    theOrganisation();

    show();

    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());
    expect(within(rowOf('Ada')).getByText('Forklift', { selector: 'td' })).toBeInTheDocument();
  });

  it('GivenDeclaredUnavailability_WhenListed_ThenEachSpanIsShown', async () => {
    theOrganisation();

    show();

    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());
    const spans = within(rowOf('Ada')).getAllByRole('listitem');
    expect(spans).toHaveLength(1);
    expect(spans[0]).toHaveTextContent(new Date(ABSENCE.start).toLocaleString());
    expect(spans[0]).toHaveTextContent(new Date(ABSENCE.end).toLocaleString());
    expect(within(rowOf('Grace')).queryAllByRole('listitem')).toHaveLength(0);
  });

  /**
   * "None set" rather than 0, which would claim a contract of no hours. And the stored value is
   * shown beside the field, never put in it: a prefilled number is one an Admin can submit without
   * having chosen it.
   */
  it('GivenNoContract_WhenListed_ThenItSaysNoneRatherThanZero', async () => {
    theOrganisation();

    show();

    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());
    expect(within(rowOf('Grace')).getByText('None set')).toBeInTheDocument();
    expect(within(rowOf('Ada')).getByText(`${ADAS_CONTRACT} minutes a week`)).toBeInTheDocument();
    expect(screen.getByLabelText('Contracted minutes per week for Ada')).toHaveValue(null);
  });
});

describe('adding an employee', () => {
  /** The new person may sort anywhere in the table, so the page says what it did. */
  it('GivenAName_WhenAdded_ThenItSaysSo', async () => {
    theOrganisation();
    show();
    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());

    await userEvent.type(screen.getByLabelText('Name'), 'Marie', WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Add employee' }));

    expect(await screen.findByText('Added Marie.')).toHaveAttribute('role', 'status');
  });

  it('GivenAName_WhenAdded_ThenItIsSentAndTheFieldClears', async () => {
    const stub = theOrganisation();
    show();
    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());

    await userEvent.type(screen.getByLabelText('Name'), 'Marie', WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Add employee' }));

    await waitFor(() => expect(callsTo(stub, 'POST', '/api/employees')).toHaveLength(1));
    const init = callsTo(stub, 'POST', '/api/employees')[0]?.[1];
    expect(JSON.parse(String(init?.body))).toEqual({ name: 'Marie' });
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue(''));
  });

  it('GivenTheBackendRefuses_WhenAdding_ThenTheRefusalIsShownAndTheNameSurvives', async () => {
    // Adding someone is an Admin's, so a Scheduler is who the backend refuses.
    theOrganisation({
      'POST /api/employees': { status: 403, body: { detail: 'Your role does not permit this.' } },
    });
    show(A_SCHEDULER);
    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());

    await userEvent.type(screen.getByLabelText('Name'), 'Marie', WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Add employee' }));

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Your role may not do this.'),
    );
    expect(screen.getByLabelText('Name')).toHaveValue('Marie');
    await waitFor(() => {
      const add = screen.getByRole('button', { name: 'Add employee' });
      expect(add).toBeEnabled();
      expect(add).not.toHaveAttribute('aria-disabled', 'true');
    });
  });

  /** Each addition is a person of their own, and none can be deleted afterwards. */
  it('GivenAnAdditionInFlight_WhenAddedAgain_ThenOnlyOneEmployeeIsPosted', async () => {
    const routed = theOrganisation();
    const release = holdingWrites(routed);
    show();
    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());
    await userEvent.type(screen.getByLabelText('Name'), 'Marie', WITHOUT_PAUSES);
    const add = screen.getByRole('button', { name: 'Add employee' });

    await userEvent.click(add);
    await userEvent.click(add);

    expect(add).toHaveAttribute('aria-disabled', 'true');
    release();
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue(''));
    expect(writes(routed)).toHaveLength(1);
    await waitFor(() => expect(add).not.toHaveAttribute('aria-disabled', 'true'));
  });

  /**
   * A disabled button drops the keyboard focus to the page in a browser (#257, C1). While the
   * addition is on its way the button says it is unavailable and keeps the focus.
   */
  it('GivenAnAdditionInFlight_WhenPressed_ThenTheButtonKeepsFocusAndIsMarkedUnavailable', async () => {
    const routed = theOrganisation();
    const release = holdingWrites(routed);
    show();
    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());
    await userEvent.type(screen.getByLabelText('Name'), 'Marie', WITHOUT_PAUSES);
    const add = screen.getByRole('button', { name: 'Add employee' });

    await userEvent.click(add);

    expect(add).toHaveAttribute('aria-disabled', 'true');
    expect(add).not.toBeDisabled();
    expect(add).toHaveFocus();
    release();
    await screen.findByText('Added Marie.');
  });

  /** A success left on screen while the next name is typed reads as that one's (C9). */
  it('GivenAnAdditionSucceeded_WhenTheFormIsEditedAgain_ThenTheSuccessIsNoLongerSaid', async () => {
    theOrganisation();
    show();
    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());
    await userEvent.type(screen.getByLabelText('Name'), 'Marie', WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Add employee' }));
    await screen.findByText('Added Marie.');

    await userEvent.type(screen.getByLabelText('Name'), 'L', WITHOUT_PAUSES);

    expect(screen.queryByText('Added Marie.')).not.toBeInTheDocument();
  });
});

describe('granting a qualification', () => {
  /** The picker empties once a grant is stored, so the page says what was granted, and to whom. */
  it('GivenAQualificationGranted_WhenItSucceeds_ThenItSaysSo', async () => {
    theOrganisation();
    show();
    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());

    await userEvent.selectOptions(screen.getByLabelText('Grant a qualification to Ada'), 'q-1');
    await userEvent.click(screen.getByRole('button', { name: 'Grant to Ada · e-1' }));

    expect(await screen.findByText('Granted Forklift to Ada.')).toHaveAttribute('role', 'status');
  });

  /** Two people can share a name, and a grant pressed on the wrong one cannot be revoked. */
  it('GivenTwoPeopleSharingAName_WhenListed_ThenEachGrantIsNamedByIdentifierToo', async () => {
    theOrganisation({
      '/api/employees': {
        status: 200,
        body: [...PEOPLE, { ...PEOPLE[1], id: 'e-9', name: 'Ada' }],
      },
    });
    show();
    await waitFor(() => expect(screen.getAllByText('Ada')).toHaveLength(2));

    expect(screen.getByRole('button', { name: 'Grant to Ada · e-1' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Grant to Ada · e-9' })).toBeInTheDocument();
  });

  it('GivenAQualificationChosen_WhenGranting_ThenItIsSentForThatEmployee', async () => {
    const stub = theOrganisation();
    show();
    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());

    await userEvent.selectOptions(screen.getByLabelText('Grant a qualification to Ada'), 'q-1');
    await userEvent.click(screen.getByRole('button', { name: 'Grant to Ada · e-1' }));

    await waitFor(() =>
      expect(callsTo(stub, 'PUT', '/api/employees/e-1/qualifications/q-1')).toHaveLength(1),
    );
    await waitFor(() => {
      const grant = screen.getByRole('button', { name: 'Grant to Ada · e-1' });
      expect(grant).toBeEnabled();
      expect(grant).not.toHaveAttribute('aria-disabled', 'true');
    });
  });

  /**
   * There is no revoking a qualification yet (F24, #86), and with the keyboard a closed select
   * changes on every arrow key in some browsers. Choosing one grants nothing until it is submitted.
   */
  it('GivenAQualificationChosen_WhenNotYetGranted_ThenNothingIsSent', async () => {
    const stub = theOrganisation();
    show();
    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());

    await userEvent.selectOptions(screen.getByLabelText('Grant a qualification to Ada'), 'q-1');

    expect(writes(stub)).toHaveLength(0);
  });

  /** The placeholder option is not a qualification; granting it must not send anything. */
  it('GivenTheChoosePlaceholder_WhenGranting_ThenNothingIsSent', async () => {
    const stub = theOrganisation();
    show();
    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());

    await userEvent.selectOptions(screen.getByLabelText('Grant a qualification to Ada'), '');
    await userEvent.click(screen.getByRole('button', { name: 'Grant to Ada · e-1' }));

    expect(writes(stub)).toHaveLength(0);
  });

  it('GivenTheBackendRefusesAGrant_WhenGranting_ThenTheRefusalIsShown', async () => {
    theOrganisation({
      'PUT /api/employees/e-1/qualifications/q-1': {
        status: 404,
        body: { detail: 'No such resource.' },
      },
    });
    show();
    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());

    await userEvent.selectOptions(screen.getByLabelText('Grant a qualification to Ada'), 'q-1');
    await userEvent.click(screen.getByRole('button', { name: 'Grant to Ada · e-1' }));

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Nothing here for this organisation.'),
    );
    await waitFor(() => {
      const grant = screen.getByRole('button', { name: 'Grant to Ada · e-1' });
      expect(grant).toBeEnabled();
      expect(grant).not.toHaveAttribute('aria-disabled', 'true');
    });
  });

  /**
   * A disabled button drops the keyboard focus to the page in a browser (#257, C1), and a grant
   * cannot be revoked, so a second one sent meanwhile is one nobody can take back.
   */
  it('GivenAGrantInFlight_WhenPressedAgain_ThenTheButtonKeepsFocusAndOnlyOneIsSent', async () => {
    const routed = theOrganisation();
    const release = holdingWrites(routed);
    show();
    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());
    await userEvent.selectOptions(screen.getByLabelText('Grant a qualification to Ada'), 'q-1');
    const grant = screen.getByRole('button', { name: 'Grant to Ada · e-1' });

    await userEvent.click(grant);
    await userEvent.click(grant);

    expect(grant).toHaveAttribute('aria-disabled', 'true');
    expect(grant).not.toBeDisabled();
    expect(grant).toHaveFocus();
    release();
    await screen.findByText('Granted Forklift to Ada.');
    expect(writes(routed)).toHaveLength(1);
  });

  /** A refusal at the top of the page says nothing about whose row it belongs to (#257, C6). */
  it('GivenTheBackendRefusesAGrant_WhenShown_ThenTheRefusalIsInThePeopleTable', async () => {
    theOrganisation({
      'PUT /api/employees/e-1/qualifications/q-1': {
        status: 404,
        body: { detail: 'No such resource.' },
      },
    });
    show();
    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());

    await userEvent.selectOptions(screen.getByLabelText('Grant a qualification to Ada'), 'q-1');
    await userEvent.click(screen.getByRole('button', { name: 'Grant to Ada · e-1' }));

    expect(await within(screen.getByRole('table')).findByRole('alert')).toHaveTextContent(
      'Nothing here for this organisation.',
    );
    expect(screen.getAllByRole('alert'), 'every alert on the page').toHaveLength(1);
    expect(within(rowOf('Grace')).queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('setting contracted hours', () => {
  /** One name repeated on every row gave a screen reader no way to tell whose field it was in. */
  it('GivenSeveralPeople_WhenListed_ThenEachContractFieldIsNamedForItsEmployee', async () => {
    theOrganisation();
    show();
    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());

    expect(screen.getByLabelText('Contracted minutes per week for Ada')).toBeInTheDocument();
    expect(screen.getByLabelText('Contracted minutes per week for Grace')).toBeInTheDocument();
  });

  it('GivenTypedMinutes_WhenSet_ThenTheyAreSentAsANumber', async () => {
    const stub = theOrganisation();
    show();
    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());

    await userEvent.type(
      screen.getByLabelText('Contracted minutes per week for Ada'),
      String(TYPED_CONTRACT),
      WITHOUT_PAUSES,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Set contracted minutes for Ada' }));

    await waitFor(() =>
      expect(callsTo(stub, 'PUT', '/api/employees/e-1/contract')).toHaveLength(1),
    );
    const init = callsTo(stub, 'PUT', '/api/employees/e-1/contract')[0]?.[1];
    expect(JSON.parse(String(init?.body))).toEqual({ weeklyContractedMinutes: TYPED_CONTRACT });
  });

  it('GivenTheBackendRefusesTheTerms_WhenSet_ThenTheRefusalIsShown', async () => {
    theOrganisation({
      // Setting terms is an Admin's. The field's own `min` stops a non-positive value before the
      // backend could refuse it as invalid, so the refusal that can reach this page is the role's.
      'PUT /api/employees/e-1/contract': {
        status: 403,
        body: { detail: 'Your role does not permit this.' },
      },
    });
    show(A_SCHEDULER);
    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());

    await userEvent.type(
      screen.getByLabelText('Contracted minutes per week for Ada'),
      '5',
      WITHOUT_PAUSES,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Set contracted minutes for Ada' }));

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Your role does not permit this.'),
    );
  });

  /** One name repeated on every row gave a screen reader no way to tell whose button it was (C7). */
  it('GivenSeveralPeople_WhenListed_ThenEachSetButtonIsNamedForItsEmployee', async () => {
    theOrganisation();
    show();
    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());

    expect(
      within(rowOf('Ada')).getByRole('button', { name: 'Set contracted minutes for Ada' }),
    ).toBeInTheDocument();
    expect(
      within(rowOf('Grace')).getByRole('button', { name: 'Set contracted minutes for Grace' }),
    ).toBeInTheDocument();
  });

  /**
   * A disabled button drops the keyboard focus to the page in a browser (C1), and a second
   * contract sent meanwhile is a second change nobody made.
   */
  it('GivenAContractInFlight_WhenPressedAgain_ThenTheButtonKeepsFocusAndOnlyOneIsSent', async () => {
    const routed = theOrganisation();
    const release = holdingWrites(routed);
    show();
    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());
    await userEvent.type(
      screen.getByLabelText('Contracted minutes per week for Ada'),
      String(TYPED_CONTRACT),
      WITHOUT_PAUSES,
    );
    const set = within(rowOf('Ada')).getByRole('button', { name: /^Set/ });

    await userEvent.click(set);
    await userEvent.click(set);

    expect(set).toHaveAttribute('aria-disabled', 'true');
    expect(set).not.toBeDisabled();
    expect(set).toHaveFocus();
    release();
    await waitFor(() =>
      expect(callsTo(routed, 'PUT', '/api/employees/e-1/contract')).toHaveLength(1),
    );
    await waitFor(() => expect(set).not.toHaveAttribute('aria-disabled', 'true'));
    expect(writes(routed)).toHaveLength(1);
  });

  /** The field keeps no echo of what was stored, so without a word nothing says it was (C3). */
  it('GivenTypedMinutes_WhenSetSucceeds_ThenAStatusNamesTheContractAndThePerson', async () => {
    theOrganisation();
    show();
    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());
    const statuses = () => screen.getAllByRole('status').map((status) => status.textContent);

    await userEvent.type(
      screen.getByLabelText('Contracted minutes per week for Ada'),
      String(TYPED_CONTRACT),
      WITHOUT_PAUSES,
    );
    await userEvent.click(within(rowOf('Ada')).getByRole('button', { name: /^Set/ }));

    await waitFor(() =>
      expect(statuses()).toContainEqual(expect.stringMatching(/contracted minutes/i)),
    );
    expect(statuses().find((said) => /contracted minutes/i.test(said ?? ''))).toMatch(/Ada/);
  });

  /** A refusal at the top of the page says nothing about whose row it belongs to (#257, C6). */
  it('GivenTheBackendRefusesTheTerms_WhenShown_ThenTheRefusalIsInThePeopleTable', async () => {
    theOrganisation({
      'PUT /api/employees/e-1/contract': {
        status: 403,
        body: { detail: 'Your role does not permit this.' },
      },
    });
    show(A_SCHEDULER);
    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());

    await userEvent.type(
      screen.getByLabelText('Contracted minutes per week for Ada'),
      '5',
      WITHOUT_PAUSES,
    );
    await userEvent.click(within(rowOf('Ada')).getByRole('button', { name: /^Set/ }));

    expect(await within(screen.getByRole('table')).findByRole('alert')).toHaveTextContent(
      'Your role does not permit this.',
    );
    expect(screen.getAllByRole('alert'), 'every alert on the page').toHaveLength(1);
    expect(within(rowOf('Grace')).queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('the people as a table', () => {
  it('GivenPeople_WhenListed_ThenTheTableHasAName', async () => {
    theOrganisation();

    show();

    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());
    expect(screen.getByRole('table')).toHaveAccessibleName();
  });

  /** An empty table says nothing at all to somebody who cannot see that it is empty (C8). */
  it('GivenNobodyEmployed_WhenListed_ThenTheListSaysSo', async () => {
    theOrganisation({ '/api/employees': { status: 200, body: [] } });

    show();

    expect(await screen.findByText(/no people/i)).toBeInTheDocument();
  });

  /** "Nobody works here" is a claim, and a refused listing is no evidence for it. */
  it('GivenTheListingIsRefused_WhenShown_ThenItDoesNotSayThereIsNobody', async () => {
    theOrganisation({
      '/api/employees': { status: 403, body: { detail: 'Your role does not permit this.' } },
    });

    show();

    await screen.findByRole('alert');
    expect(screen.queryByText(/no people/i)).not.toBeInTheDocument();
  });
});
