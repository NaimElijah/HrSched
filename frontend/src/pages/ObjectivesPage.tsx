import { useId, useRef, useState } from 'react';
import { scheduling } from '../api/scheduling';
import type { ObjectiveWeights, ObjectiveWeightsRequest } from '../api/scheduling';
import { Busy, Failure } from '../components/Feedback';
import { useAsync } from '../components/useAsync';
import { useSessionKey } from '../auth/SessionProvider';
import { OBJECTIVE_FIELDS, type ObjectiveField } from '../components/objectiveFields';

function stored(weights: ObjectiveWeights | null, key: ObjectiveField): string {
  const value = weights === null ? null : weights[key];
  return value === null || value === undefined ? '' : String(value);
}

/** Each objective and its stored weight, as one phrase a status can say. */
function described(weights: ObjectiveWeights): string {
  return OBJECTIVE_FIELDS.map(([key, name]) => {
    const weight = weights[key];
    return weight === null ? `${name} not weighed` : `${name} ${String(weight)}`;
  }).join(', ');
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
  const [done, setDone] = useState<string | null>(null);
  // A second revision sent meanwhile would be one nobody asked for.
  const [weighing, setWeighing] = useState(false);
  const sessionKey = useSessionKey();
  const waitingId = useId();
  const heading = useRef<HTMLHeadingElement>(null);

  const current = useAsync(() => scheduling.findObjectives(), sessionKey);
  const loaded = current.value;

  const shown = (key: ObjectiveField): string => edits[key] ?? stored(loaded, key);

  async function revise(event: React.FormEvent) {
    event.preventDefault();
    // Unlike the rules, weights are not versioned: a revision sent before the stored weights are
    // known would unweigh them for good, so there is nothing to send until they are.
    if (loaded === null || weighing) {
      return;
    }
    setFailure(null);
    setDone(null);
    const weight = (key: ObjectiveField): number | undefined => {
      const value = shown(key).trim();
      return value === '' ? undefined : Number(value);
    };
    const body: ObjectiveWeightsRequest = {
      fairness: weight('fairness'),
      preferences: weight('preferences'),
      disruption: weight('disruption'),
    };
    setWeighing(true);
    try {
      const weighed = await scheduling.reviseObjectives(body);
      setEdits({});
      // The fields look the same before and after, so without a word nothing says it was stored.
      setDone(`Objectives weighed: ${described(weighed)}.`);
      current.reload();
    } catch (error) {
      setFailure(error);
    } finally {
      setWeighing(false);
    }
  }

  return (
    <section>
      <h2 ref={heading} tabIndex={-1}>
        What makes a roster better
      </h2>
      <p className="hint">
        Each objective is a count, and lower is better. A weight says how much it matters against
        the others; blank means it is not weighed. A draft&apos;s check shows each count and the
        weighted total. Weights never make a roster legal or illegal. Generation fills every place
        it can first, then keeps the weighted total as low as it can.
      </p>

      {/* Change events bubble, so any edit clears a success that would read as the next one's. */}
      <form onSubmit={revise} onChange={() => setDone(null)} className="grid">
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
        {/* aria-disabled rather than disabled, so the button keeps focus and can say why. */}
        <button
          type="submit"
          aria-disabled={loaded === null || weighing}
          aria-describedby={loaded === null ? waitingId : undefined}
        >
          Weigh objectives
        </button>
      </form>
      {loaded === null ? (
        <p id={waitingId} className="hint">
          {current.error === null
            ? 'Weighing waits until the stored weights have loaded.'
            : 'The stored weights did not load, so there is nothing to weigh from yet.'}
        </p>
      ) : null}
      {current.error === null ? null : (
        <button
          type="button"
          onClick={() => {
            // The button goes with the failure it answers, so focus moves on before it does.
            heading.current?.focus();
            current.reload();
          }}
        >
          Try again
        </button>
      )}

      <Failure error={failure} />
      <p className="done" role="status">
        {done}
      </p>
      <Failure error={current.error} />
      <Busy busy={current.busy} />
    </section>
  );
}
