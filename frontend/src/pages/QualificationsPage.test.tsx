import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ActingAsProvider } from '../identity/ActingAs';
import { QualificationsPage } from './QualificationsPage';

/**
 * A qualification is a named ticket scoped to one organisation, and a shift may only require one
 * this organisation has defined. That makes this the smallest screen in the application and the
 * vocabulary everything else depends on.
 */

const DEFINED = [
  { id: 'q-1', name: 'Forklift' },
  { id: 'q-2', name: 'Welding' },
];

function routeTo(handlers: {
  list?: unknown;
  onPost?: () => { ok: boolean; status: number; body: unknown };
}) {
  const stub = vi.fn((path: string, init?: RequestInit) => {
    if (init?.method === 'POST') {
      const outcome = handlers.onPost?.() ?? { ok: true, status: 201, body: { id: 'q-3' } };
      return Promise.resolve({
        ok: outcome.ok,
        status: outcome.status,
        headers: new Headers(),
        text: () => Promise.resolve(JSON.stringify(outcome.body)),
      });
    }
    void path;
    return Promise.resolve({
      ok: true,
      status: 200,
      headers: new Headers(),
      text: () => Promise.resolve(JSON.stringify(handlers.list ?? [])),
    });
  });
  vi.stubGlobal('fetch', stub);
  return stub;
}

function show() {
  render(
    <ActingAsProvider>
      <QualificationsPage />
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

describe('listing what the organisation has defined', () => {
  it('GivenDefinedQualifications_WhenOpened_ThenEachIsListedWithItsIdentifier', async () => {
    routeTo({ list: DEFINED });

    show();

    await waitFor(() => expect(screen.getByText(/Forklift/)).toBeInTheDocument());
    expect(screen.getByText('q-1')).toBeInTheDocument();
    expect(screen.getByText(/Welding/)).toBeInTheDocument();
  });

  it('GivenNoneDefined_WhenOpened_ThenTheListIsEmptyRatherThanBroken', async () => {
    routeTo({ list: [] });

    show();

    await waitFor(() => expect(screen.getByRole('button', { name: 'Define' })).toBeEnabled());
    expect(screen.queryAllByRole('listitem')).toHaveLength(0);
  });

  it('GivenTheListingIsRefused_WhenOpened_ThenTheRefusalIsShownRatherThanAnEmptyList', async () => {
    const stub = vi.fn(() =>
      Promise.resolve({
        ok: false,
        status: 403,
        headers: new Headers(),
        text: () => Promise.resolve(JSON.stringify({ detail: 'Your role does not permit this.' })),
      }),
    );
    vi.stubGlobal('fetch', stub);

    show();

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Your role may not do this.'),
    );
  });
});

describe('defining one', () => {
  it('GivenAName_WhenDefining_ThenItIsSentAndTheFieldIsCleared', async () => {
    const stub = routeTo({ list: [] });
    show();

    await userEvent.type(screen.getByLabelText('Name'), 'Forklift');
    await userEvent.click(screen.getByRole('button', { name: 'Define' }));

    await waitFor(() => {
      const post = stub.mock.calls.find((call) => call[1]?.method === 'POST');
      expect(post).toBeDefined();
      expect(JSON.parse(post?.[1]?.body as string)).toEqual({ name: 'Forklift' });
    });
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue(''));
  });

  it('GivenTheBackendRefuses_WhenDefining_ThenTheRefusalIsShownAndTheNameSurvives', async () => {
    routeTo({
      list: [],
      onPost: () => ({ ok: false, status: 403, body: { detail: 'Only an Admin may.' } }),
    });
    show();

    await userEvent.type(screen.getByLabelText('Name'), 'Forklift');
    await userEvent.click(screen.getByRole('button', { name: 'Define' }));

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Your role may not do this.'),
    );
    expect(screen.getByLabelText('Name')).toHaveValue('Forklift');
  });

  it('GivenADefinitionSucceeds_WhenItReturns_ThenTheListingIsAskedForAgain', async () => {
    const stub = routeTo({ list: DEFINED });
    show();
    await waitFor(() => expect(screen.getByText(/Forklift/)).toBeInTheDocument());
    const before = stub.mock.calls.filter((call) => call[1]?.method !== 'POST').length;

    await userEvent.type(screen.getByLabelText('Name'), 'Nursing');
    await userEvent.click(screen.getByRole('button', { name: 'Define' }));

    await waitFor(() => {
      const after = stub.mock.calls.filter((call) => call[1]?.method !== 'POST').length;
      expect(after).toBeGreaterThan(before);
    });
  });
});
