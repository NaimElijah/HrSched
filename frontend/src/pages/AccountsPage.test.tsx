import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AccountsPage } from './AccountsPage';
import { callsTo, routeTo } from '../test-support/routeTo';
import type { Answer } from '../test-support/routeTo';
import { A_TENANT, SignedIn } from '../test-support/session';
import type { Session } from '../api/session';

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

/** Invented for these tests. Nothing here suggests what a real password should look like. */
const A_PASSWORD = 'an invented temporary password';

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

  it('GivenNoAccountHasAPasswordHere_WhenListed_ThenNothingLooksLikeACredential', async () => {
    theOrganisation();

    show();

    await screen.findByRole('cell', { name: 'ada@example.com' });
    expect(screen.queryByText(/passwordHash/)).not.toBeInTheDocument();
  });
});

describe('creating an account', () => {
  async function fillIn(signInName: string, role: string, password: string) {
    await userEvent.type(await screen.findByLabelText('Sign-in name'), signInName);
    await userEvent.selectOptions(screen.getByLabelText('Role'), role);
    await userEvent.type(screen.getByLabelText('Temporary password'), password);
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
          detail: "An Employee account must be linked to one of this organisation's employees.",
        },
      },
    });
    show();

    await fillIn('robin@example.com', 'EMPLOYEE', A_PASSWORD);
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent(
        "An Employee account must be linked to one of this organisation's employees.",
      ),
    );
    const init = callsTo(stub, 'POST', '/api/accounts')[0]?.[1];
    expect(JSON.parse(String(init?.body))).not.toHaveProperty('employee');
  });

  it('GivenNoSignInName_WhenCreating_ThenNothingIsSent', async () => {
    const stub = theOrganisation();
    show();

    await userEvent.type(await screen.findByLabelText('Temporary password'), A_PASSWORD);
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));

    expect(callsTo(stub, 'POST', '/api/accounts')).toHaveLength(0);
  });

  it('GivenNoTemporaryPassword_WhenCreating_ThenNothingIsSent', async () => {
    const stub = theOrganisation();
    show();

    await userEvent.type(await screen.findByLabelText('Sign-in name'), 'grace@example.com');
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
  });
});

describe('changing an account', () => {
  function rowFor(signInName: string) {
    return screen.getByRole('cell', { name: signInName }).closest('tr') as HTMLElement;
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
          detail: 'Link it before restoring it.',
        },
      },
    });
    show();

    await screen.findByRole('cell', { name: 'grace@example.com' });
    await userEvent.click(
      within(rowFor('grace@example.com')).getByRole('button', { name: 'Restore' }),
    );

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Link it before restoring it.'),
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

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent(
        'An organisation must keep at least one active Admin.',
      ),
    );
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
      within(rowFor('ada@example.com')).getByRole('button', { name: 'Disable' }),
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
      within(rowFor('grace@example.com')).getByRole('button', { name: 'Restore' }),
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
    );
    await userEvent.click(within(row).getByRole('button', { name: 'Reset password' }));

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
    await userEvent.click(within(row).getByRole('button', { name: 'Reset password' }));

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
    await userEvent.type(field, A_PASSWORD);
    await userEvent.click(within(row).getByRole('button', { name: 'Reset password' }));

    await waitFor(() => expect(field).toHaveValue(''));
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
