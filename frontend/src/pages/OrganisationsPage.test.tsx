import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ActingAsProvider } from '../identity/ActingAs';
import { callsTo, routeTo, type Answer } from '../test-support/routeTo';
import { OrganisationsPage } from './OrganisationsPage';

/**
 * The platform Operator's screen (F13): list, onboard and read organisations.
 *
 * Every request goes out as the identity the bar claims, unchanged. A tenant role is therefore
 * refused the listing, and the page shows that refusal rather than hiding the screen: which role may
 * administer the platform is the backend's rule, not the tab bar's.
 */

const ACME = { id: '22222222-2222-4222-8222-222222222222', name: 'Acme' };
const BETA = { id: '55555555-5555-4555-8555-555555555555', name: 'Beta' };
const EMPLOYEE = '11111111-1111-4111-8111-111111111111';

function actingAs(role: string, employee: string | null = null) {
  window.localStorage.setItem(
    'hrsched.acting-as',
    JSON.stringify({
      organisation: ACME.id,
      user: '33333333-3333-4333-8333-333333333333',
      role,
      employee,
    }),
  );
}

function stored(): { organisation?: string; employee?: string | null } {
  return JSON.parse(window.localStorage.getItem('hrsched.acting-as') ?? '{}') as {
    organisation?: string;
    employee?: string | null;
  };
}

function thePlatform(overrides: Record<string, Answer> = {}) {
  return routeTo({
    '/api/organisations': { status: 200, body: [ACME, BETA] },
    [`/api/organisations/${ACME.id}`]: { status: 200, body: ACME },
    ...overrides,
  });
}

function show() {
  render(
    <ActingAsProvider>
      <OrganisationsPage />
    </ActingAsProvider>,
  );
}

beforeEach(() => {
  window.localStorage.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
});

describe('reading the platform', () => {
  it('GivenTheOperator_WhenOpened_ThenEveryOrganisationIsListed', async () => {
    actingAs('OPERATOR');
    thePlatform();

    show();

    expect(await screen.findByRole('cell', { name: 'Beta' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'Acme' })).toBeInTheDocument();
  });

  it('GivenTheActingOrganisation_WhenOpened_ThenItsNameIsReadBack', async () => {
    actingAs('ADMIN');
    thePlatform({ '/api/organisations': { status: 403, body: {} } });

    show();

    expect(await screen.findByText('You are acting in Acme.')).toBeInTheDocument();
  });

  it('GivenATenantRole_WhenOpened_ThenTheOperatorRefusalIsShown', async () => {
    actingAs('ADMIN');
    thePlatform({
      '/api/organisations': {
        status: 403,
        body: { detail: 'Only the platform operator may do this.' },
      },
    });

    show();

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Your role may not do this.'),
    );
  });

  /** An organisation claim nobody onboarded is refused at the boundary, listing included. */
  it('GivenAnOrganisationClaimNobodyOnboarded_WhenOpened_ThenTheRefusalIsShown', async () => {
    actingAs('OPERATOR');
    thePlatform({
      '/api/organisations': { status: 404, body: { detail: 'No such resource.' } },
      [`/api/organisations/${ACME.id}`]: { status: 404, body: { detail: 'No such resource.' } },
    });

    show();

    await waitFor(() =>
      expect(screen.getAllByRole('alert')[0]).toHaveTextContent(
        'Nothing here for this organisation.',
      ),
    );
  });
});

describe('onboarding', () => {
  /** Not overridden to OPERATOR, unlike the bar's bootstrap: this screen claims nothing extra. */
  it('GivenATypedName_WhenOnboarding_ThenItIsSentAsTheActingIdentity', async () => {
    actingAs('OPERATOR');
    const stub = thePlatform({ 'POST /api/organisations': { status: 201, body: BETA } });
    show();

    await userEvent.type(await screen.findByLabelText('Name'), 'Beta');
    await userEvent.click(screen.getByRole('button', { name: 'Onboard organisation' }));

    await waitFor(() => expect(callsTo(stub, 'POST', '/api/organisations')).toHaveLength(1));
    const init = callsTo(stub, 'POST', '/api/organisations')[0]?.[1];
    expect(JSON.parse(String(init?.body))).toEqual({ name: 'Beta' });
    expect((init?.headers as Headers).get('X-Organisation')).toBe(ACME.id);
    expect((init?.headers as Headers).get('X-Role')).toBe('OPERATOR');
    expect(await screen.findByText('Onboarded Beta.')).toHaveAttribute('role', 'status');
  });

  it('GivenABlankName_WhenOnboarding_ThenNothingIsSent', async () => {
    actingAs('OPERATOR');
    const stub = thePlatform();
    show();

    await userEvent.click(await screen.findByRole('button', { name: 'Onboard organisation' }));

    expect(callsTo(stub, 'POST', '/api/organisations')).toHaveLength(0);
  });
});

describe('acting in an organisation', () => {
  it('GivenAListedOrganisation_WhenActingInIt_ThenTheIdentitySwitchesAndTheEmployeeLinkClears', async () => {
    actingAs('SCHEDULER', EMPLOYEE);
    thePlatform();
    show();

    await userEvent.click(await screen.findByRole('button', { name: 'Act in Beta' }));

    await waitFor(() => expect(stored().organisation).toBe(BETA.id));
    expect(stored().employee).toBeNull();
  });

  it('GivenTheOrganisationAlreadyActedIn_WhenListed_ThenItOffersNoSwitch', async () => {
    actingAs('OPERATOR');
    thePlatform();

    show();

    await screen.findByRole('button', { name: 'Act in Beta' });
    expect(screen.queryByRole('button', { name: 'Act in Acme' })).not.toBeInTheDocument();
  });
});
