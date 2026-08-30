import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ActingAsProvider } from '../identity/ActingAs';
import { MePage } from './MePage';

/**
 * Employee self-service, which is the one place the acting identity has to carry an employee link
 * (ADR-012). Without it the backend cannot tell that the caller is the subject of their own record,
 * so the page has to explain that rather than showing a form that will always be refused.
 *
 * The span is validated here rather than by the browser: neither button submits the form, so the
 * `required` attributes never run and an empty field would otherwise reach `new Date('')` and
 * surface to the user as a bare "Invalid time value".
 */

const EMPLOYEE = '11111111-1111-4111-8111-111111111111';

function accepting() {
  const stub = vi.fn(() =>
    Promise.resolve({ ok: true, status: 204, text: () => Promise.resolve('') }),
  );
  vi.stubGlobal('fetch', stub);
  return stub;
}

function refusing(status: number, body: unknown) {
  const stub = vi.fn(() =>
    Promise.resolve({
      ok: false,
      status,
      text: () => Promise.resolve(JSON.stringify(body)),
    }),
  );
  vi.stubGlobal('fetch', stub);
  return stub;
}

function actingAsEmployee(employee: string | null) {
  window.localStorage.setItem(
    'hrsched.acting-as',
    JSON.stringify({
      organisation: '22222222-2222-4222-8222-222222222222',
      user: '33333333-3333-4333-8333-333333333333',
      role: 'EMPLOYEE',
      employee,
    }),
  );
}

function show() {
  render(
    <ActingAsProvider>
      <MePage />
    </ActingAsProvider>,
  );
}

async function fillSpan(from: string, to: string) {
  if (from !== '') {
    await userEvent.type(screen.getByLabelText('From'), from);
  }
  if (to !== '') {
    await userEvent.type(screen.getByLabelText('To'), to);
  }
}

function bodyOf(stub: ReturnType<typeof accepting>): Record<string, unknown> {
  const call = stub.mock.calls[0]?.[1] as RequestInit | undefined;
  if (typeof call?.body !== 'string') {
    throw new Error('nothing was sent');
  }
  return JSON.parse(call.body) as Record<string, unknown>;
}

beforeEach(() => {
  window.localStorage.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
});

describe('without an employee link', () => {
  it('GivenNoEmployeeLink_WhenOpened_ThenItExplainsWhatToDoRatherThanShowingAForm', () => {
    actingAsEmployee(null);

    show();

    expect(screen.getByText(/acting as employee/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'I cannot work this' })).not.toBeInTheDocument();
  });

  it('GivenNoEmployeeLink_WhenOpened_ThenItSaysWhyTheLinkIsNeeded', () => {
    actingAsEmployee(null);

    show();

    expect(screen.getByText(/different identities on purpose/i)).toBeInTheDocument();
  });
});

describe('validating the span before anything is sent', () => {
  it('GivenBothFieldsBlank_WhenDeclaring_ThenItAsksForBothAndSendsNothing', async () => {
    actingAsEmployee(EMPLOYEE);
    const stub = accepting();
    show();

    await userEvent.click(screen.getByRole('button', { name: 'I cannot work this' }));

    expect(screen.getByRole('alert')).toHaveTextContent('Give both a start and an end.');
    expect(stub).not.toHaveBeenCalled();
  });

  it('GivenOnlyAStart_WhenDeclaring_ThenItAsksForBothAndSendsNothing', async () => {
    actingAsEmployee(EMPLOYEE);
    const stub = accepting();
    show();

    await fillSpan('2026-10-05T08:00', '');
    await userEvent.click(screen.getByRole('button', { name: 'I cannot work this' }));

    expect(screen.getByRole('alert')).toHaveTextContent('Give both a start and an end.');
    expect(stub).not.toHaveBeenCalled();
  });

  it('GivenAnEndBeforeItsStart_WhenDeclaring_ThenItIsRefusedBeforeAnyRequest', async () => {
    actingAsEmployee(EMPLOYEE);
    const stub = accepting();
    show();

    await fillSpan('2026-10-05T16:00', '2026-10-05T08:00');
    await userEvent.click(screen.getByRole('button', { name: 'I cannot work this' }));

    expect(screen.getByRole('alert')).toHaveTextContent('The end has to come after the start.');
    expect(stub).not.toHaveBeenCalled();
  });

  it('GivenAnEndEqualToItsStart_WhenDeclaring_ThenItIsRefusedBeforeAnyRequest', async () => {
    actingAsEmployee(EMPLOYEE);
    const stub = accepting();
    show();

    await fillSpan('2026-10-05T08:00', '2026-10-05T08:00');
    await userEvent.click(screen.getByRole('button', { name: 'I cannot work this' }));

    expect(screen.getByRole('alert')).toHaveTextContent('The end has to come after the start.');
    expect(stub).not.toHaveBeenCalled();
  });
});

