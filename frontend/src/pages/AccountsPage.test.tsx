import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AccountsPage } from './AccountsPage';
import { callsTo, holdingWrites, routeTo, writes } from '../test-support/routeTo';
import type { Answer } from '../test-support/routeTo';
import { A_TENANT, SignedIn } from '../test-support/session';
import type { Session } from '../api/session';
import { WITHOUT_PAUSES } from '../test-support/typing';

/**
 * The screen an Admin administers colleagues from (requirement F15).
 *
 * Nothing here sends an organisation, and nothing here can: the backend takes it from the token, so
 * there is no field for one and no test that one is right. What there are tests for is the part
 * this screen could get wrong on its own — showing a password back, defaulting one, or leaving one
 * in a field after it has been sent.
 *
 * The refusals are the backend's and are rendered as what they are. A Scheduler opening this screen
 * sees the 403, not a hidden tab: the UI enforces nothing (ADR-019).
 */

const ADA = {
  id: '11111111-1111-4111-8111-111111111111',
  signInName: 'ada@example.com',
  role: 'ADMIN' as const,
  employee: null,
  active: true,
  passwordChangeRequired: false,
  locked: false,
  createdAt: '2026-03-01T09:00:00Z',
};

const GRACE = {
  id: '99999999-9999-4999-8999-999999999999',
  signInName: 'grace@example.com',
  role: 'SCHEDULER' as const,
  employee: '77777777-7777-4777-8777-777777777777',
  active: false,
  passwordChangeRequired: true,
  locked: true,
  createdAt: '2026-03-02T09:00:00Z',
};

/** The personnel record GRACE's account is linked to. Invented, like every identifier here. */
const GRACE_HOPPER = {
  id: '77777777-7777-4777-8777-777777777777',
  name: 'Grace Hopper',
  qualifications: [],
  unavailability: [],
  weeklyContractedMinutes: null,
};

/** Somebody this organisation employs whom no account is linked to yet. */
const ROBIN = {
  id: '55555555-5555-4555-8555-555555555555',
  name: 'Robin Example',
  qualifications: [],
  unavailability: [],
  weeklyContractedMinutes: null,
};

/** What the backend answers every R34 refusal with, copied from AccessExceptionHandler. */
const UNLINKED_EMPLOYEE_REFUSED =
  "An Employee account must be linked to one of this organisation's employees; one that is not cannot be restored until it is.";

/** Invented for these tests. Nothing here suggests what a real password should look like. */
const A_PASSWORD = 'an invented temporary password';

/** Invented, and short on purpose: a password a configured minimum length can refuse. */
const A_SHORT_PASSWORD = 'short';

function theOrganisation(overrides: Record<string, Answer> = {}) {
  return routeTo({
    '/api/accounts': { status: 200, body: [ADA, GRACE] },
    '/api/employees': { status: 200, body: [GRACE_HOPPER, ROBIN] },
    ...overrides,
  });
}

