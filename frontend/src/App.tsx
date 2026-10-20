import { useState } from 'react';
import { ApiError } from './api/problem';
import { workforce } from './api/workforce';
import { ChangePasswordPage } from './auth/ChangePasswordPage';
import { SessionBar } from './auth/SessionBar';
import { SignInPage } from './auth/SignInPage';
import { useSession } from './auth/SessionProvider';
import { Failure } from './components/Feedback';
import { defaultSpan, localDate, startOfLocalDay } from './components/localDates';
import { useAsync } from './components/useAsync';
import { AccountsPage } from './pages/AccountsPage';
import { MePage } from './pages/MePage';
import { OrganisationsPage } from './pages/OrganisationsPage';
import { PeoplePage } from './pages/PeoplePage';
import { QualificationsPage } from './pages/QualificationsPage';
import { RostersPage } from './pages/RostersPage';
import { ObjectivesPage } from './pages/ObjectivesPage';
import { RulesPage } from './pages/RulesPage';
import { ShiftsPage } from './pages/ShiftsPage';
import { SwapsPage } from './pages/SwapsPage';

const TENANT_TABS = [
  'People',
  'Accounts',
  'Qualifications',
  'Rules',
  'Shifts',
  'Rosters',
  'Swaps',
  'Me',
  'Password',
] as const;

/**
 * The operator belongs to no organisation, so every tenant screen would have nothing to show. The
 * register is the whole of what they administer (ADR-030).
 */
const OPERATOR_TABS = ['Organisations', 'Password'] as const;

type Tab = (typeof TENANT_TABS)[number] | (typeof OPERATOR_TABS)[number] | 'Organisations';

/**
 * The shell: who is signed in, tab navigation, and the planning span the scheduling pages share.
 *
 * What is drawn is decided by the session state, and there are five of them. While the refresh
 * cookie is being tried the app says so quietly rather than flashing a sign-in form at somebody who
 * turns out to be signed in. If that attempt cannot finish, it says what went wrong and offers to
 * try again: a backend nobody could reach has said nothing about whether somebody is signed in, and
 * a session that could not be read back after a good refresh is still live. Signed out, there is
 * nothing here but the form. With a temporary
 * password still pending there is nothing but the password screen — not because the tabs would be
 * empty, but because the backend refuses that account everything else, and offering tabs that all
 * answer 403 would be a lie the UI told.
 *
 * **Tabs are chosen by account kind, never by role.** A role's refusal is still rendered as the 403
 * it is: the UI enforces nothing, and a screen that looked like it satisfied a requirement would be
 * no evidence that the rule holds.
 *
 * Navigation is local state rather than a router. Nothing here needs a shareable URL yet, and a
 * routing library would be a dependency bought against a requirement nobody has stated. The trigger
 * to add one is the first time somebody needs to send a colleague a link to a specific roster.
 */
export function App() {
  const { state, session, key, restoreFailure, retryRestore } = useSession();
  const [tab, setTab] = useState<Tab>('People');
  const [span, setSpan] = useState(() => defaultSpan(new Date()));

  const operator = session?.kind === 'OPERATOR';
  const tabs = operator ? OPERATOR_TABS : TENANT_TABS;
  const current: Tab = (tabs as readonly Tab[]).includes(tab) ? tab : (tabs[0] as Tab);

  // The pickers need the employee list, and an EMPLOYEE is not permitted to read it — that one
  // refusal is expected and is answered with an empty list. Every other failure is surfaced,
  // because silently showing an empty picker during an outage is indistinguishable from an
  // organisation with no staff. An operator is not in an organisation at all, so it asks nothing.
  const people = useAsync(
    () =>
      state !== 'signed-in' || operator
        ? Promise.resolve([])
        : workforce.findEmployees().catch((error: unknown) => {
            if (error instanceof ApiError && error.isForbidden) {
              return [];
            }
            throw error;
          }),
    `${key}|people`,
  );

  if (state === 'restoring') {
    return (
      <div className="app">
        <p role="status">Restoring your session…</p>
      </div>
    );
  }

  if (state === 'restore-failed') {
    return (
      <div className="app">
        <header>
          <h1>HrSched</h1>
        </header>
        <h2>Your session could not be restored</h2>
        <Failure error={restoreFailure} />
        <button type="button" onClick={retryRestore}>
          Try again
        </button>
      </div>
    );
  }

  if (state === 'signed-out') {
    return (
      <div className="app">
        <header>
          <h1>HrSched</h1>
          <p className="subtitle">Staff shift scheduling. Rules are configuration, never code.</p>
        </header>
        <SignInPage />
      </div>
    );
  }

  if (state === 'password-change-required') {
    return (
      <div className="app">
        <header>
          <h1>HrSched</h1>
        </header>
        <ChangePasswordPage />
      </div>
    );
  }

  return (
    <div className="app">
      <header>
        <h1>HrSched</h1>
        <p className="subtitle">Staff shift scheduling. Rules are configuration, never code.</p>
      </header>

      <SessionBar onChangePassword={() => setTab('Password')} />
      <Failure error={people.error} />

      <nav>
        {tabs.map((candidate) => (
          <button
            key={candidate}
            type="button"
            className={candidate === current ? 'tab tab--current' : 'tab'}
            aria-current={candidate === current ? 'page' : undefined}
            onClick={() => setTab(candidate)}
          >
            {candidate}
          </button>
        ))}
      </nav>

      {operator ? null : (
        <fieldset className="span">
          <legend>Planning span</legend>
          <label>
            Span from
            <input
              type="date"
              value={localDate(span.from)}
              onChange={(event) => {
                const changed = startOfLocalDay(event.target.value);
                if (changed !== null) {
                  setSpan({ ...span, from: changed });
                }
              }}
            />
          </label>
          <label>
            Span to
            <input
              type="date"
              value={localDate(span.to)}
              onChange={(event) => {
                const changed = startOfLocalDay(event.target.value);
                if (changed !== null) {
                  setSpan({ ...span, to: changed });
                }
              }}
            />
          </label>
        </fieldset>
      )}

      <main>
        {current === 'People' ? <PeoplePage /> : null}
        {current === 'Accounts' ? <AccountsPage /> : null}
        {current === 'Qualifications' ? <QualificationsPage /> : null}
        {current === 'Rules' ? (
          <>
            <RulesPage />
            <ObjectivesPage />
          </>
        ) : null}
        {current === 'Shifts' ? <ShiftsPage span={span} /> : null}
        {current === 'Rosters' ? <RostersPage span={span} people={people.value ?? []} /> : null}
        {current === 'Swaps' ? <SwapsPage people={people.value ?? []} /> : null}
        {current === 'Me' ? <MePage span={span} people={people.value ?? []} /> : null}
        {current === 'Organisations' ? <OrganisationsPage /> : null}
        {current === 'Password' ? <ChangePasswordPage /> : null}
      </main>
    </div>
  );
}
