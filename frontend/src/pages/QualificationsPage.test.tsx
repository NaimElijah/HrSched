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

    // Only once the listing has answered, which the page says; until then an empty list proves
    // nothing about what the page does with an empty answer.
    await screen.findByText(/no qualifications/i);
    expect(screen.queryAllByRole('listitem')).toHaveLength(0);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('GivenTheListingIsRefused_WhenOpened_ThenTheRefusalIsShownRatherThanAnEmptyList', async () => {
    theOrganisation({
      '/api/qualifications': { status: 403, body: { detail: 'Your role does not permit this.' } },
    });

    show();

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Not permitted.'));
    // A refused listing is no evidence that there are none.
    expect(screen.queryByText(/no qualifications/i)).not.toBeInTheDocument();
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
    await waitFor(() => {
      const define = screen.getByRole('button', { name: 'Define' });
      expect(define).toBeEnabled();
      expect(define).not.toHaveAttribute('aria-disabled', 'true');
    });
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

    expect(define).toHaveAttribute('aria-disabled', 'true');
    release();
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue(''));
    expect(writes(routed)).toHaveLength(1);
    await waitFor(() => expect(define).not.toHaveAttribute('aria-disabled', 'true'));
  });

  /**
   * A disabled button drops the keyboard focus to the page in a browser (#257, C1). While the
   * definition is on its way the button says it is unavailable and keeps the focus.
   */
  it('GivenADefinitionInFlight_WhenPressed_ThenTheButtonKeepsFocusAndIsMarkedUnavailable', async () => {
    const routed = theOrganisation(NONE_DEFINED);
    const release = holdingWrites(routed);
    show();
    await userEvent.type(screen.getByLabelText('Name'), 'Nursing', WITHOUT_PAUSES);
    const define = screen.getByRole('button', { name: 'Define' });

    await userEvent.click(define);

    expect(define).toHaveAttribute('aria-disabled', 'true');
    // jsdom keeps focus on a disabled button, so this, not toHaveFocus(), is what C1 rests on.
    expect(define).not.toBeDisabled();
    expect(define).toHaveFocus();
    release();
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue(''));
  });

  /** The field empties on success, so without a word nothing says the definition was stored (C3). */
  it('GivenADefinitionSucceeds_WhenItReturns_ThenAStatusNamesWhatWasDefined', async () => {
    theOrganisation(NONE_DEFINED);
    show();
    await userEvent.type(screen.getByLabelText('Name'), 'Nursing', WITHOUT_PAUSES);
    // Captured before the write: a live region that appears along with its text is not read.
    const statuses = screen.getAllByRole('status');

    await userEvent.click(screen.getByRole('button', { name: 'Define' }));

    await waitFor(() =>
      expect(statuses.map((status) => status.textContent)).toContain('Defined Nursing.'),
    );
  });

  /** A success left on screen while the next one is typed reads as that one's (C9). */
  it('GivenADefinitionSucceeded_WhenTheFormIsEditedAgain_ThenTheSuccessIsNoLongerSaid', async () => {
    theOrganisation(NONE_DEFINED);
    show();
    await userEvent.type(screen.getByLabelText('Name'), 'Nursing', WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Define' }));
    await screen.findByText(/defined nursing/i);

    await userEvent.type(screen.getByLabelText('Name'), 'W', WITHOUT_PAUSES);

    expect(screen.queryByText(/defined nursing/i)).not.toBeInTheDocument();
  });
});

describe('an empty vocabulary', () => {
  /** An empty list says nothing at all to somebody who cannot see that it is empty (C8). */
  it('GivenNoneDefined_WhenOpened_ThenTheListSaysSo', async () => {
    theOrganisation(NONE_DEFINED);

    show();

    expect(await screen.findByText(/no qualifications/i)).toBeInTheDocument();
  });
});