function show(session: Session = A_TENANT) {
  render(
    <SignedIn session={session}>
      <AccountsPage />
    </SignedIn>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('reading the organisation', () => {
  it('GivenAnAdmin_WhenOpened_ThenEveryAccountIsListed', async () => {
    theOrganisation();

    show();

    expect(await screen.findByRole('cell', { name: 'ada@example.com' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'grace@example.com' })).toBeInTheDocument();
  });

  it('GivenADisabledAndLockedAccount_WhenListed_ThenBothAreShown', async () => {
    theOrganisation();

    show();

    const row = (await screen.findByRole('cell', { name: 'grace@example.com' })).closest('tr');
    expect(within(row as HTMLElement).getByText(/Disabled/)).toBeInTheDocument();
    expect(within(row as HTMLElement).getByText(/Locked/)).toBeInTheDocument();
  });

  it('GivenASchedulerRole_WhenOpened_ThenTheRefusalIsShown', async () => {
    theOrganisation({
      '/api/accounts': { status: 403, body: { detail: 'Your role may not do this.' } },
    });

    show({ ...A_TENANT, role: 'SCHEDULER' });

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Your role may not do this.'),
    );
  });

  /**
   * The backend sends no credential, and this screen must not become the place one leaks if that
   * ever changes: it shows the fields it names, never whatever else the body carries.
   */
  it('GivenAnAccountListedWithAPasswordHash_WhenShown_ThenTheHashIsNotOnScreen', async () => {
    // Invented, and shaped like nothing a real hash function produces.
    const leaked = 'an-invented-password-hash';
    theOrganisation({
      '/api/accounts': { status: 200, body: [{ ...ADA, passwordHash: leaked }, GRACE] },
    });

    show();

    await screen.findByRole('cell', { name: 'ada@example.com' });
    expect(document.body).not.toHaveTextContent(leaked);
    expect(screen.queryByText(/passwordHash/)).not.toBeInTheDocument();
  });
});

describe('creating an account', () => {
  async function fillIn(signInName: string, role: string, password: string) {
    await userEvent.type(await screen.findByLabelText('Sign-in name'), signInName, WITHOUT_PAUSES);
    await userEvent.selectOptions(screen.getByLabelText('Role'), role);
    await userEvent.type(screen.getByLabelText('Temporary password'), password, WITHOUT_PAUSES);
  }

  it('GivenANameARoleAndAPassword_WhenCreating_ThenAllThreeAreSent', async () => {
    const stub = theOrganisation({ 'POST /api/accounts': { status: 201, body: null } });
    show();

    await fillIn('grace@example.com', 'SCHEDULER', A_PASSWORD);
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));

    await waitFor(() => expect(callsTo(stub, 'POST', '/api/accounts')).toHaveLength(1));
    const init = callsTo(stub, 'POST', '/api/accounts')[0]?.[1];
    expect(JSON.parse(String(init?.body))).toEqual({
      signInName: 'grace@example.com',
      temporaryPassword: A_PASSWORD,
      role: 'SCHEDULER',
    });
  });

  it('GivenAnEmployeeChosen_WhenCreating_ThenTheLinkIsSent', async () => {
    const stub = theOrganisation({ 'POST /api/accounts': { status: 201, body: null } });
    show();

    await fillIn('grace@example.com', 'EMPLOYEE', A_PASSWORD);
    const choice = screen.getByLabelText('Employee');
    await within(choice).findByRole('option', { name: `Robin Example · ${ROBIN.id}` });
    await userEvent.selectOptions(choice, ROBIN.id);
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));

    await waitFor(() => expect(callsTo(stub, 'POST', '/api/accounts')).toHaveLength(1));
    const init = callsTo(stub, 'POST', '/api/accounts')[0]?.[1];
    expect(JSON.parse(String(init?.body))).toMatchObject({ employee: ROBIN.id });
  });

  /** The backend decides whether an Employee may be linked to nobody (R34); the form sends it on. */
  it('GivenNoEmployeeChosen_WhenCreatingAnEmployee_ThenTheBackendsRefusalIsShown', async () => {
    const stub = theOrganisation({
      'POST /api/accounts': {
        status: 422,
        body: {
          title: 'Employee account not linked',
          detail: UNLINKED_EMPLOYEE_REFUSED,
        },
      },
    });
    show();

    await fillIn('robin@example.com', 'EMPLOYEE', A_PASSWORD);
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent(UNLINKED_EMPLOYEE_REFUSED),
    );
    const init = callsTo(stub, 'POST', '/api/accounts')[0]?.[1];
    expect(JSON.parse(String(init?.body))).not.toHaveProperty('employee');
  });

  it('GivenNoSignInName_WhenCreating_ThenNothingIsSent', async () => {
    const stub = theOrganisation();
    show();

    await userEvent.type(
      await screen.findByLabelText('Temporary password'),
      A_PASSWORD,
      WITHOUT_PAUSES,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));

    expect(callsTo(stub, 'POST', '/api/accounts')).toHaveLength(0);
  });

  it('GivenNoTemporaryPassword_WhenCreating_ThenNothingIsSent', async () => {
    const stub = theOrganisation();
    show();

    await userEvent.type(
      await screen.findByLabelText('Sign-in name'),
      'grace@example.com',
      WITHOUT_PAUSES,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));

    expect(callsTo(stub, 'POST', '/api/accounts')).toHaveLength(0);
  });

  /** Typed, never suggested, and never left on screen once it has been sent. */
  it('GivenTheForm_WhenShown_ThenTheTemporaryPasswordIsEmptyAndMasked', async () => {
    theOrganisation();
    show();

    const field = await screen.findByLabelText('Temporary password');
    expect(field).toHaveAttribute('type', 'password');
    expect(field).toHaveValue('');
  });

  it('GivenAnAccountCreated_WhenItSucceeds_ThenThePasswordFieldIsCleared', async () => {
    theOrganisation({ 'POST /api/accounts': { status: 201, body: null } });
    show();

    await fillIn('grace@example.com', 'SCHEDULER', A_PASSWORD);
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));

    await waitFor(() => expect(screen.getByLabelText('Temporary password')).toHaveValue(''));
    expect(screen.getByLabelText('Sign-in name')).toHaveValue('');
  });

  /** A refused attempt is retyped only when there is something to change, never because it failed. */
  it('GivenACreationRefused_WhenShown_ThenWhatWasTypedIsKept', async () => {
    theOrganisation({
      'POST /api/accounts': {
        status: 409,
        body: { detail: 'That sign-in name is already in use.' },
      },
    });
    show();

    await fillIn('ada@example.com', 'EMPLOYEE', A_PASSWORD);
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));

    await screen.findByRole('alert');
    expect(screen.getByLabelText('Temporary password')).toHaveValue(A_PASSWORD);
    expect(screen.getByLabelText('Sign-in name')).toHaveValue('ada@example.com');
  });

  it('GivenATakenSignInName_WhenCreating_ThenTheRefusalIsShown', async () => {
    theOrganisation({
      'POST /api/accounts': {
        status: 409,
        body: { detail: 'That sign-in name is already in use.' },
      },
    });
    show();

    await fillIn('ada@example.com', 'EMPLOYEE', A_PASSWORD);
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('That sign-in name is already in use.'),
    );
    await waitFor(() => {
      const create = screen.getByRole('button', { name: 'Create account' });
      expect(create).toBeEnabled();
      expect(create).not.toHaveAttribute('aria-disabled', 'true');
    });
  });

  /** A second creation sent meanwhile is refused for the name the first took, beside its success. */
  it('GivenACreationInFlight_WhenCreatedAgain_ThenOnlyOneIsSent', async () => {
    const routed = theOrganisation({ 'POST /api/accounts': { status: 201, body: null } });
    const release = holdingWrites(routed);
    show();
    await fillIn('robin@example.com', 'SCHEDULER', A_PASSWORD);
    const create = screen.getByRole('button', { name: 'Create account' });

    await userEvent.click(create);
    await userEvent.click(create);

    expect(create).toHaveAttribute('aria-disabled', 'true');
    release();
    await screen.findByText('Created robin@example.com.');
    expect(writes(routed)).toHaveLength(1);
    await waitFor(() => expect(create).not.toHaveAttribute('aria-disabled', 'true'));
  });

  /**
   * A disabled button drops the keyboard focus to the page in a browser (#257, C1). While the
   * creation is on its way the button says it is unavailable and keeps the focus.
   */
  it('GivenACreationInFlight_WhenPressed_ThenTheButtonKeepsFocusAndIsMarkedUnavailable', async () => {
    const routed = theOrganisation({ 'POST /api/accounts': { status: 201, body: null } });
    const release = holdingWrites(routed);
    show();
    await fillIn('robin@example.com', 'SCHEDULER', A_PASSWORD);
    const create = screen.getByRole('button', { name: 'Create account' });

    await userEvent.click(create);

    expect(create).toHaveAttribute('aria-disabled', 'true');
    expect(create).not.toBeDisabled();
    expect(create).toHaveFocus();
    release();
    await screen.findByText('Created robin@example.com.');
  });

  /** A success left on screen while the next account is typed reads as that one's (C9). */
  it('GivenACreationSucceeded_WhenTheFormIsEditedAgain_ThenTheSuccessIsNoLongerSaid', async () => {
    theOrganisation({ 'POST /api/accounts': { status: 201, body: null } });
    show();
    await fillIn('robin@example.com', 'SCHEDULER', A_PASSWORD);
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));
    await screen.findByText('Created robin@example.com.');

    await userEvent.type(screen.getByLabelText('Sign-in name'), 'm', WITHOUT_PAUSES);

    expect(screen.queryByText('Created robin@example.com.')).not.toBeInTheDocument();
  });
});

