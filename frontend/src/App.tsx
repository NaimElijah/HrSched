import { useState } from 'react';
import { identityKey } from './api/client';
import { workforce } from './api/workforce';
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

function defaultSpan(): { from: string; to: string } {
  const from = new Date();
  from.setUTCHours(0, 0, 0, 0);
  const to = new Date(from);
  to.setUTCDate(to.getUTCDate() + DEFAULT_SPAN_DAYS);
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

  // The bar needs the employee list to offer the link, and an employee cannot read it. Failing to
  // load is expected in that case, so it is held quietly here rather than shown as a page error.
  const people = useAsync(
    () => workforce.findEmployees(identity).catch(() => []),
    identityKey(identity),
  );

  return (
    <div className="app">
      <header>
        <h1>HrSched</h1>
        <p className="subtitle">Staff shift scheduling. Rules are configuration, never code.</p>
      </header>

      <ActingAsBar employees={people.value ?? []} />

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
          value={span.from.slice(0, 10)}
          onChange={(event) =>
            setSpan({ ...span, from: new Date(`${event.target.value}T00:00:00Z`).toISOString() })
          }
        />
        <input
          type="date"
          value={span.to.slice(0, 10)}
          onChange={(event) =>
            setSpan({ ...span, to: new Date(`${event.target.value}T00:00:00Z`).toISOString() })
          }
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
