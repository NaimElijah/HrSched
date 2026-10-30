import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { callsTo, routeTo, writes } from '../test-support/routeTo';
import type { Answer } from '../test-support/routeTo';
import { A_TENANT, SignedIn } from '../test-support/session';
import { ShiftsPage } from './ShiftsPage';
import { WITHOUT_PAUSES } from '../test-support/typing';

/**
 * Defining what needs covering.
 *
 * Headcount is typed into an input and must reach the wire as a number rather than a string: the
 * backend refuses anything below one, and a comparison against a string would not mean what it
 * looks like it means.
 *
 * The interesting negative result is in the validation block below -- this form's own date guard
 * turns out to be unreachable through the UI, because the browser refuses the submit first.
 */

const SPAN = { from: '2026-10-05T00:00:00.000Z', to: '2026-10-19T00:00:00.000Z' };

const QUALIFICATIONS = [
  { id: 'q-1', name: 'Forklift' },
  { id: 'q-2', name: 'Welding' },
];

/** Answers the shifts in the span, the qualifications, and a definition, unless overridden. */
function theOrganisation(overrides: Record<string, Answer> = {}) {
  return routeTo({
    '/api/shifts': { status: 200, body: [] },
    '/api/qualifications': { status: 200, body: QUALIFICATIONS },
    'POST /api/shifts': { status: 201, body: { id: 's-1' } },
    ...overrides,
  });
}

function show() {
  render(
    <SignedIn session={A_TENANT}>
      <ShiftsPage span={SPAN} />
    </SignedIn>,
  );
}