describe('changing an account', () => {
  function rowFor(signInName: string) {
    return screen.getByRole('cell', { name: signInName }).closest('tr') as HTMLElement;
  }

  /** The organisation, accepting a password reset for Ada, with the list it reads back editable. */
  function resettingAdasPassword() {
    const server: Record<string, Answer> = {
      '/api/accounts': { status: 200, body: [ADA, GRACE] },
      '/api/employees': { status: 200, body: [GRACE_HOPPER, ROBIN] },
      [`POST /api/accounts/${ADA.id}/password`]: { status: 204, body: null },
    };
    return { routed: routeTo(server), server };
  }

  /** Resets Ada's password from her row, and waits until the list has been read back after it. */
  async function resetAdasPassword({ routed }: ReturnType<typeof resettingAdasPassword>) {
    const listedBefore = callsTo(routed, 'GET', '/api/accounts').length;
    await userEvent.type(
      screen.getByLabelText('New temporary password for ada@example.com'),
      A_PASSWORD,
      WITHOUT_PAUSES,
    );
    await userEvent.click(
      within(rowFor('ada@example.com')).getByRole('button', {
        name: 'Reset password for ada@example.com',
      }),
    );
    await screen.findByText('Reset the password of ada@example.com.');
    await waitFor(() =>
      expect(callsTo(routed, 'GET', '/api/accounts').length).toBeGreaterThan(listedBefore),
    );
  }

  it('GivenAnAccount_WhenItsRoleIsChanged_ThenTheMembershipIsSent', async () => {
    const stub = theOrganisation({
      'PUT /api/accounts/99999999-9999-4999-8999-999999999999/membership': {
        status: 204,
        body: null,
      },
    });
    show();

    await screen.findByRole('cell', { name: 'grace@example.com' });
    await userEvent.selectOptions(
      within(rowFor('grace@example.com')).getByLabelText('Role for grace@example.com'),
      'EMPLOYEE',
    );
    await userEvent.click(
      screen.getByRole('button', { name: 'Change the role of grace@example.com' }),
    );

    await waitFor(() =>
      expect(
        callsTo(stub, 'PUT', '/api/accounts/99999999-9999-4999-8999-999999999999/membership'),
      ).toHaveLength(1),
    );
    const init = callsTo(
      stub,
      'PUT',
      '/api/accounts/99999999-9999-4999-8999-999999999999/membership',
    )[0]?.[1];
    // The employee link travels with the role: the backend takes them as one value, so sending the
    // role alone would silently unlink somebody.
    expect(JSON.parse(String(init?.body))).toEqual({
      role: 'EMPLOYEE',
      employee: '77777777-7777-4777-8777-777777777777',
    });
  });

  it('GivenEmployees_WhenListed_ThenEachIsOfferedByNameAndIdentifier', async () => {
    theOrganisation();
    show();

    await screen.findByRole('cell', { name: 'ada@example.com' });
    const choice = within(rowFor('ada@example.com')).getByLabelText('Employee for ada@example.com');
    expect(
      await within(choice).findByRole('option', { name: `Robin Example · ${ROBIN.id}` }),
    ).toBeInTheDocument();
    expect(within(choice).getByRole('option', { name: 'Not linked' })).toBeInTheDocument();
    expect(choice).toHaveValue('');
  });

  it('GivenAnAccount_WhenLinkedToAnEmployee_ThenTheLinkIsSentWithItsRole', async () => {
    const stub = theOrganisation({
      'PUT /api/accounts/11111111-1111-4111-8111-111111111111/membership': {
        status: 204,
        body: null,
      },
    });
    show();

    await screen.findByRole('cell', { name: 'ada@example.com' });
    await within(rowFor('ada@example.com')).findByRole('option', {
      name: `Robin Example · ${ROBIN.id}`,
    });
    await userEvent.selectOptions(
      within(rowFor('ada@example.com')).getByLabelText('Employee for ada@example.com'),
      ROBIN.id,
    );
    await userEvent.click(
      screen.getByRole('button', { name: 'Change the employee of ada@example.com' }),
    );

    await waitFor(() =>
      expect(
        callsTo(stub, 'PUT', '/api/accounts/11111111-1111-4111-8111-111111111111/membership'),
      ).toHaveLength(1),
    );
    const init = callsTo(
      stub,
      'PUT',
      '/api/accounts/11111111-1111-4111-8111-111111111111/membership',
    )[0]?.[1];
    expect(JSON.parse(String(init?.body))).toEqual({ role: 'ADMIN', employee: ROBIN.id });
  });

  it('GivenALinkedAccount_WhenSetToNotLinked_ThenTheLinkIsLeftOut', async () => {
    const stub = theOrganisation({
      'PUT /api/accounts/99999999-9999-4999-8999-999999999999/membership': {
        status: 204,
        body: null,
      },
    });
    show();

    await screen.findByRole('cell', { name: 'grace@example.com' });
    const choice = within(rowFor('grace@example.com')).getByLabelText(
      'Employee for grace@example.com',
    );
    await waitFor(() => expect(choice).toHaveValue(GRACE_HOPPER.id));
    await userEvent.selectOptions(choice, '');
    await userEvent.click(
      screen.getByRole('button', { name: 'Change the employee of grace@example.com' }),
    );

    await waitFor(() =>
      expect(
        callsTo(stub, 'PUT', '/api/accounts/99999999-9999-4999-8999-999999999999/membership'),
      ).toHaveLength(1),
    );
    const init = callsTo(
      stub,
      'PUT',
      '/api/accounts/99999999-9999-4999-8999-999999999999/membership',
    )[0]?.[1];
    expect(JSON.parse(String(init?.body))).toEqual({ role: 'SCHEDULER' });
  });

  /** A link the list does not hold still shows as a link, never as "Not linked". */
  it('GivenALinkToSomebodyNotListed_WhenShown_ThenItIsShownByItsIdentifier', async () => {
    const elsewhere = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
    theOrganisation({
      '/api/accounts': { status: 200, body: [{ ...GRACE, employee: elsewhere }] },
    });
    show();

    await screen.findByRole('cell', { name: 'grace@example.com' });
    const choice = within(rowFor('grace@example.com')).getByLabelText(
      'Employee for grace@example.com',
    );
    expect(choice).toHaveValue(elsewhere);
    expect(within(choice).getByRole('option', { name: elsewhere })).toBeInTheDocument();
  });

  /** An Employee account V9 disabled is restored only once linked (R34); the backend says so. */
  it('GivenAnEmployeeAccountLinkedToNobody_WhenRestored_ThenTheBackendsRefusalIsShown', async () => {
    const unlinked = {
      ...GRACE,
      role: 'EMPLOYEE' as const,
      employee: null,
      locked: false,
      passwordChangeRequired: false,
    };
    theOrganisation({
      '/api/accounts': { status: 200, body: [unlinked] },
      'PUT /api/accounts/99999999-9999-4999-8999-999999999999/activation': {
        status: 422,
        body: {
          title: 'Employee account not linked',
          detail: UNLINKED_EMPLOYEE_REFUSED,
        },
      },
    });
    show();

    await screen.findByRole('cell', { name: 'grace@example.com' });
    await userEvent.click(
      within(rowFor('grace@example.com')).getByRole('button', {
        name: 'Restore grace@example.com',
      }),
    );

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent(UNLINKED_EMPLOYEE_REFUSED),
    );
  });

  it('GivenTheLastAdmin_WhenDemoted_ThenTheBackendsRefusalIsShown', async () => {
    theOrganisation({
      'PUT /api/accounts/11111111-1111-4111-8111-111111111111/membership': {
        status: 409,
        body: { detail: 'An organisation must keep at least one active Admin.' },
      },
    });
    show();

    await screen.findByRole('cell', { name: 'ada@example.com' });
    await userEvent.selectOptions(
      within(rowFor('ada@example.com')).getByLabelText('Role for ada@example.com'),
      'SCHEDULER',
    );
    await userEvent.click(
      screen.getByRole('button', { name: 'Change the role of ada@example.com' }),
    );

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent(
        'An organisation must keep at least one active Admin.',
      ),
    );
  });

  /**
   * With the keyboard, a closed select changes on every arrow key in some browsers, so a change
   * sent on choosing would let an Admin demote themselves in one keystroke. Choosing sends nothing.
   */
  it('GivenARoleChosen_WhenNotYetSubmitted_ThenNothingIsSent', async () => {
    const stub = theOrganisation();
    show();

    await screen.findByRole('cell', { name: 'ada@example.com' });
    await userEvent.selectOptions(
      within(rowFor('ada@example.com')).getByLabelText('Role for ada@example.com'),
      'EMPLOYEE',
    );

    expect(writes(stub)).toHaveLength(0);
  });

  it('GivenAnEmployeeChosen_WhenNotYetSubmitted_ThenNothingIsSent', async () => {
    const stub = theOrganisation();
    show();

    await screen.findByRole('cell', { name: 'ada@example.com' });
    await within(rowFor('ada@example.com')).findByRole('option', {
      name: `Robin Example · ${ROBIN.id}`,
    });
    await userEvent.selectOptions(
      within(rowFor('ada@example.com')).getByLabelText('Employee for ada@example.com'),
      ROBIN.id,
    );

    expect(writes(stub)).toHaveLength(0);
  });

  /**
   * The membership is one value, so a change resends the field it does not change. Sent from the
   * copy of the account read before the last change was stored, it would put back what that change
   * replaced: the row waits until the list has been read again.
   */
  it('GivenAChangeStoredButNotYetReadBack_WhenTheRowIsUsedAgain_ThenItWaitsForTheReadBack', async () => {
    const membership = `/api/accounts/${GRACE.id}/membership`;
    const server: Record<string, Answer> = {
      '/api/accounts': { status: 200, body: [ADA, GRACE] },
      '/api/employees': { status: 200, body: [GRACE_HOPPER, ROBIN] },
      [`PUT ${membership}`]: { status: 204, body: null },
    };
    const routed = routeTo(server);
    let readBack!: () => void;
    const readingBack = new Promise<void>((resolve) => {
      readBack = resolve;
    });
    vi.stubGlobal(
      'fetch',
      vi.fn((path: string, init?: RequestInit) =>
        path === '/api/accounts' && callsTo(routed, 'PUT', membership).length > 0
          ? readingBack.then(() => routed(path, init))
          : routed(path, init),
      ),
    );
    show();
    await screen.findByRole('cell', { name: 'grace@example.com' });
    await within(rowFor('grace@example.com')).findByRole('option', {
      name: `Robin Example · ${ROBIN.id}`,
    });
    await userEvent.selectOptions(
      within(rowFor('grace@example.com')).getByLabelText('Employee for grace@example.com'),
      ROBIN.id,
    );
    await userEvent.click(
      screen.getByRole('button', { name: 'Change the employee of grace@example.com' }),
    );
    await screen.findByText('Changed who grace@example.com is.');

    const role = within(rowFor('grace@example.com')).getByLabelText('Role for grace@example.com');
    const changeRole = screen.getByRole('button', { name: 'Change the role of grace@example.com' });
    expect(role).toBeDisabled();
    expect(changeRole).toHaveAttribute('aria-disabled', 'true');
    expect(
      within(rowFor('grace@example.com')).getByRole('button', {
        name: 'Reset password for grace@example.com',
      }),
    ).toHaveAttribute('aria-disabled', 'true');
    server['/api/accounts'] = { status: 200, body: [ADA, { ...GRACE, employee: ROBIN.id }] };
    readBack();
    await waitFor(() => expect(role).toBeEnabled());
    await userEvent.selectOptions(role, 'EMPLOYEE');
    await userEvent.click(changeRole);

    await waitFor(() => expect(callsTo(routed, 'PUT', membership)).toHaveLength(2));
    const second = callsTo(routed, 'PUT', membership)[1]?.[1];
    expect(JSON.parse(String(second?.body))).toEqual({ role: 'EMPLOYEE', employee: ROBIN.id });
  });

  /**
   * Another row's change reads the list back too, and that copy says nothing about a change still
   * on its way. The row waits for its own request, then for the read-back that request started.
   */
  it('GivenAChangeInFlight_WhenAnotherRowsChangeIsReadBack_ThenTheRowStillWaits', async () => {
    const membership = `/api/accounts/${GRACE.id}/membership`;
    const server: Record<string, Answer> = {
      '/api/accounts': { status: 200, body: [ADA, GRACE] },
      '/api/employees': { status: 200, body: [GRACE_HOPPER, ROBIN] },
      [`PUT ${membership}`]: { status: 204, body: null },
      [`POST /api/accounts/${ADA.id}/password`]: { status: 204, body: null },
    };
    const routed = routeTo(server);
    let answer!: () => void;
    const answering = new Promise<void>((resolve) => {
      answer = resolve;
    });
    vi.stubGlobal(
      'fetch',
      vi.fn((path: string, init?: RequestInit) =>
        path === membership ? answering.then(() => routed(path, init)) : routed(path, init),
      ),
    );
    show();
    await screen.findByRole('cell', { name: 'grace@example.com' });
    const role = within(rowFor('grace@example.com')).getByLabelText('Role for grace@example.com');
    await userEvent.selectOptions(role, 'EMPLOYEE');
    await userEvent.click(
      screen.getByRole('button', { name: 'Change the role of grace@example.com' }),
    );
    const listedBefore = callsTo(routed, 'GET', '/api/accounts').length;

    await userEvent.type(
      screen.getByLabelText('New temporary password for ada@example.com'),
      A_PASSWORD,
      WITHOUT_PAUSES,
    );
    await userEvent.click(
      within(rowFor('ada@example.com')).getByRole('button', {
        name: 'Reset password for ada@example.com',
      }),
    );
    await screen.findByText('Reset the password of ada@example.com.');
    await waitFor(() =>
      expect(callsTo(routed, 'GET', '/api/accounts').length).toBeGreaterThan(listedBefore),
    );

    const changeEmployee = screen.getByRole('button', {
      name: 'Change the employee of grace@example.com',
    });
    expect(role).toBeDisabled();
    expect(changeEmployee).toHaveAttribute('aria-disabled', 'true');
    server['/api/accounts'] = { status: 200, body: [ADA, { ...GRACE, role: 'EMPLOYEE' }] };
    answer();
    await screen.findByText('Changed the role of grace@example.com.');
    await waitFor(() => expect(role).toBeEnabled());
    expect(role).toHaveValue('EMPLOYEE');
  });

  /**
   * The backend stores and audits a membership change whether or not anything differs, so a
   * Change pressed with the stored value still chosen would record a change nobody made.
   */
  it('GivenTheStoredRoleStillChosen_WhenChangeIsPressed_ThenNothingIsSentOrSaid', async () => {
    const stub = theOrganisation();
    show();
    await screen.findByRole('cell', { name: 'grace@example.com' });
    const role = within(rowFor('grace@example.com')).getByLabelText('Role for grace@example.com');
    const change = screen.getByRole('button', { name: 'Change the role of grace@example.com' });

    await userEvent.click(change);
    await userEvent.selectOptions(role, 'ADMIN');
    await userEvent.selectOptions(role, 'SCHEDULER');
    await userEvent.click(change);

    expect(writes(stub)).toHaveLength(0);
    expect(screen.queryByText(/^Changed/)).not.toBeInTheDocument();
    expect(change).toHaveAttribute('aria-disabled', 'true');
  });

  it('GivenTheStoredEmployeeStillChosen_WhenChangeIsPressed_ThenNothingIsSentOrSaid', async () => {
    const stub = theOrganisation();
    show();
    await screen.findByRole('cell', { name: 'grace@example.com' });
    const change = screen.getByRole('button', {
      name: 'Change the employee of grace@example.com',
    });

    await userEvent.click(change);

    expect(writes(stub)).toHaveLength(0);
    expect(screen.queryByText(/^Changed/)).not.toBeInTheDocument();
    expect(change).toHaveAttribute('aria-disabled', 'true');
  });

  /**
   * Another row's change reads the whole list back. A choice made here and not yet sent was made
   * against a value that has not changed, so it stays rather than reverting under the Admin's hand.
   */
  it('GivenARoleChosenButNotSent_WhenAnotherRowsChangeIsReadBack_ThenTheChoiceIsKept', async () => {
    const stub = resettingAdasPassword();
    show();
    await screen.findByRole('cell', { name: 'grace@example.com' });
    const role = within(rowFor('grace@example.com')).getByLabelText('Role for grace@example.com');
    await userEvent.selectOptions(role, 'EMPLOYEE');

    await resetAdasPassword(stub);

    expect(role).toHaveValue('EMPLOYEE');
    const change = screen.getByRole('button', { name: 'Change the role of grace@example.com' });
    expect(change).toBeEnabled();
    expect(change).not.toHaveAttribute('aria-disabled', 'true');
  });

  /** A choice made against a value that has since changed would be a decision about the past. */
  it('GivenARoleChosenButNotSent_WhenTheStoredRoleChangesUnderIt_ThenTheStoredRoleIsShown', async () => {
    const stub = resettingAdasPassword();
    show();
    await screen.findByRole('cell', { name: 'grace@example.com' });
    const role = within(rowFor('grace@example.com')).getByLabelText('Role for grace@example.com');
    await userEvent.selectOptions(role, 'EMPLOYEE');
    stub.server['/api/accounts'] = { status: 200, body: [ADA, { ...GRACE, role: 'ADMIN' }] };

    await resetAdasPassword(stub);

    await waitFor(() => expect(role).toHaveValue('ADMIN'));
  });

  /** A refusal reads nothing back, so the row must not wait for a read that never comes. */
  it('GivenAChangeRefused_WhenShown_ThenTheRowCanBeUsedAgain', async () => {
    theOrganisation({
      'PUT /api/accounts/11111111-1111-4111-8111-111111111111/membership': {
        status: 409,
        body: { detail: 'An organisation must keep at least one active Admin.' },
      },
    });
    show();
    await screen.findByRole('cell', { name: 'ada@example.com' });
    const role = within(rowFor('ada@example.com')).getByLabelText('Role for ada@example.com');

    await userEvent.selectOptions(role, 'SCHEDULER');
    await userEvent.click(
      screen.getByRole('button', { name: 'Change the role of ada@example.com' }),
    );

    await screen.findByRole('alert');
    expect(role).toBeEnabled();
    expect(role).toHaveValue('SCHEDULER');
  });

  it('GivenAnActiveAccount_WhenDisabled_ThenTheActivationIsSent', async () => {
    const stub = theOrganisation({
      'PUT /api/accounts/11111111-1111-4111-8111-111111111111/activation': {
        status: 204,
        body: null,
      },
    });
    show();

    await screen.findByRole('cell', { name: 'ada@example.com' });
    await userEvent.click(
      within(rowFor('ada@example.com')).getByRole('button', { name: 'Disable ada@example.com' }),
    );

    await waitFor(() =>
      expect(
        callsTo(stub, 'PUT', '/api/accounts/11111111-1111-4111-8111-111111111111/activation'),
      ).toHaveLength(1),
    );
    const init = callsTo(
      stub,
      'PUT',
      '/api/accounts/11111111-1111-4111-8111-111111111111/activation',
    )[0]?.[1];
    expect(JSON.parse(String(init?.body))).toEqual({ active: false });
  });

  it('GivenADisabledAccount_WhenRestored_ThenTheActivationIsSent', async () => {
    const stub = theOrganisation({
      'PUT /api/accounts/99999999-9999-4999-8999-999999999999/activation': {
        status: 204,
        body: null,
      },
    });
    show();

    await screen.findByRole('cell', { name: 'grace@example.com' });
    await userEvent.click(
      within(rowFor('grace@example.com')).getByRole('button', {
        name: 'Restore grace@example.com',
      }),
    );

    await waitFor(() =>
      expect(
        callsTo(stub, 'PUT', '/api/accounts/99999999-9999-4999-8999-999999999999/activation'),
      ).toHaveLength(1),
    );
    const init = callsTo(
      stub,
      'PUT',
      '/api/accounts/99999999-9999-4999-8999-999999999999/activation',
    )[0]?.[1];
    expect(JSON.parse(String(init?.body))).toEqual({ active: true });
  });
});

