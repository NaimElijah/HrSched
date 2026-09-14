import { useState } from 'react';
import { identityKey } from './api/client';
import { ApiError } from './api/problem';
import { workforce } from './api/workforce';
import { Failure } from './components/Feedback';
import { defaultSpan, localDate, startOfLocalDay } from './components/localDates';
import { useAsync } from './components/useAsync';
import { ActingAsBar } from './identity/ActingAsBar';
import { useActingAs } from './identity/ActingAs';
import { MePage } from './pages/MePage';
import { PeoplePage } from './pages/PeoplePage';
import { QualificationsPage } from './pages/QualificationsPage';
import { RostersPage } from './pages/RostersPage';
import { RulesPage } from './pages/RulesPage';
import { ShiftsPage } from './pages/ShiftsPage';

const TABS = ['People', 'Qualifications', 'Rules', 'Shifts', 'Rosters', 'Me'] as const;
type Tab = (typeof TABS)[number];

/**
 * The shell: the acting-as bar, tab navigation, and the planning span the scheduling pages share.
 *
 * Navigation is local state rather than a router. Nothing here needs a shareable URL yet, and a
 * routing library would be a dependency bought against a requirement nobody has stated. The trigger
 * to add one is the first time somebody needs to send a colleague a link to a specific roster.
 */
export function App() {
  const [tab, setTab] = useState<Tab>('People');
  const [span, setSpan] = useState(() => defaultSpan(new Date()));
  const { identity } = useActingAs();

  // The bar needs the employee list to populate its picker, and an EMPLOYEE is not permitted to
  // read it — that one refusal is expected and is answered with an empty list. Every other failure
  // is surfaced, because silently showing an empty picker during an outage is indistinguishable
  // from an organisation with no staff.
  // Nothing is fetched until there is an organisation to fetch it for. On a first visit the bar is
  // still onboarding one, and asking now would send an empty X-Organisation and earn a wall of 400s.
  const ready = identity.organisation !== '';

  const people = useAsync(
    () =>
      !ready
        ? Promise.resolve([])
        : workforce.findEmployees(identity).catch((error: unknown) => {
            if (error instanceof ApiError && error.isForbidden) {
              return [];
            }
            throw error;
          }),
    identityKey(identity),
  );

  return (
    <div className="app">
      <header>
        <h1>HrSched</h1>
        <p className="subtitle">Staff shift scheduling. Rules are configuration, never code.</p>
      </header>

      <ActingAsBar employees={people.value ?? []} />
      <Failure error={people.error} />

      <nav>
        {TABS.map((candidate) => (
          <button
            key={candidate}
            type="button"
            className={candidate === tab ? 'tab tab--current' : 'tab'}
            aria-current={candidate === tab ? 'page' : undefined}
            onClick={() => setTab(candidate)}
          >
            {candidate}
          </button>
        ))}
      </nav>

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

      <main>
        {!ready ? <p>Setting up an organisation…</p> : null}
        {ready && tab === 'People' ? <PeoplePage /> : null}
        {ready && tab === 'Qualifications' ? <QualificationsPage /> : null}
        {ready && tab === 'Rules' ? <RulesPage /> : null}
        {ready && tab === 'Shifts' ? <ShiftsPage span={span} /> : null}
        {ready && tab === 'Rosters' ? <RostersPage span={span} /> : null}
        {ready && tab === 'Me' ? <MePage span={span} /> : null}
      </main>
    </div>
  );
}