function postedBody(stub: ReturnType<typeof routeTo>): Record<string, unknown> {
  const body = callsTo(stub, 'POST', '/api/shifts')[0]?.[1]?.body;
  if (typeof body !== 'string') {
    throw new Error('no shift was posted to POST /api/shifts');
  }
  return JSON.parse(body) as Record<string, unknown>;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('asking for the shifts in the span', () => {
  it('GivenASpan_WhenOpened_ThenBothEndsAreSentInTheQuery', async () => {
    const stub = theOrganisation();

    show();

    await waitFor(() =>
      expect(stub.mock.calls.map(([path]) => path)).toContain(
        `/api/shifts?from=${encodeURIComponent(SPAN.from)}&to=${encodeURIComponent(SPAN.to)}`,
      ),
    );
  });
});

describe('what a listed shift requires', () => {
  it('GivenAShiftRequiringQualifications_WhenListed_ThenTheirNamesAreShown', async () => {
    theOrganisation({
      '/api/shifts': {
        status: 200,
        body: [
          {
            id: 's-1',
            start: '2026-10-05T08:00:00Z',
            end: '2026-10-05T16:00:00Z',
            zone: 'UTC',
            requiredHeadcount: 2,
            requiredQualifications: ['q-1', 'q-2'],
          },
        ],
      },
    });

    show();

    expect(await screen.findByText('Forklift, Welding')).toBeInTheDocument();
  });

  /** A name this page cannot resolve is shown as its identifier, never dropped or guessed at. */
  it('GivenAQualificationThisPageCannotName_WhenListed_ThenItsIdentifierIsShown', async () => {
    theOrganisation({
      '/api/shifts': {
        status: 200,
        body: [
          {
            id: 's-1',
            start: '2026-10-05T08:00:00Z',
            end: '2026-10-05T16:00:00Z',
            zone: 'UTC',
            requiredHeadcount: 1,
            requiredQualifications: ['q-unknown'],
          },
        ],
      },
    });

    show();

    expect(await screen.findByText('q-unknown')).toBeInTheDocument();
  });

  it('GivenAShiftMarkedUnpopular_WhenListed_ThenTheMarkIsShown', async () => {
    theOrganisation({
      '/api/shifts': {
        status: 200,
        body: [
          {
            id: 's-1',
            start: '2026-10-05T22:00:00Z',
            end: '2026-10-06T06:00:00Z',
            zone: 'UTC',
            requiredHeadcount: 1,
            requiredQualifications: [],
            unpopular: true,
          },
        ],
      },
    });

    show();

    expect(await screen.findByRole('cell', { name: 'Unpopular' })).toBeInTheDocument();
  });
});

describe('validating before anything is sent', () => {
  /**
   * Unlike MePage, this button submits a form, so the browser runs constraint validation on the
   * `required` fields and nothing reaches the handler at all. The page's own "Give both a start and
   * an end" branch is therefore unreachable through the UI here -- it guards the case where a value
   * is present but unparseable, which a native datetime input will not produce. What matters to a
   * user is the same either way: an incomplete shift is never sent.
   */
  it('GivenAMissingStart_WhenDefining_ThenNothingIsSent', async () => {
    const stub = theOrganisation();
    show();
    await waitFor(() => expect(screen.getByText('Forklift')).toBeInTheDocument());

    await userEvent.type(screen.getByLabelText('Ends'), '2026-10-05T16:00', WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Define shift' }));

    expect(writes(stub)).toHaveLength(0);
  });

  it('GivenAMissingEnd_WhenDefining_ThenNothingIsSent', async () => {
    const stub = theOrganisation();
    show();
    await waitFor(() => expect(screen.getByText('Forklift')).toBeInTheDocument());

    await userEvent.type(screen.getByLabelText('Starts'), '2026-10-05T08:00', WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Define shift' }));

    expect(writes(stub)).toHaveLength(0);
  });
});

describe('defining a shift', () => {
  it('GivenATypedHeadcount_WhenDefining_ThenItIsSentAsANumberNotAString', async () => {
    const stub = theOrganisation();
    show();
    await waitFor(() => expect(screen.getByText('Forklift')).toBeInTheDocument());

    await userEvent.type(screen.getByLabelText('Starts'), '2026-10-05T08:00', WITHOUT_PAUSES);
    await userEvent.type(screen.getByLabelText('Ends'), '2026-10-05T16:00', WITHOUT_PAUSES);
    await userEvent.clear(screen.getByLabelText('People needed'));
    await userEvent.type(screen.getByLabelText('People needed'), '3', WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Define shift' }));

    await waitFor(() => expect(postedBody(stub).requiredHeadcount).toBe(3));
  });

  /**
   * The times typed are wall-clock times where the reader is, which for this run is the zone
   * vite.config.ts pins: 08:00 and 16:00 in Los Angeles in October are 15:00 and 23:00 UTC.
   */
  it('GivenAValidPeriod_WhenDefining_ThenBothInstantsAreSentAsIso', async () => {
    const stub = theOrganisation();
    show();
    await waitFor(() => expect(screen.getByText('Forklift')).toBeInTheDocument());

    await userEvent.type(screen.getByLabelText('Starts'), '2026-10-05T08:00', WITHOUT_PAUSES);
    await userEvent.type(screen.getByLabelText('Ends'), '2026-10-05T16:00', WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Define shift' }));

    await waitFor(() => {
      const sent = postedBody(stub);
      expect(sent.start).toBe('2026-10-05T15:00:00.000Z');
      expect(sent.end).toBe('2026-10-05T23:00:00.000Z');
    });
  });

  /**
   * The backend counts a shift's working-time days in its own zone (R36), and the times above
   * were read in the reader's zone, so that is the one that must travel with them. The run pins
   * that zone (vite.config.ts), and it is not UTC, so a page that sent UTC regardless fails here.
   */
  it('GivenAValidPeriod_WhenDefining_ThenTheZoneItWasEnteredInIsSent', async () => {
    const stub = theOrganisation();
    show();
    await waitFor(() => expect(screen.getByText('Forklift')).toBeInTheDocument());

    await userEvent.type(screen.getByLabelText('Starts'), '2026-10-05T08:00', WITHOUT_PAUSES);
    await userEvent.type(screen.getByLabelText('Ends'), '2026-10-05T16:00', WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Define shift' }));

    await waitFor(() => expect(postedBody(stub).zone).toBe('America/Los_Angeles'));
  });

  it('GivenAQualificationSelected_WhenDefining_ThenItIsRequiredOnTheShift', async () => {
    const stub = theOrganisation();
    show();
    await waitFor(() => expect(screen.getByText('Forklift')).toBeInTheDocument());

    await userEvent.type(screen.getByLabelText('Starts'), '2026-10-05T08:00', WITHOUT_PAUSES);
    await userEvent.type(screen.getByLabelText('Ends'), '2026-10-05T16:00', WITHOUT_PAUSES);
    await userEvent.selectOptions(screen.getByLabelText('Required qualifications'), ['q-1']);
    await userEvent.click(screen.getByRole('button', { name: 'Define shift' }));

    await waitFor(() => expect(postedBody(stub).requiredQualifications).toEqual(['q-1']));
  });

  it('GivenNoQualificationSelected_WhenDefining_ThenNoneIsRequired', async () => {
    const stub = theOrganisation();
    show();
    await waitFor(() => expect(screen.getByText('Forklift')).toBeInTheDocument());

    await userEvent.type(screen.getByLabelText('Starts'), '2026-10-05T08:00', WITHOUT_PAUSES);
    await userEvent.type(screen.getByLabelText('Ends'), '2026-10-05T16:00', WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Define shift' }));

    await waitFor(() => expect(postedBody(stub).requiredQualifications).toEqual([]));
  });

  it('GivenTheUnpopularBoxTicked_WhenDefining_ThenTheShiftIsMarkedUnpopular', async () => {
    const stub = theOrganisation();
    show();
    await waitFor(() => expect(screen.getByText('Forklift')).toBeInTheDocument());

    await userEvent.type(screen.getByLabelText('Starts'), '2026-10-05T22:00', WITHOUT_PAUSES);
    await userEvent.type(screen.getByLabelText('Ends'), '2026-10-06T06:00', WITHOUT_PAUSES);
    await userEvent.click(screen.getByLabelText('Unpopular'));
    await userEvent.click(screen.getByRole('button', { name: 'Define shift' }));

    await waitFor(() => expect(postedBody(stub).unpopular).toBe(true));
  });

  /** Nobody said it is unpopular, so it is sent as not marked rather than left for the backend. */
  it('GivenTheUnpopularBoxLeftClear_WhenDefining_ThenTheShiftIsSentAsNotMarked', async () => {
    const stub = theOrganisation();
    show();
    await waitFor(() => expect(screen.getByText('Forklift')).toBeInTheDocument());

    await userEvent.type(screen.getByLabelText('Starts'), '2026-10-05T08:00', WITHOUT_PAUSES);
    await userEvent.type(screen.getByLabelText('Ends'), '2026-10-05T16:00', WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Define shift' }));

    await waitFor(() => expect(postedBody(stub).unpopular).toBe(false));
  });

  it('GivenTheBackendRefuses_WhenDefining_ThenTheRefusalIsShown', async () => {
    theOrganisation({
      'POST /api/shifts': {
        status: 400,
        body: { detail: 'A shift must require at least one person.' },
      },
    });
    show();
    await waitFor(() => expect(screen.getByText('Forklift')).toBeInTheDocument());

    await userEvent.type(screen.getByLabelText('Starts'), '2026-10-05T08:00', WITHOUT_PAUSES);
    await userEvent.type(screen.getByLabelText('Ends'), '2026-10-05T16:00', WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Define shift' }));

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent(
        'A shift must require at least one person.',
      ),
    );
  });
});