describe('resetting a colleague’s password', () => {
  it('GivenATypedPassword_WhenResetting_ThenItIsSentForThatAccountAlone', async () => {
    const stub = theOrganisation({
      'POST /api/accounts/99999999-9999-4999-8999-999999999999/password': {
        status: 204,
        body: null,
      },
    });
    show();

    await screen.findByRole('cell', { name: 'grace@example.com' });
    const row = screen
      .getByRole('cell', { name: 'grace@example.com' })
      .closest('tr') as HTMLElement;
    await userEvent.type(
      within(row).getByLabelText('New temporary password for grace@example.com'),
      A_PASSWORD,
      WITHOUT_PAUSES,
    );
    await userEvent.click(
      within(row).getByRole('button', { name: 'Reset password for grace@example.com' }),
    );

    await waitFor(() =>
      expect(
        callsTo(stub, 'POST', '/api/accounts/99999999-9999-4999-8999-999999999999/password'),
      ).toHaveLength(1),
    );
    const init = callsTo(
      stub,
      'POST',
      '/api/accounts/99999999-9999-4999-8999-999999999999/password',
    )[0]?.[1];
    expect(JSON.parse(String(init?.body))).toEqual({ temporaryPassword: A_PASSWORD });
  });

  it('GivenNoTypedPassword_WhenResetting_ThenNothingIsSent', async () => {
    const stub = theOrganisation();
    show();

    await screen.findByRole('cell', { name: 'grace@example.com' });
    const row = screen
      .getByRole('cell', { name: 'grace@example.com' })
      .closest('tr') as HTMLElement;
    await userEvent.click(
      within(row).getByRole('button', { name: 'Reset password for grace@example.com' }),
    );

    expect(
      callsTo(stub, 'POST', '/api/accounts/99999999-9999-4999-8999-999999999999/password'),
    ).toHaveLength(0);
  });

  it('GivenAReset_WhenItSucceeds_ThenTheTypedPasswordIsCleared', async () => {
    theOrganisation({
      'POST /api/accounts/99999999-9999-4999-8999-999999999999/password': {
        status: 204,
        body: null,
      },
    });
    show();

    await screen.findByRole('cell', { name: 'grace@example.com' });
    const row = screen
      .getByRole('cell', { name: 'grace@example.com' })
      .closest('tr') as HTMLElement;
    const field = within(row).getByLabelText('New temporary password for grace@example.com');
    await userEvent.type(field, A_PASSWORD, WITHOUT_PAUSES);
    await userEvent.click(
      within(row).getByRole('button', { name: 'Reset password for grace@example.com' }),
    );

    await waitFor(() => expect(field).toHaveValue(''));
  });

  it('GivenAResetRefused_WhenShown_ThenTheTypedPasswordIsKept', async () => {
    theOrganisation({
      // What AccessExceptionHandler answers a temporary password the policy refuses. The minimum
      // the detail names is this fixture's, invented: the backend names the one it is configured
      // with, and the page shows whatever it is sent.
      'POST /api/accounts/99999999-9999-4999-8999-999999999999/password': {
        status: 422,
        body: {
          title: 'Password not accepted',
          detail: 'A password must be at least 15 characters long.',
          reason: 'TOO_SHORT',
        },
      },
    });
    show();

    await screen.findByRole('cell', { name: 'grace@example.com' });
    const row = screen
      .getByRole('cell', { name: 'grace@example.com' })
      .closest('tr') as HTMLElement;
    const field = within(row).getByLabelText('New temporary password for grace@example.com');
    await userEvent.type(field, A_SHORT_PASSWORD, WITHOUT_PAUSES);
    await userEvent.click(
      within(row).getByRole('button', { name: 'Reset password for grace@example.com' }),
    );

    // The fixture's invented detail, shown as sent.
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'A password must be at least 15 characters long.',
    );
    expect(field).toHaveValue(A_SHORT_PASSWORD);
  });

  it('GivenAResetField_WhenShown_ThenItIsMaskedAndEmpty', async () => {
    theOrganisation();
    show();

    await screen.findByRole('cell', { name: 'grace@example.com' });
    const row = screen
      .getByRole('cell', { name: 'grace@example.com' })
      .closest('tr') as HTMLElement;
    const field = within(row).getByLabelText('New temporary password for grace@example.com');
    expect(field).toHaveAttribute('type', 'password');
    expect(field).toHaveValue('');
  });
});

