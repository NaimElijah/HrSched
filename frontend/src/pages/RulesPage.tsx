import { useState } from 'react';
import { scheduling } from '../api/scheduling';
import type { ConstraintSet, ConstraintSetRequest } from '../api/scheduling';
import { identityKey } from '../api/client';
import { Busy, Failure } from '../components/Feedback';
import { useAsync } from '../components/useAsync';
import { useActingAs } from '../identity/ActingAs';

/**
 * Admin: this organisation's working-time rules.
 *
 * Every field is blank unless the organisation has configured that rule, and no value is ever
 * suggested. HrSched hardcodes no jurisdiction, and a number offered by this form would be a legal
 * threshold chosen by a developer. A jurisdiction is a preset somebody loads, with its source cited.
 *
 * Each field shows the configured value until it is edited, and that is load-bearing rather than a
 * convenience: revising replaces the whole set, so submitting a form that had only one field filled
 * in would delete every other rule. Clearing a field is how a rule is switched off, and it must be
 * the only way.
 */
const FIELDS = [
  ['minimumRestMinutes', 'Minimum rest between assignments (minutes)'],
  ['maximumDailyMinutes', 'Maximum daily working time (minutes)'],
  ['maximumWeeklyMinutes', 'Maximum working time over the roster period (minutes)'],
  ['maximumConsecutiveDays', 'Maximum consecutive working days'],
] as const;

type Field = (typeof FIELDS)[number][0];

function configured(set: ConstraintSet | null, key: Field): string {
  const value = set === null ? null : set[key];
  return value === null || value === undefined ? '' : String(value);
}

export function RulesPage() {
  // Only what the admin has actually typed. Everything else is derived from the loaded rules, so
  // no effect syncs two copies of the same state — and an empty string is a real edit meaning
  // "switch this rule off", which is why it is distinct from an absent key.
  const [edits, setEdits] = useState<Partial<Record<Field, string>>>({});
  const [failure, setFailure] = useState<unknown>(null);
  const { identity } = useActingAs();

  const current = useAsync(() => scheduling.currentConstraints(identity), identityKey(identity));
  const loaded = current.value;

  const shown = (key: Field): string => edits[key] ?? configured(loaded, key);

  async function revise(event: React.FormEvent) {
    event.preventDefault();
    setFailure(null);
    const number = (key: Field): number | undefined => {
      const value = shown(key).trim();
      return value === '' ? undefined : Number(value);
    };
    const body: ConstraintSetRequest = {
      minimumRestMinutes: number('minimumRestMinutes'),
      maximumDailyMinutes: number('maximumDailyMinutes'),
      maximumWeeklyMinutes: number('maximumWeeklyMinutes'),
      maximumConsecutiveDays: number('maximumConsecutiveDays'),
    };
    try {
      await scheduling.reviseConstraints(identity, body);
      setEdits({});
      current.reload();
    } catch (error) {
      setFailure(error);
    }
  }

  return (
    <section>
      <h2>Working-time rules</h2>
      <p className="hint">
        Blank means a rule is not enforced, and clearing a field is how you switch one off. No value
        is suggested here on purpose — HrSched encodes no country&apos;s law, and every threshold is
        configuration.
      </p>

      <p>
        Current version: <strong>{loaded?.version ?? '—'}</strong>
      </p>
      <ul>
        {(loaded?.constraints ?? []).map((constraint) => (
          <li key={constraint}>{constraint}</li>
        ))}
      </ul>

      <form onSubmit={revise} className="grid">
        {FIELDS.map(([key, label]) => (
          <label key={key}>
            {label}
            <input
              type="number"
              min="1"
              value={shown(key)}
              placeholder="not enforced"
              onChange={(event) => setEdits({ ...edits, [key]: event.target.value })}
            />
          </label>
        ))}
        <button type="submit">Revise rules</button>
      </form>

      <Failure error={failure} />
      <Failure error={current.error} />
      <Busy busy={current.busy} />
    </section>
  );
}
