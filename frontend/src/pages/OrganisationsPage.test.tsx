import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { OrganisationsPage } from './OrganisationsPage';
import { callsTo, routeTo } from '../test-support/routeTo';
import type { Answer } from '../test-support/routeTo';
import { A_TENANT, SignedIn, THE_OPERATOR } from '../test-support/session';
import type { Session } from '../api/session';
import { WITHOUT_PAUSES } from '../test-support/typing';

/**
 * The register of tenants, which is the whole of what the operator administers.
 *
 * There is nothing here about acting inside an organisation any more. That used to be a button, and
 * it only ever worked because the backend believed whatever identity headers it was sent; the
 * operator is now refused everything inside every tenant (ADR-030), so the button would offer
 * something that could not happen.
 *
 * A tenant role reaching this screen is shown the backend's refusal rather than a hidden tab: which
 * identity administers the platform is the backend's rule (ADR-019), and a screen that hid itself
 * would be the UI claiming to enforce it.
 */

const ACME = { id: '22222222-2222-4222-8222-222222222222', name: 'Acme' };
const BETA = { id: '55555555-5555-4555-8555-555555555555', name: 'Beta' };

/** Invented for these tests. Nothing here is a suggestion about what a real one should look like. */
const AN_ADMIN = 'first-admin@example.com';
const A_PASSWORD = 'an invented temporary password';

function thePlatform(overrides: Record<string, Answer> = {}) {
  return routeTo({
    '/api/organisations': { status: 200, body: [ACME, BETA] },
    ...overrides,
  });
}

function show(session: Session = THE_OPERATOR) {
  render(
    <SignedIn session={session}>
      <OrganisationsPage />
    </SignedIn>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('reading the platform', () => {
  it('GivenTheOperator_WhenOpened_ThenEveryOrganisationIsListed', async () => {
    thePlatform();

    show();

    expect(await screen.findByRole('cell', { name: 'Beta' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'Acme' })).toBeInTheDocument();
  });

  it('GivenATenantRole_WhenOpened_ThenTheOperatorRefusalIsShown', async () => {
    thePlatform({
      '/api/organisations': {
        status: 403,
        body: { detail: 'Only the platform operator may do this.' },
      },
    });

    show(A_TENANT);

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Your role may not do this.'),
    );
  });

  it('GivenTheSessionHasEnded_WhenOpened_ThenTheRefusalSaysSo', async () => {
    // An ended session is one whose refresh the backend refuses too, and it refuses with a 401.
    thePlatform({
      '/api/organisations': { status: 401, body: { detail: 'A valid access token is required.' } },
      'POST /api/auth/refresh': { status: 401, body: { detail: 'Sign in again.' } },
    });

    show();

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Your session has ended.'),
    );
  });
});

