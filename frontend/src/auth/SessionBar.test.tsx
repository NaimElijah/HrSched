import { within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { endSession } from '../api/session';
import { A_TENANT, renderSignedIn, THE_OPERATOR } from '../test-support/session';
import { SessionBar } from './SessionBar';

/**
 * Who is signed in, reported as a fact.
 *
 * It replaced a bar that let anybody claim any role in any organisation, so what matters as much
 * as what it says is what it does not offer: nothing on it changes who is acting.
 */

afterEach(() => {
  endSession();
});

describe('what the session bar says', () => {
  it('GivenATenant_WhenShown_ThenItNamesTheAccountItsRoleAndItsOrganisation', () => {
    // Rendered on its own, so everything rendered is the bar.
    const { container: bar } = renderSignedIn(<SessionBar onChangePassword={() => undefined} />, {
      ...A_TENANT,
      role: 'SCHEDULER',
    });

    expect(bar).toHaveTextContent(`Signed in as ${A_TENANT.signInName}`);
    expect(bar).toHaveTextContent(`SCHEDULER in ${String(A_TENANT.organisation)}`);
  });

  it('GivenTheOperator_WhenShown_ThenItSaysPlatformOperatorAndNamesNoRole', () => {
    const { container: bar } = renderSignedIn(
      <SessionBar onChangePassword={() => undefined} />,
      THE_OPERATOR,
    );

    expect(bar).toHaveTextContent(`Signed in as ${THE_OPERATOR.signInName}`);
    expect(bar).toHaveTextContent('Platform operator');
    expect(bar).not.toHaveTextContent(/\b(ADMIN|SCHEDULER|EMPLOYEE)\b/);
  });

  it('GivenASession_WhenShown_ThenNothingOnItChangesWhoIsActing', () => {
    const { container: bar } = renderSignedIn(<SessionBar onChangePassword={() => undefined} />);

    expect(within(bar).queryAllByRole('combobox')).toHaveLength(0);
    expect(within(bar).queryAllByRole('textbox')).toHaveLength(0);
    expect(
      within(bar)
        .getAllByRole('button')
        .map((button) => button.textContent),
    ).toEqual(['Change password', 'Sign out']);
  });
});
