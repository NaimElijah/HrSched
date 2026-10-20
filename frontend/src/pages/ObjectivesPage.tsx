import { useState } from 'react';
import { scheduling } from '../api/scheduling';
import type { ObjectiveWeights, ObjectiveWeightsRequest } from '../api/scheduling';
import { Busy, Failure } from '../components/Feedback';
import { useAsync } from '../components/useAsync';
import { useSessionKey } from '../auth/SessionProvider';
import { OBJECTIVE_FIELDS, type ObjectiveField } from './objectiveFields';

function stored(weights: ObjectiveWeights | null, key: ObjectiveField): string {
  const value = weights === null ? null : weights[key];
  return value === null || value === undefined ? '' : String(value);
}

/**
 * Admin: how this organisation weighs what makes one legal roster better than another (F35,
 * ADR-010).
 *
 * No weight is ever suggested. What matters more, fairness or fewer changes, is the organisation's
 * to say, and a number offered here would be a developer's answer. A blank field leaves that
 * objective unweighed.
 *
 * Each field shows the stored weight until it is edited, for the reason the rules form does:
 * revising replaces the weights whole, so a form that sent only the field just typed would unweigh
 * every other objective.
 */
export function ObjectivesPage() {
  // Only what the admin has typed; an empty string is a real edit meaning "stop weighing this".
  const [edits, setEdits] = useState<Partial<Record<ObjectiveField, string>>>({});
  const [failure, setFailure] = useState<unknown>(null);
  const sessionKey = useSessionKey();

  const current = useAsync(() => scheduling.findObjectives(), sessionKey);
  const loaded = current.value;

  const shown = (key: ObjectiveField): string => edits[key] ?? stored(loaded, key);

  async function revise(event: React.FormEvent) {
    event.preventDefault();
    setFailure(null);
    const weight = (key: ObjectiveField): number | undefined => {
      const value = shown(key).trim();
      return value === '' ? undefined : Number(value);
    };
    const body: ObjectiveWeightsRequest = {
      fairness: weight('fairness'),
      preferences: weight('preferences'),
      disruption: weight('disruption'),
    };
    try {
      await scheduling.reviseObjectives(body);
      setEdits({});
      current.reload();
    } catch (error) {
      setFailure(error);
    }
  }

  return (
    <section>
      <h2>What makes a roster better</h2>
      <p className="hint">
        Each objective is a count, and lower is better. A weight says how much it matters against
        the others; blank means it is not weighed. A draft&apos;s check shows each count and the
        weighted total. Weights never make a roster legal or illegal, and generation does not use
        them yet.
      </p>

      <form onSubmit={revise} className="grid">
        {OBJECTIVE_FIELDS.map(([key, name, measures]) => (
          <label key={key}>
            {name} weight ({measures})
            <input
              type="number"
              min="1"
              step="1"
              value={shown(key)}
              placeholder="not weighed"
              onChange={(event) => setEdits({ ...edits, [key]: event.target.value })}
            />
          </label>
        ))}
        <button type="submit">Weigh objectives</button>
      </form>

      <Failure error={failure} />
      <Failure error={current.error} />
      <Busy busy={current.busy} />
    </section>
  );
}
