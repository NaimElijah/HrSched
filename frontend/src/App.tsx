import { useEffect, useRef, useState } from 'react';
import { ApiError } from './api/problem';
import { workforce } from './api/workforce';
import { ChangePasswordPage, PASSWORD_CHANGED } from './auth/ChangePasswordPage';
import { SessionBar } from './auth/SessionBar';
import { SignInPage } from './auth/SignInPage';
import { SignOutUnconfirmedPage } from './auth/SignOutUnconfirmedPage';
import { useSession } from './auth/SessionProvider';
import type { SessionState } from './auth/SessionProvider';
import { Failure } from './components/Feedback';
import { focusHeading } from './components/focus';
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

type Tab = (typeof TENANT_TABS)[number] | (typeof OPERATOR_TABS)[number];

/** What each screen outside the shell is called, in the document title as on the screen. */
const SCREEN_NAMES: Readonly<Record<Exclude<SessionState, 'signed-in'>, string>> = {
  restoring: 'Restoring your session',
  'restore-failed': 'Your session could not be restored',
  'signed-out': 'Sign in',
  'sign-out-unconfirmed': 'Sign-out not confirmed',
  'password-change-required': 'Change your password',
};

const APPLICATION = 'HrSched';

/** The document title for a view: what a browser's tab list and a screen reader's window list say. */
function titleOf(view: string): string {
  return `${view} · ${APPLICATION}`;
}

/**
 * The shell: who is signed in, tab navigation, and the planning span the scheduling pages share.
 *
 * What is drawn is decided by the session state, and there are six of them. While the refresh
 * cookie is being tried the app says so quietly rather than flashing a sign-in form at somebody who
 * turns out to be signed in. If that attempt cannot finish, it says what went wrong and offers to
 * try again: a backend nobody could reach has said nothing about whether somebody is signed in, and
 * a session that could not be read back after a good refresh is still live. Signed out, there is
 * nothing here but the form, which says why when a session on screen has just ended. A sign-out the
 * backend did not confirm shows no form either, but a screen of its own that says this browser may
 * still be signed in and offers to sign out again (#250). With a temporary
 * password still pending there is nothing but the password screen — not because the tabs would be
 * empty, but because the backend refuses that account everything else, and offering tabs that all
 * answer 403 would be a lie the UI told.
 *
 * **A view change moves focus to the heading of what appeared, and the title names it** (#257,
 * C4): a tab, a sign-in, the end of a forced password change, a retried restore. Everything in
 * `main` is replaced, and the control that was pressed either stays where it was or is gone, so a
 * screen-reader user would otherwise hear nothing of it. A first load takes no focus: nothing was
 * pressed, and the page begins where a page does.
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
  // The generation the Rosters page is waiting for, held here because that page is gone whenever
  // another tab is open, and a solve takes minutes (ADR-046). Tied to the session that started it,
  // so nobody signing in after is shown a wait, or an outcome, that was somebody else's.
  const [generation, setGeneration] = useState<{ session: string; id: string } | null>(null);
  const waitingFor = generation?.session === key ? generation.id : null;
  // Set a render after the shell appears, because a live region announces a change to what it
  // holds, not what it was inserted holding.
  const [notice, setNotice] = useState<string | null>(null);
  const main = useRef<HTMLElement>(null);
  // The view last settled on, or null until the first restore has ended.
  const shown = useRef<string | null>(null);

  const operator = session?.kind === 'OPERATOR';
  const tabs = operator ? OPERATOR_TABS : TENANT_TABS;
  const current: Tab = (tabs as readonly Tab[]).includes(tab) ? tab : (tabs[0] as Tab);
  const view = state === 'signed-in' ? current : SCREEN_NAMES[state];

  useEffect(() => {
    document.title = titleOf(view);
    const previous = shown.current;
    if (state === 'restoring') {
      // A retry removes the button that had focus, so what it ends in is a view change even when
      // it fails the same way again. The first restore is not one.
      if (previous !== null) {
        shown.current = view;
      }
      return;
    }
    shown.current = view;
    if (previous === null || previous === view) {
      return;
    }
    focusHeading(main.current);
    // The forced change screen goes the moment the change is accepted, its own status with it, so
    // the shell says it; any other view change ends what it said.
    setNotice(
      previous === SCREEN_NAMES['password-change-required'] && state === 'signed-in'
        ? PASSWORD_CHANGED
        : null,
    );
  }, [state, view]);

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
        <main ref={main}>
          <h2>{SCREEN_NAMES['restore-failed']}</h2>
          <Failure error={restoreFailure} />
          <button type="button" onClick={retryRestore}>
            Try again
          </button>
        </main>
      </div>
    );
  }

  if (state === 'sign-out-unconfirmed') {
    return (
      <div className="app">
        <header>
          <h1>HrSched</h1>
        </header>
        <main ref={main}>
          <SignOutUnconfirmedPage />
        </main>
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
        <main ref={main}>
          <SignInPage />
        </main>
      </div>
    );
  }

  if (state === 'password-change-required') {
    return (
      <div className="app">
        <header>
          <h1>HrSched</h1>
        </header>
        <main ref={main}>
          <ChangePasswordPage />
        </main>
      </div>
    );
  }

  return (
    <div className="app">
      <header>
        <h1>HrSched</h1>
        <p className="subtitle">Staff shift scheduling. Rules are configuration, never code.</p>
        <SessionBar onChangePassword={() => setTab('Password')} />
        {/* The shell's own read, so it is said in the shell's landmark, apart from the screen's. */}
        <Failure error={people.error} />
      </header>

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

      <main ref={main}>
        <p className="done" role="status">
          {notice}
        </p>
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
        {current === 'Rosters' ? (
          <RostersPage
            span={span}
            people={people.value ?? []}
            waitingFor={waitingFor}
            onWaitingFor={(id) => setGeneration(id === null ? null : { session: key, id })}
          />
        ) : null}
        {current === 'Swaps' ? <SwapsPage people={people.value ?? []} /> : null}
        {current === 'Me' ? <MePage span={span} people={people.value ?? []} /> : null}
        {current === 'Organisations' ? <OrganisationsPage /> : null}
        {current === 'Password' ? <ChangePasswordPage /> : null}
      </main>
    </div>
  );
}
