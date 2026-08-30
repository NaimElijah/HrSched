import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ActingAsProvider } from '../identity/ActingAs';
import { PeoplePage } from './PeoplePage';

/**
 * Admin: who works here, what they hold, and what they are contracted to.
 *
 * Listing people returns their unavailability, which is personal data, so the backend gates it
 * behind Admin or Scheduler. An EMPLOYEE sees a 403, and the page has to say so rather than show an
 * empty table — an empty table reads as "nobody works here", which is a different and wrong claim.
 */

const PEOPLE = [
  { id: 'e-1', name: 'Ada', qualifications: ['q-1'] },
  { id: 'e-2', name: 'Grace', qualifications: [] },
];

const QUALIFICATIONS = [{ id: 'q-1', name: 'Forklift' }];

function routeTo(
  options: {
    people?: { ok: boolean; status: number; body: unknown };
    onWrite?: () => { ok: boolean; status: number; body: unknown };
  } = {},
) {
  const stub = vi.fn((path: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    if (method !== 'GET') {
      const outcome = options.onWrite?.() ?? { ok: true, status: 204, body: null };
      return Promise.resolve({
        ok: outcome.ok,
        status: outcome.status,
        text: () => Promise.resolve(outcome.body === null ? '' : JSON.stringify(outcome.body)),
      });
    }
    if (path.startsWith('/api/qualifications')) {
      return Promise.resolve({
        ok: true,
        status: 200,
        text: () => Promise.resolve(JSON.stringify(QUALIFICATIONS)),
      });
    }
    const people = options.people ?? { ok: true, status: 200, body: PEOPLE };
    return Promise.resolve({
      ok: people.ok,
      status: people.status,
      text: () => Promise.resolve(JSON.stringify(people.body)),
    });
  });
  vi.stubGlobal('fetch', stub);
  return stub;
}

function show() {
  render(
    <ActingAsProvider>
      <PeoplePage />
    </ActingAsProvider>,
  );
}

function writes(stub: ReturnType<typeof routeTo>) {
  return stub.mock.calls.filter((call) => (call[1]?.method ?? 'GET') !== 'GET');
}

beforeEach(() => {
  window.localStorage.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
});

describe('listing people', () => {
  it('GivenPeople_WhenOpened_ThenEachIsListedWithTheirIdentifier', async () => {
    routeTo();

    show();

    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());
    expect(screen.getByText('e-1')).toBeInTheDocument();
    expect(screen.getByText('Grace')).toBeInTheDocument();
  });

  it('GivenTheListingIs403_WhenOpened_ThenTheRefusalIsShownRatherThanAnEmptyTable', async () => {
    routeTo({
      people: { ok: false, status: 403, body: { detail: 'Your role does not permit this.' } },
    });

    show();

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Your role may not do this.'),
    );
  });
});

describe('adding an employee', () => {
  it('GivenAName_WhenAdded_ThenItIsSentAndTheFieldClears', async () => {
    const stub = routeTo();
    show();
    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());

    await userEvent.type(screen.getByLabelText('Name'), 'Marie');
    await userEvent.click(screen.getByRole('button', { name: 'Add employee' }));

    await waitFor(() => {
      const posted = writes(stub).find((call) => call[1]?.method === 'POST');
      expect(JSON.parse(posted?.[1]?.body as string)).toEqual({ name: 'Marie' });
    });
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue(''));
  });

  it('GivenTheBackendRefuses_WhenAdding_ThenTheRefusalIsShownAndTheNameSurvives', async () => {
    routeTo({ onWrite: () => ({ ok: false, status: 403, body: { detail: 'Only an Admin.' } }) });
    show();
    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());

    await userEvent.type(screen.getByLabelText('Name'), 'Marie');
    await userEvent.click(screen.getByRole('button', { name: 'Add employee' }));

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Your role may not do this.'),
    );
    expect(screen.getByLabelText('Name')).toHaveValue('Marie');
  });
});

describe('granting a qualification', () => {
  it('GivenAQualificationChosen_WhenGranting_ThenItIsSentForThatEmployee', async () => {
    const stub = routeTo();
    show();
    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());

    await userEvent.selectOptions(screen.getByLabelText('Grant a qualification to Ada'), 'q-1');

    await waitFor(() =>
      expect(writes(stub).map((call) => String(call[0]))).toContain(
        '/api/employees/e-1/qualifications/q-1',
      ),
    );
  });

  /** The placeholder option is not a qualification; selecting it must not send anything. */
  it('GivenTheChoosePlaceholder_WhenSelected_ThenNothingIsSent', async () => {
    const stub = routeTo();
    show();
    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());

    await userEvent.selectOptions(screen.getByLabelText('Grant a qualification to Ada'), '');

    expect(writes(stub)).toHaveLength(0);
  });

  it('GivenTheBackendRefusesAGrant_WhenGranting_ThenTheRefusalIsShown', async () => {
    routeTo({ onWrite: () => ({ ok: false, status: 404, body: { detail: 'No such resource.' } }) });
    show();
    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());

    await userEvent.selectOptions(screen.getByLabelText('Grant a qualification to Ada'), 'q-1');

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Nothing here for this organisation.'),
    );
  });
});

describe('setting contracted hours', () => {
  it('GivenTypedMinutes_WhenSet_ThenTheyAreSentAsANumber', async () => {
    const stub = routeTo();
    show();
    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());

    const fields = screen.getAllByLabelText('Contracted minutes per week');
    await userEvent.type(fields[0] as HTMLElement, '2400');
    await userEvent.click(screen.getAllByRole('button', { name: 'Set' })[0] as HTMLElement);

    await waitFor(() => {
      const put = writes(stub).find((call) => String(call[0]).endsWith('/contract'));
      expect(JSON.parse(put?.[1]?.body as string)).toEqual({ weeklyContractedMinutes: 2400 });
    });
  });

  it('GivenTheBackendRefusesTheTerms_WhenSet_ThenTheRefusalIsShown', async () => {
    routeTo({
      onWrite: () => ({
        ok: false,
        status: 400,
        body: { detail: 'weeklyContractedHours must be positive' },
      }),
    });
    show();
    await waitFor(() => expect(screen.getByText('Ada')).toBeInTheDocument());

    const fields = screen.getAllByLabelText('Contracted minutes per week');
    await userEvent.type(fields[0] as HTMLElement, '5');
    await userEvent.click(screen.getAllByRole('button', { name: 'Set' })[0] as HTMLElement);

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('weeklyContractedHours must be positive'),
    );
  });
});
