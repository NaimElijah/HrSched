import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { OrganisationsPage } from './OrganisationsPage';
import { callsTo, routeTo } from '../test-support/routeTo';
import type { Answer } from '../test-support/routeTo';
import { A_TENANT, SignedIn, THE_OPERATOR } from '../test-support/session';
import type { Session } from '../api/session';

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
    thePlatform({
      '/api/organisations': { status: 401, body: { detail: 'A valid access token is required.' } },
    });

    show();

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Your session has ended.'),
    );
  });
});

describe('onboarding', () => {
  it('GivenATypedName_WhenOnboarding_ThenItIsSentAsTheSignedInOperator', async () => {
    const stub = thePlatform({ 'POST /api/organisations': { status: 201, body: BETA } });
    show();

    await userEvent.type(await screen.findByLabelText('Name'), 'Beta');
    await userEvent.click(screen.getByRole('button', { name: 'Onboard organisation' }));

    await waitFor(() => expect(callsTo(stub, 'POST', '/api/organisations')).toHaveLength(1));
    const init = callsTo(stub, 'POST', '/api/organisations')[0]?.[1];
    expect(JSON.parse(String(init?.body))).toEqual({ name: 'Beta' });
    expect((init?.headers as Headers).get('X-Role')).toBe('OPERATOR');
    // The operator belongs to none, so there is no organisation to claim.
    expect((init?.headers as Headers).get('X-Organisation')).toBeNull();
    expect(await screen.findByText('Onboarded Beta.')).toHaveAttribute('role', 'status');
  });

  it('GivenABlankName_WhenOnboarding_ThenNothingIsSent', async () => {
    const stub = thePlatform();
    show();

    await userEvent.click(await screen.findByRole('button', { name: 'Onboard organisation' }));

    expect(callsTo(stub, 'POST', '/api/organisations')).toHaveLength(0);
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
