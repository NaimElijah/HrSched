import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { callsTo, holdingWrites, routeTo, writes } from '../test-support/routeTo';
import type { Answer } from '../test-support/routeTo';
import type { Session } from '../api/session';
import { A_SCHEDULER, A_TENANT, SignedIn } from '../test-support/session';
import { QualificationsPage } from './QualificationsPage';
import { WITHOUT_PAUSES } from '../test-support/typing';

/**
 * A qualification is a named ticket scoped to one organisation, and a shift may only require one
 * this organisation has defined. That makes this the smallest screen in the application and the
 * vocabulary everything else depends on.
 */

const DEFINED = [
  { id: 'q-1', name: 'Forklift' },
  { id: 'q-2', name: 'Welding' },
];

/** Answers the listing with what is defined, and accepts a definition, unless overridden. */
function theOrganisation(overrides: Record<string, Answer> = {}) {
  return routeTo({
    '/api/qualifications': { status: 200, body: DEFINED },
    'POST /api/qualifications': { status: 201, body: { id: 'q-3', name: 'Nursing' } },
    ...overrides,
  });
}

const NONE_DEFINED: Record<string, Answer> = {
  '/api/qualifications': { status: 200, body: [] },
};

function show(session: Session = A_TENANT) {
  render(
    <SignedIn session={session}>
      <QualificationsPage />
    </SignedIn>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('listing what the organisation has defined', () => {
  it('GivenDefinedQualifications_WhenOpened_ThenEachIsListedWithItsIdentifier', async () => {
    theOrganisation();

    show();

    await waitFor(() => expect(screen.getByText(/Forklift/)).toBeInTheDocument());
    expect(screen.getByText('q-1')).toBeInTheDocument();
    expect(screen.getByText(/Welding/)).toBeInTheDocument();
  });

  it('GivenNoneDefined_WhenOpened_ThenTheListIsEmptyRatherThanBroken', async () => {
    theOrganisation(NONE_DEFINED);

    show();

    // The busy region empties only once the listing has answered; until then an empty list proves
    // nothing about what the page does with an empty answer.
    await waitFor(() => expect(screen.getByRole('status')).toBeEmptyDOMElement());
    expect(screen.queryAllByRole('listitem')).toHaveLength(0);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('GivenTheListingIsRefused_WhenOpened_ThenTheRefusalIsShownRatherThanAnEmptyList', async () => {
    theOrganisation({
      '/api/qualifications': { status: 403, body: { detail: 'Your role does not permit this.' } },
    });

    show();

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Not permitted.'));
  });
});

describe('defining one', () => {
  it('GivenAName_WhenDefining_ThenItIsSentAndTheFieldIsCleared', async () => {
    const stub = theOrganisation(NONE_DEFINED);
    show();

    await userEvent.type(screen.getByLabelText('Name'), 'Forklift', WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Define' }));

    await waitFor(() => expect(callsTo(stub, 'POST', '/api/qualifications')).toHaveLength(1));
    const init = callsTo(stub, 'POST', '/api/qualifications')[0]?.[1];
    expect(JSON.parse(String(init?.body))).toEqual({ name: 'Forklift' });
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue(''));
  });

  it('GivenTheBackendRefuses_WhenDefining_ThenTheRefusalIsShownAndTheNameSurvives', async () => {
    theOrganisation({
      ...NONE_DEFINED,
      // Defining one is an Admin's, so a Scheduler is who the backend refuses.
      'POST /api/qualifications': {
        status: 403,
        body: { detail: 'Your role does not permit this.' },
      },
    });
    show(A_SCHEDULER);

    await userEvent.type(screen.getByLabelText('Name'), 'Forklift', WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Define' }));

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Not permitted.'));
    expect(screen.getByLabelText('Name')).toHaveValue('Forklift');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Define' })).toBeEnabled());
  });

  it('GivenADefinitionSucceeds_WhenItReturns_ThenTheListingIsAskedForAgain', async () => {
    const stub = theOrganisation();
    show();
    await waitFor(() => expect(screen.getByText(/Forklift/)).toBeInTheDocument());
    const before = callsTo(stub, 'GET', '/api/qualifications').length;

    await userEvent.type(screen.getByLabelText('Name'), 'Nursing', WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Define' }));

    await waitFor(() => {
      expect(callsTo(stub, 'GET', '/api/qualifications').length).toBeGreaterThan(before);
    });
  });

  /** Each definition is a qualification of its own, and none can be deleted afterwards. */
  it('GivenADefinitionInFlight_WhenDefinedAgain_ThenOnlyOneIsPosted', async () => {
    const routed = theOrganisation(NONE_DEFINED);
    const release = holdingWrites(routed);
    show();
    await userEvent.type(screen.getByLabelText('Name'), 'Nursing', WITHOUT_PAUSES);
    const define = screen.getByRole('button', { name: 'Define' });

    await userEvent.click(define);
    await userEvent.click(define);

    expect(define).toBeDisabled();
    release();
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue(''));
    expect(writes(routed)).toHaveLength(1);
    await waitFor(() => expect(define).toBeEnabled());
  });
});