/**
 * Every credential on this screen belongs to somebody else, so the browser is told not to autofill
 * the Admin's own: `new-password` on the passwords, `off` on a name that is not the Admin's. What
 * a password manager then offers to save is the browser's choice, and jsdom cannot show it; these
 * tests pin the hints, not the prompt (#250).
 */
describe('what the browser’s password manager is offered', () => {
  it('GivenTheCreateForm_WhenShown_ThenTheTemporaryPasswordIsMarkedAsANewPassword', async () => {
    theOrganisation();
    show();

    expect(await screen.findByLabelText('Temporary password')).toHaveAttribute(
      'autocomplete',
      'new-password',
    );
  });

  it('GivenTheCreateForm_WhenShown_ThenTheSignInNameIsNotAutofilled', async () => {
    theOrganisation();
    show();

    expect(await screen.findByLabelText('Sign-in name')).toHaveAttribute('autocomplete', 'off');
  });

  it('GivenEveryAccountRow_WhenShown_ThenEachResetFieldIsMarkedAsANewPassword', async () => {
    theOrganisation();
    show();
    await screen.findByRole('cell', { name: GRACE.signInName });

    for (const { signInName } of [ADA, GRACE]) {
      const row = screen.getByRole('cell', { name: signInName }).closest('tr') as HTMLElement;
      expect(
        within(row).getByLabelText(`New temporary password for ${signInName}`),
        `the reset field for ${signInName}`,
      ).toHaveAttribute('autocomplete', 'new-password');
    }
  });
});

