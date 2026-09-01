import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ActingAsProvider } from '../identity/ActingAs';
import { ShiftsPage } from './ShiftsPage';

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

function routeTo(options: { onPost?: () => { ok: boolean; status: number; body: unknown } } = {}) {
  const stub = vi.fn((path: string, init?: RequestInit) => {
    if (init?.method === 'POST') {
      const outcome = options.onPost?.() ?? { ok: true, status: 201, body: { id: 's-1' } };
      return Promise.resolve({
        ok: outcome.ok,
        status: outcome.status,
        headers: new Headers(),
        text: () => Promise.resolve(JSON.stringify(outcome.body)),
      });
    }
    const body = path.startsWith('/api/qualifications') ? QUALIFICATIONS : [];
    return Promise.resolve({
      ok: true,
      status: 200,
      headers: new Headers(),
      text: () => Promise.resolve(JSON.stringify(body)),
    });
  });
  vi.stubGlobal('fetch', stub);
  return stub;
}

function show() {
  render(
    <ActingAsProvider>
      <ShiftsPage span={SPAN} />
    </ActingAsProvider>,
  );
}

function postedBody(stub: ReturnType<typeof routeTo>): Record<string, unknown> {
  const post = stub.mock.calls.find((call) => call[1]?.method === 'POST');
  if (typeof post?.[1]?.body !== 'string') {
    throw new Error('no shift was posted');
  }
  return JSON.parse(post[1].body) as Record<string, unknown>;
}

beforeEach(() => {
  window.localStorage.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
});

describe('asking for the shifts in the span', () => {
  it('GivenASpan_WhenOpened_ThenBothEndsAreSentInTheQuery', async () => {
    const stub = routeTo();

    show();

    await waitFor(() => {
      const shifts = stub.mock.calls.find((call) => String(call[0]).startsWith('/api/shifts'));
      expect(shifts).toBeDefined();
      expect(String(shifts?.[0])).toContain(encodeURIComponent(SPAN.from));
      expect(String(shifts?.[0])).toContain(encodeURIComponent(SPAN.to));
    });
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
    const stub = routeTo();
    show();
    await waitFor(() => expect(screen.getByText('Forklift')).toBeInTheDocument());

    await userEvent.type(screen.getByLabelText('Ends'), '2026-10-05T16:00');
    await userEvent.click(screen.getByRole('button', { name: 'Define shift' }));

    expect(stub.mock.calls.some((call) => call[1]?.method === 'POST')).toBe(false);
  });

  it('GivenAMissingEnd_WhenDefining_ThenNothingIsSent', async () => {
    const stub = routeTo();
    show();
    await waitFor(() => expect(screen.getByText('Forklift')).toBeInTheDocument());

    await userEvent.type(screen.getByLabelText('Starts'), '2026-10-05T08:00');
    await userEvent.click(screen.getByRole('button', { name: 'Define shift' }));

    expect(stub.mock.calls.some((call) => call[1]?.method === 'POST')).toBe(false);
  });
});

describe('defining a shift', () => {
  it('GivenATypedHeadcount_WhenDefining_ThenItIsSentAsANumberNotAString', async () => {
    const stub = routeTo();
    show();
    await waitFor(() => expect(screen.getByText('Forklift')).toBeInTheDocument());

    await userEvent.type(screen.getByLabelText('Starts'), '2026-10-05T08:00');
    await userEvent.type(screen.getByLabelText('Ends'), '2026-10-05T16:00');
    await userEvent.clear(screen.getByLabelText('People needed'));
    await userEvent.type(screen.getByLabelText('People needed'), '3');
    await userEvent.click(screen.getByRole('button', { name: 'Define shift' }));

    await waitFor(() => expect(postedBody(stub).requiredHeadcount).toBe(3));
  });

  it('GivenAValidPeriod_WhenDefining_ThenBothInstantsAreSentAsIso', async () => {
    const stub = routeTo();
    show();
    await waitFor(() => expect(screen.getByText('Forklift')).toBeInTheDocument());

    await userEvent.type(screen.getByLabelText('Starts'), '2026-10-05T08:00');
    await userEvent.type(screen.getByLabelText('Ends'), '2026-10-05T16:00');
    await userEvent.click(screen.getByRole('button', { name: 'Define shift' }));

    await waitFor(() => {
      const sent = postedBody(stub);
      expect(sent.start).toMatch(/^\d{4}-\d{2}-\d{2}T.*Z$/);
      expect(sent.end).toMatch(/^\d{4}-\d{2}-\d{2}T.*Z$/);
    });
  });

  it('GivenAQualificationSelected_WhenDefining_ThenItIsRequiredOnTheShift', async () => {
    const stub = routeTo();
    show();
    await waitFor(() => expect(screen.getByText('Forklift')).toBeInTheDocument());

    await userEvent.type(screen.getByLabelText('Starts'), '2026-10-05T08:00');
    await userEvent.type(screen.getByLabelText('Ends'), '2026-10-05T16:00');
    await userEvent.selectOptions(screen.getByLabelText('Required qualifications'), ['q-1']);
    await userEvent.click(screen.getByRole('button', { name: 'Define shift' }));

    await waitFor(() => expect(postedBody(stub).requiredQualifications).toEqual(['q-1']));
  });

  it('GivenNoQualificationSelected_WhenDefining_ThenNoneIsRequired', async () => {
    const stub = routeTo();
    show();
    await waitFor(() => expect(screen.getByText('Forklift')).toBeInTheDocument());

    await userEvent.type(screen.getByLabelText('Starts'), '2026-10-05T08:00');
    await userEvent.type(screen.getByLabelText('Ends'), '2026-10-05T16:00');
    await userEvent.click(screen.getByRole('button', { name: 'Define shift' }));

    await waitFor(() => expect(postedBody(stub).requiredQualifications).toEqual([]));
  });

  it('GivenTheBackendRefuses_WhenDefining_ThenTheRefusalIsShown', async () => {
    routeTo({
      onPost: () => ({
        ok: false,
        status: 400,
        body: { detail: 'A shift must require at least one person.' },
      }),
    });
    show();
    await waitFor(() => expect(screen.getByText('Forklift')).toBeInTheDocument());

    await userEvent.type(screen.getByLabelText('Starts'), '2026-10-05T08:00');
    await userEvent.type(screen.getByLabelText('Ends'), '2026-10-05T16:00');
    await userEvent.click(screen.getByRole('button', { name: 'Define shift' }));

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent(
        'A shift must require at least one person.',
      ),
    );
  });
});