describe('declaring unavailability', () => {
  it('GivenAValidSpan_WhenDeclaring_ThenTheRequestCarriesIsoInstants', async () => {
    actingAsEmployee(EMPLOYEE);
    const stub = accepting();
    show();

    await fillSpan('2026-10-05T08:00', '2026-10-05T16:00');
    await userEvent.click(screen.getByRole('button', { name: 'I cannot work this' }));

    await waitFor(() => expect(stub).toHaveBeenCalled());
    const sent = bodyOf(stub);
    expect(sent.start).toMatch(/^\d{4}-\d{2}-\d{2}T.*Z$/);
    expect(sent.end).toMatch(/^\d{4}-\d{2}-\d{2}T.*Z$/);
  });

  it('GivenAValidSpan_WhenDeclaring_ThenItIsSentToTheEmployeesOwnRecord', async () => {
    actingAsEmployee(EMPLOYEE);
    const stub = accepting();
    show();

    await fillSpan('2026-10-05T08:00', '2026-10-05T16:00');
    await userEvent.click(screen.getByRole('button', { name: 'I cannot work this' }));

    await waitFor(() => expect(stub).toHaveBeenCalled());
    expect(stub.mock.calls[0]?.[0]).toBe(`/api/employees/${EMPLOYEE}/unavailability`);
  });

  it('GivenTheBackendAccepts_WhenDeclaring_ThenItSaysNobodyWillBeRosteredThen', async () => {
    actingAsEmployee(EMPLOYEE);
    accepting();
    show();

    await fillSpan('2026-10-05T08:00', '2026-10-05T16:00');
    await userEvent.click(screen.getByRole('button', { name: 'I cannot work this' }));

    await waitFor(() =>
      expect(screen.getByText('Recorded: you cannot work then.')).toBeInTheDocument(),
    );
  });

  it('GivenTheBackendRefuses_WhenDeclaring_ThenTheRefusalIsShownRatherThanASuccess', async () => {
    actingAsEmployee(EMPLOYEE);
    refusing(400, { detail: 'That overlaps a span you already declared.' });
    show();

    await fillSpan('2026-10-05T08:00', '2026-10-05T16:00');
    await userEvent.click(screen.getByRole('button', { name: 'I cannot work this' }));

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent(
        'That overlaps a span you already declared.',
      ),
    );
    expect(screen.queryByText('Recorded: you cannot work then.')).not.toBeInTheDocument();
  });
});

describe('stating a preference', () => {
  it('GivenAChosenStrength_WhenStatingAPreference_ThenThatStrengthIsSent', async () => {
    actingAsEmployee(EMPLOYEE);
    const stub = accepting();
    show();

    await fillSpan('2026-10-05T08:00', '2026-10-05T16:00');
    await userEvent.selectOptions(screen.getByLabelText('Preference'), 'PREFERS_NOT');
    await userEvent.click(screen.getByRole('button', { name: 'State a preference' }));

    await waitFor(() => expect(stub).toHaveBeenCalled());
    expect(bodyOf(stub).strength).toBe('PREFERS_NOT');
  });

  it('GivenAPreferenceRecorded_WhenShown_ThenItSaysItNeverBlocksARoster', async () => {
    actingAsEmployee(EMPLOYEE);
    accepting();
    show();

    await fillSpan('2026-10-05T08:00', '2026-10-05T16:00');
    await userEvent.click(screen.getByRole('button', { name: 'State a preference' }));

    await waitFor(() =>
      expect(
        screen.getByText('Recorded: a preference, which never blocks a roster.'),
      ).toBeInTheDocument(),
    );
  });
});