/** What a keyboard or screen reader user is told about each account's row (#257). */
describe('the accounts table, told rather than shown', () => {
  function rowFor(signInName: string) {
    return screen.getByRole('cell', { name: signInName }).closest('tr') as HTMLElement;
  }

  /** The detail AccessExceptionHandler sends for the last Admin; the page shows it as sent. */
  const LAST_ADMIN = 'An organisation must keep at least one active Admin.';

  it('GivenTheAccounts_WhenListed_ThenTheTableHasAName', async () => {
    theOrganisation();

    show();

    await screen.findByRole('cell', { name: 'ada@example.com' });
    expect(screen.getByRole('table')).toHaveAccessibleName();
  });

  /** An unnamed header is read as nothing, so the actions column is announced as no column (C8). */
  it('GivenTheAccounts_WhenListed_ThenEveryColumnHeaderHasAName', async () => {
    theOrganisation();

    show();

    await screen.findByRole('cell', { name: 'ada@example.com' });
    screen.getAllByRole('columnheader').forEach((header, index) => {
      expect(header, `column header ${index + 1}`).toHaveAccessibleName();
    });
  });

  /** "Reset password" on every row gave a screen reader no way to tell whose it was (C7). */
  it('GivenSeveralAccounts_WhenListed_ThenEachRowsButtonsNameTheirAccount', async () => {
    theOrganisation();

    show();

    await screen.findByRole('cell', { name: 'grace@example.com' });
    const ada = within(rowFor('ada@example.com'));
    const grace = within(rowFor('grace@example.com'));
    expect(ada.getByRole('button', { name: 'Reset password for ada@example.com' })).toBeVisible();
    expect(ada.getByRole('button', { name: 'Disable ada@example.com' })).toBeVisible();
    expect(
      grace.getByRole('button', { name: 'Reset password for grace@example.com' }),
    ).toBeVisible();
    expect(grace.getByRole('button', { name: 'Restore grace@example.com' })).toBeVisible();
  });

  /** A bare disabled button cannot be reached to learn why it does nothing (C5). */
  it('GivenTheStoredRoleChosen_WhenShown_ThenChangeIsUnavailableAndSaysToChooseAnother', async () => {
    theOrganisation();
    show();
    await screen.findByRole('cell', { name: 'grace@example.com' });
    const change = screen.getByRole('button', { name: 'Change the role of grace@example.com' });

    expect(change).toHaveAttribute('aria-disabled', 'true');
    expect(change).not.toBeDisabled();
    expect(change).toHaveAccessibleDescription(/choose/i);
    await userEvent.selectOptions(
      within(rowFor('grace@example.com')).getByLabelText('Role for grace@example.com'),
      'EMPLOYEE',
    );
    expect(change).not.toHaveAttribute('aria-disabled', 'true');
  });

  it('GivenTheStoredEmployeeChosen_WhenShown_ThenChangeIsUnavailableAndSaysToChooseAnother', async () => {
    theOrganisation();
    show();
    await screen.findByRole('cell', { name: 'ada@example.com' });
    await within(rowFor('ada@example.com')).findByRole('option', {
      name: `Robin Example · ${ROBIN.id}`,
    });
    const change = screen.getByRole('button', { name: 'Change the employee of ada@example.com' });

    expect(change).toHaveAttribute('aria-disabled', 'true');
    expect(change).not.toBeDisabled();
    expect(change).toHaveAccessibleDescription(/choose/i);
    await userEvent.selectOptions(
      within(rowFor('ada@example.com')).getByLabelText('Employee for ada@example.com'),
      ROBIN.id,
    );
    expect(change).not.toHaveAttribute('aria-disabled', 'true');
  });

  /**
   * A disabled button drops the keyboard focus to the page in a browser (C1), and the row must not
   * send a second change from the copy the first is about to replace.
   */
  it('GivenARoleChangeInFlight_WhenPressedAgain_ThenChangeKeepsFocusAndOnlyOneIsSent', async () => {
    const membership = `/api/accounts/${GRACE.id}/membership`;
    const routed = theOrganisation({ [`PUT ${membership}`]: { status: 204, body: null } });
    const release = holdingWrites(routed);
    show();
    await screen.findByRole('cell', { name: 'grace@example.com' });
    await userEvent.selectOptions(
      within(rowFor('grace@example.com')).getByLabelText('Role for grace@example.com'),
      'EMPLOYEE',
    );
    const change = screen.getByRole('button', { name: 'Change the role of grace@example.com' });

    await userEvent.click(change);
    await userEvent.click(change);

    expect(change).toHaveAttribute('aria-disabled', 'true');
    expect(change).not.toBeDisabled();
    expect(change).toHaveFocus();
    release();
    await screen.findByText('Changed the role of grace@example.com.');
    expect(writes(routed)).toHaveLength(1);
  });

  it('GivenAnEmployeeChangeInFlight_WhenPressedAgain_ThenChangeKeepsFocusAndOnlyOneIsSent', async () => {
    const membership = `/api/accounts/${ADA.id}/membership`;
    const routed = theOrganisation({ [`PUT ${membership}`]: { status: 204, body: null } });
    const release = holdingWrites(routed);
    show();
    await screen.findByRole('cell', { name: 'ada@example.com' });
    await within(rowFor('ada@example.com')).findByRole('option', {
      name: `Robin Example · ${ROBIN.id}`,
    });
    await userEvent.selectOptions(
      within(rowFor('ada@example.com')).getByLabelText('Employee for ada@example.com'),
      ROBIN.id,
    );
    const change = screen.getByRole('button', { name: 'Change the employee of ada@example.com' });

    await userEvent.click(change);
    await userEvent.click(change);

    expect(change).toHaveAttribute('aria-disabled', 'true');
    expect(change).not.toBeDisabled();
    expect(change).toHaveFocus();
    release();
    await screen.findByText('Changed who ada@example.com is.');
    expect(writes(routed)).toHaveLength(1);
  });

  it('GivenAResetInFlight_WhenPressedAgain_ThenResetKeepsFocusAndOnlyOneIsSent', async () => {
    const routed = theOrganisation({
      [`POST /api/accounts/${GRACE.id}/password`]: { status: 204, body: null },
    });
    const release = holdingWrites(routed);
    show();
    await screen.findByRole('cell', { name: 'grace@example.com' });
    await userEvent.type(
      screen.getByLabelText('New temporary password for grace@example.com'),
      A_PASSWORD,
      WITHOUT_PAUSES,
    );
    const reset = within(rowFor('grace@example.com')).getByRole('button', {
      name: /^Reset password/,
    });

    await userEvent.click(reset);
    await userEvent.click(reset);

    expect(reset).toHaveAttribute('aria-disabled', 'true');
    expect(reset).not.toBeDisabled();
    expect(reset).toHaveFocus();
    release();
    await screen.findByText('Reset the password of grace@example.com.');
    expect(writes(routed)).toHaveLength(1);
  });

  /** Disable and Restore are one button and one handler, the label following the stored state. */
  it('GivenADisableInFlight_WhenPressedAgain_ThenDisableKeepsFocusAndOnlyOneIsSent', async () => {
    const routed = theOrganisation({
      [`PUT /api/accounts/${ADA.id}/activation`]: { status: 204, body: null },
    });
    const release = holdingWrites(routed);
    show();
    await screen.findByRole('cell', { name: 'ada@example.com' });
    const disable = within(rowFor('ada@example.com')).getByRole('button', { name: /^Disable/ });

    await userEvent.click(disable);
    await userEvent.click(disable);

    expect(disable).toHaveAttribute('aria-disabled', 'true');
    expect(disable).not.toBeDisabled();
    expect(disable).toHaveFocus();
    release();
    await screen.findByText('Disabled ada@example.com.');
    expect(writes(routed)).toHaveLength(1);
  });

  /** A refusal above the table says nothing about which of its rows it belongs to (C6). */
  it('GivenAResetRefused_WhenShown_ThenTheRefusalIsInTheAccountsTable', async () => {
    theOrganisation({
      // The minimum this detail names is invented, as in the reset tests above.
      [`POST /api/accounts/${GRACE.id}/password`]: {
        status: 422,
        body: {
          title: 'Password not accepted',
          detail: 'A password must be at least 15 characters long.',
          reason: 'TOO_SHORT',
        },
      },
    });
    show();
    await screen.findByRole('cell', { name: 'grace@example.com' });

    await userEvent.type(
      screen.getByLabelText('New temporary password for grace@example.com'),
      A_SHORT_PASSWORD,
      WITHOUT_PAUSES,
    );
    await userEvent.click(
      within(rowFor('grace@example.com')).getByRole('button', { name: /^Reset password/ }),
    );

    expect(await within(screen.getByRole('table')).findByRole('alert')).toHaveTextContent(
      'A password must be at least 15 characters long.',
    );
    expect(screen.getAllByRole('alert'), 'every alert on the page').toHaveLength(1);
    expect(within(rowFor('ada@example.com')).queryByRole('alert')).not.toBeInTheDocument();
  });

  /** The field the refusal is about says so, and says why, where the cursor already is (C6). */
  it('GivenAResetRefused_WhenShown_ThenThatRowsPasswordFieldIsMarkedInvalidWithTheReason', async () => {
    theOrganisation({
      [`POST /api/accounts/${GRACE.id}/password`]: {
        status: 422,
        body: {
          title: 'Password not accepted',
          detail: 'A password must be at least 15 characters long.',
          reason: 'TOO_SHORT',
        },
      },
    });
    show();
    await screen.findByRole('cell', { name: 'grace@example.com' });
    const field = screen.getByLabelText('New temporary password for grace@example.com');

    await userEvent.type(field, A_SHORT_PASSWORD, WITHOUT_PAUSES);
    await userEvent.click(
      within(rowFor('grace@example.com')).getByRole('button', { name: /^Reset password/ }),
    );

    await waitFor(() => expect(field).toHaveAttribute('aria-invalid', 'true'));
    expect(field).toHaveAccessibleDescription(/at least 15 characters long/);
    expect(screen.getByLabelText('New temporary password for ada@example.com')).not.toHaveAttribute(
      'aria-invalid',
      'true',
    );
  });

  it('GivenARoleChangeRefused_WhenShown_ThenTheRefusalIsInTheAccountsTable', async () => {
    theOrganisation({
      [`PUT /api/accounts/${ADA.id}/membership`]: { status: 409, body: { detail: LAST_ADMIN } },
    });
    show();
    await screen.findByRole('cell', { name: 'ada@example.com' });

    await userEvent.selectOptions(
      within(rowFor('ada@example.com')).getByLabelText('Role for ada@example.com'),
      'SCHEDULER',
    );
    await userEvent.click(
      screen.getByRole('button', { name: 'Change the role of ada@example.com' }),
    );

    expect(await within(screen.getByRole('table')).findByRole('alert')).toHaveTextContent(
      LAST_ADMIN,
    );
    expect(screen.getAllByRole('alert'), 'every alert on the page').toHaveLength(1);
    expect(within(rowFor('grace@example.com')).queryByRole('alert')).not.toBeInTheDocument();
  });

  it('GivenADisableRefused_WhenShown_ThenTheRefusalIsInTheAccountsTable', async () => {
    theOrganisation({
      [`PUT /api/accounts/${ADA.id}/activation`]: { status: 409, body: { detail: LAST_ADMIN } },
    });
    show();
    await screen.findByRole('cell', { name: 'ada@example.com' });

    await userEvent.click(
      within(rowFor('ada@example.com')).getByRole('button', { name: /^Disable/ }),
    );

    expect(await within(screen.getByRole('table')).findByRole('alert')).toHaveTextContent(
      LAST_ADMIN,
    );
    expect(screen.getAllByRole('alert'), 'every alert on the page').toHaveLength(1);
    expect(within(rowFor('grace@example.com')).queryByRole('alert')).not.toBeInTheDocument();
  });
});
