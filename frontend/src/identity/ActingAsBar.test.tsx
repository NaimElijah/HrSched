import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { Employee } from '../api/workforce';
import { ActingAsProvider } from './ActingAs';
import { ActingAsBar } from './ActingAsBar';

/**
 * The identity switcher, which is deliberately the loudest thing on the page because there is no
 * authentication behind it.
 *
 * Its one piece of real logic is the synthetic option: an EMPLOYEE may not list colleagues, so the
 * picker is empty for exactly the role that needs it. Without the synthetic entry the control would
 * read "nobody" while `X-Employee` was still being sent — the interface disagreeing with the
 * request it is making.
 */

const EMPLOYEE = '11111111-1111-4111-8111-111111111111';

const COLLEAGUES: Employee[] = [
  { id: EMPLOYEE, name: 'Ada', qualifications: [] },
  { id: '44444444-4444-4444-8444-444444444444', name: 'Grace', qualifications: [] },
];

function actingAs(role: string, employee: string | null) {
  window.localStorage.setItem(
    'hrsched.acting-as',
    JSON.stringify({
      organisation: '22222222-2222-4222-8222-222222222222',
      user: '33333333-3333-4333-8333-333333333333',
      role,
      employee,
    }),
  );
}

function show(employees: readonly Employee[]) {
  render(
    <ActingAsProvider>
      <ActingAsBar employees={employees} />
    </ActingAsProvider>,
  );
}

const picker = () => screen.getByLabelText('Acting as employee');

beforeEach(() => {
  window.localStorage.clear();
});

afterEach(() => {
  window.localStorage.clear();
});

describe('warning that there is no authentication', () => {
  it('GivenTheBar_WhenRendered_ThenItSaysIdentityIsClaimedRatherThanProven', () => {
    actingAs('ADMIN', null);

    show([]);

    expect(screen.getByText(/No authentication/)).toBeInTheDocument();
  });
});

describe('the employee picker', () => {
  it('GivenAnEmployeeAbsentFromTheList_WhenRendered_ThenASyntheticOptionKeepsItHonest', () => {
    actingAs('EMPLOYEE', EMPLOYEE);

    show([]);

    expect(picker()).toHaveValue(EMPLOYEE);
    expect(within(picker()).getByRole('option', { name: EMPLOYEE })).toBeInTheDocument();
  });

  it('GivenAnEmployeePresentInTheList_WhenRendered_ThenNoDuplicateOptionIsAdded', () => {
    actingAs('ADMIN', EMPLOYEE);

    show(COLLEAGUES);

    expect(within(picker()).getAllByRole('option', { name: 'Ada' })).toHaveLength(1);
    expect(within(picker()).queryByRole('option', { name: EMPLOYEE })).not.toBeInTheDocument();
  });

  it('GivenNoEmployeeLink_WhenRendered_ThenNoSyntheticOptionAppears', () => {
    actingAs('ADMIN', null);

    show(COLLEAGUES);

    expect(picker()).toHaveValue('');
    // Scoped to the picker: the role select contributes options of its own.
    expect(within(picker()).getAllByRole('option')).toHaveLength(COLLEAGUES.length + 1);
  });

  /** An operator belongs to no organisation, so they are emphatically not one of its employees. */
  it('GivenTheOperatorRole_WhenRendered_ThenTheEmployeePickerIsDisabled', () => {
    actingAs('OPERATOR', null);

    show(COLLEAGUES);

    expect(picker()).toBeDisabled();
  });

  it('GivenAnEmployeeChosen_WhenClearedToNobody_ThenTheLinkBecomesNullRatherThanEmpty', async () => {
    actingAs('ADMIN', EMPLOYEE);
    show(COLLEAGUES);

    await userEvent.selectOptions(picker(), '');

    const stored = JSON.parse(window.localStorage.getItem('hrsched.acting-as') as string) as {
      employee: unknown;
    };
    expect(stored.employee).toBeNull();
  });

  it('GivenAColleagueChosen_WhenSelected_ThenTheLinkIsStored', async () => {
    actingAs('ADMIN', null);
    show(COLLEAGUES);

    await userEvent.selectOptions(picker(), EMPLOYEE);

    const stored = JSON.parse(window.localStorage.getItem('hrsched.acting-as') as string) as {
      employee: unknown;
    };
    expect(stored.employee).toBe(EMPLOYEE);
  });
});

describe('switching role', () => {
  /**
   * An operator belongs to no organisation, so they cannot be one of its employees. Switching to
   * OPERATOR while a link is held has to clear it, or X-Employee would keep being sent by an actor
   * the backend does not treat as a tenant at all.
   */
  it('GivenAnEmployeeLink_WhenSwitchingToOperator_ThenTheLinkIsCleared', async () => {
    actingAs('EMPLOYEE', EMPLOYEE);
    show(COLLEAGUES);

    await userEvent.selectOptions(screen.getByLabelText('Role'), 'OPERATOR');

    const stored = JSON.parse(window.localStorage.getItem('hrsched.acting-as') as string) as {
      employee: unknown;
    };
    expect(stored.employee).toBeNull();
  });

  it('GivenAnEmployeeLink_WhenSwitchingToAnotherTenantRole_ThenTheLinkSurvives', async () => {
    actingAs('EMPLOYEE', EMPLOYEE);
    show(COLLEAGUES);

    await userEvent.selectOptions(screen.getByLabelText('Role'), 'SCHEDULER');

    const stored = JSON.parse(window.localStorage.getItem('hrsched.acting-as') as string) as {
      employee: unknown;
    };
    expect(stored.employee).toBe(EMPLOYEE);
  });
});

describe('switching organisation', () => {
  it('GivenNewOrganisationClicked_WhenPressed_ThenAFreshIdentifierIsMinted', async () => {
    actingAs('ADMIN', null);
    show([]);
    const before = (screen.getByLabelText('Organisation') as HTMLInputElement).value;

    await userEvent.click(screen.getByRole('button', { name: 'New organisation' }));

    expect((screen.getByLabelText('Organisation') as HTMLInputElement).value).not.toBe(before);
  });
});
