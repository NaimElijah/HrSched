import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { callsTo, routeTo } from '../test-support/routeTo';
import type { Answer } from '../test-support/routeTo';
import { A_TENANT, SignedIn } from '../test-support/session';
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

function show() {
  render(
    <SignedIn session={A_TENANT}>
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

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Your role may not do this.'),
    );
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
      'POST /api/qualifications': { status: 403, body: { detail: 'Only an Admin may.' } },
    });
    show();

    await userEvent.type(screen.getByLabelText('Name'), 'Forklift', WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Define' }));

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Your role may not do this.'),
    );
    expect(screen.getByLabelText('Name')).toHaveValue('Forklift');
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
});
