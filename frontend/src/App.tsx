import { useState } from 'react';
import { identityKey } from './api/client';
import { ApiError } from './api/problem';
import { workforce } from './api/workforce';
import { Failure } from './components/Feedback';
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

/** How many days forward the default planning span reaches. Not a domain rule — a UI convenience. */
const DEFAULT_SPAN_DAYS = 14;

/**
 * Midnight *local* time on the given date, as an instant.
 *
 * Local rather than UTC because shifts are entered with `datetime-local`, which the browser reads
 * as wall-clock time. A span built from UTC midnight would silently exclude shifts the user had
 * just created — for a reader west of Greenwich, an evening shift lands on the next UTC day.
 *
 * @returns null when the field is empty, which native date inputs allow
 */
function startOfLocalDay(date: string): string | null {
  if (date === '') {
    return null;
  }
  const parsed = new Date(`${date}T00:00:00`);
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString();
}

/** The local calendar date an instant falls on, which is what a date input expects. */
function localDate(instant: string): string {
  const date = new Date(instant);
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 10);
}

function defaultSpan(): { from: string; to: string } {
  const from = new Date();
  from.setHours(0, 0, 0, 0);
  const to = new Date(from);
  to.setDate(to.getDate() + DEFAULT_SPAN_DAYS);
  return { from: from.toISOString(), to: to.toISOString() };
}

/**
 * The shell: the acting-as bar, tab navigation, and the planning span the scheduling pages share.
 *
 * Navigation is local state rather than a router. Nothing here needs a shareable URL yet, and a
 * routing library would be a dependency bought against a requirement nobody has stated. The trigger
 * to add one is the first time somebody needs to send a colleague a link to a specific roster.
 */
export function App() {
  const [tab, setTab] = useState<Tab>('People');
  const [span, setSpan] = useState(defaultSpan);
  const { identity } = useActingAs();

  // The bar needs the employee list to populate its picker, and an EMPLOYEE is not permitted to
  // read it — that one refusal is expected and is answered with an empty list. Every other failure
  // is surfaced, because silently showing an empty picker during an outage is indistinguishable
  // from an organisation with no staff.
  const people = useAsync(
    () =>
      workforce.findEmployees(identity).catch((error: unknown) => {
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

      <label className="span">
        Planning span
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

      <main>
        {tab === 'People' ? <PeoplePage /> : null}
        {tab === 'Qualifications' ? <QualificationsPage /> : null}
        {tab === 'Rules' ? <RulesPage /> : null}
        {tab === 'Shifts' ? <ShiftsPage span={span} /> : null}
        {tab === 'Rosters' ? <RostersPage span={span} /> : null}
        {tab === 'Me' ? <MePage /> : null}
      </main>
    </div>
  );
}