describe('onboarding', () => {
  async function fillIn(organisation: string, admin: string, password: string) {
    await userEvent.type(await screen.findByLabelText('Name'), organisation, WITHOUT_PAUSES);
    await userEvent.type(screen.getByLabelText('First admin sign-in name'), admin, WITHOUT_PAUSES);
    await userEvent.type(screen.getByLabelText('Temporary password'), password, WITHOUT_PAUSES);
  }

  it('GivenANameAndAFirstAdmin_WhenOnboarding_ThenBothAreSentAsTheSignedInOperator', async () => {
    const stub = thePlatform({ 'POST /api/organisations': { status: 201, body: BETA } });
    show();

    await fillIn('Beta', AN_ADMIN, A_PASSWORD);
    await userEvent.click(screen.getByRole('button', { name: 'Onboard organisation' }));

    await waitFor(() => expect(callsTo(stub, 'POST', '/api/organisations')).toHaveLength(1));
    const init = callsTo(stub, 'POST', '/api/organisations')[0]?.[1];
    expect(JSON.parse(String(init?.body))).toEqual({
      name: 'Beta',
      firstAdmin: { signInName: AN_ADMIN, temporaryPassword: A_PASSWORD },
    });
    // Who is acting is the token's business; nothing about it is in the request otherwise.
    expect((init?.headers as Headers).get('X-Role')).toBeNull();
    expect((init?.headers as Headers).get('X-Organisation')).toBeNull();
    expect(await screen.findByText('Onboarded Beta.')).toHaveAttribute('role', 'status');
  });

  it('GivenABlankName_WhenOnboarding_ThenNothingIsSent', async () => {
    const stub = thePlatform();
    show();

    await userEvent.type(
      await screen.findByLabelText('First admin sign-in name'),
      AN_ADMIN,
      WITHOUT_PAUSES,
    );
    await userEvent.type(screen.getByLabelText('Temporary password'), A_PASSWORD, WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Onboard organisation' }));

    expect(callsTo(stub, 'POST', '/api/organisations')).toHaveLength(0);
  });

  /**
   * An organisation nobody can administer is not usable, and the operator cannot act inside one to
   * add somebody later (ADR-030) — so the backend refuses onboarding without a first Admin, and the
   * form must not be able to ask for it.
   */
  it('GivenNoFirstAdmin_WhenOnboarding_ThenNothingIsSent', async () => {
    const stub = thePlatform();
    show();

    await userEvent.type(await screen.findByLabelText('Name'), 'Beta', WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Onboard organisation' }));

    expect(callsTo(stub, 'POST', '/api/organisations')).toHaveLength(0);
  });

  it('GivenNoTemporaryPassword_WhenOnboarding_ThenNothingIsSent', async () => {
    const stub = thePlatform();
    show();

    await userEvent.type(await screen.findByLabelText('Name'), 'Beta', WITHOUT_PAUSES);
    await userEvent.type(
      screen.getByLabelText('First admin sign-in name'),
      AN_ADMIN,
      WITHOUT_PAUSES,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Onboard organisation' }));

    expect(callsTo(stub, 'POST', '/api/organisations')).toHaveLength(0);
  });

  /** Typed, never suggested, and never left on the screen afterwards. */
  it('GivenTheForm_WhenShown_ThenTheTemporaryPasswordIsEmptyAndMasked', async () => {
    thePlatform();
    show();

    const field = await screen.findByLabelText('Temporary password');
    expect(field).toHaveAttribute('type', 'password');
    expect(field).toHaveValue('');
  });

  it('GivenAnOnboarding_WhenItSucceeds_ThenTheTemporaryPasswordIsCleared', async () => {
    thePlatform({ 'POST /api/organisations': { status: 201, body: BETA } });
    show();

    await fillIn('Beta', AN_ADMIN, A_PASSWORD);
    await userEvent.click(screen.getByRole('button', { name: 'Onboard organisation' }));

    await screen.findByText('Onboarded Beta.');
    expect(screen.getByLabelText('Temporary password')).toHaveValue('');
    expect(screen.getByLabelText('First admin sign-in name')).toHaveValue('');
  });

  it('GivenAnOnboardingRefused_WhenShown_ThenWhatWasTypedIsKept', async () => {
    thePlatform({
      'POST /api/organisations': {
        status: 409,
        body: { detail: 'That sign-in name is already in use.' },
      },
    });
    show();

    await fillIn('Beta', AN_ADMIN, A_PASSWORD);
    await userEvent.click(screen.getByRole('button', { name: 'Onboard organisation' }));

    await screen.findByRole('alert');
    expect(screen.getByLabelText('Temporary password')).toHaveValue(A_PASSWORD);
    expect(screen.getByLabelText('First admin sign-in name')).toHaveValue(AN_ADMIN);
    expect(screen.getByLabelText('Name')).toHaveValue('Beta');
  });

  it('GivenATakenSignInName_WhenOnboarding_ThenTheRefusalIsShown', async () => {
    thePlatform({
      'POST /api/organisations': {
        status: 409,
        body: { detail: 'That sign-in name is already in use.' },
      },
    });
    show();

    await fillIn('Beta', AN_ADMIN, A_PASSWORD);
    await userEvent.click(screen.getByRole('button', { name: 'Onboard organisation' }));

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('That sign-in name is already in use.'),
    );
  });
});

describe('what is no longer offered', () => {
  /**
   * The operator belongs to no organisation and is refused inside every one of them, so offering
   * to act in one would be offering something the backend would refuse (ADR-030).
   */
  it('GivenTheOperator_WhenTheListIsShown_ThenThereIsNoWayToActInsideAnOrganisation', async () => {
    thePlatform();

    show();

    await screen.findByRole('cell', { name: 'Beta' });
    expect(screen.queryByRole('button', { name: /Act in/ })).not.toBeInTheDocument();
  });
});

/**
 * Both credentials typed here are the first Admin's, never the operator's, so the browser is told
 * not to autofill the operator's own. What a password manager then offers to save is the browser's
 * choice, and jsdom cannot show it; these tests pin the hints, not the prompt (#250).
 */
describe('what the browser’s password manager is offered', () => {
  it('GivenTheOnboardingForm_WhenShown_ThenTheTemporaryPasswordIsMarkedAsANewPassword', async () => {
    thePlatform();
    show();

    expect(await screen.findByLabelText('Temporary password')).toHaveAttribute(
      'autocomplete',
      'new-password',
    );
  });

  it('GivenTheOnboardingForm_WhenShown_ThenTheFirstAdminsSignInNameIsNotAutofilled', async () => {
    thePlatform();
    show();

    expect(await screen.findByLabelText('First admin sign-in name')).toHaveAttribute(
      'autocomplete',
      'off',
    );
  });
});
