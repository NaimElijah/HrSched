import { useState } from 'react';
import { scheduling } from '../api/scheduling';
import type { ConstraintSet, ConstraintSetRequest } from '../api/scheduling';
import { Busy, Failure } from '../components/Feedback';
import { useAsync } from '../components/useAsync';
import { useSessionKey } from '../auth/SessionProvider';
import { RULE_FIELDS, type RuleField } from '../components/ruleFields';

function configured(set: ConstraintSet | null, key: RuleField): string {
  const value = set === null ? null : set[key];
  return value === null || value === undefined ? '' : String(value);
}

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
export function RulesPage() {
  // Only what the admin has actually typed. Everything else is derived from the loaded rules, so
  // no effect syncs two copies of the same state — and an empty string is a real edit meaning
  // "switch this rule off", which is why it is distinct from an absent key.
  const [edits, setEdits] = useState<Partial<Record<RuleField, string>>>({});
  const [failure, setFailure] = useState<unknown>(null);
  const sessionKey = useSessionKey();

  const current = useAsync(() => scheduling.currentConstraints(), sessionKey);
  const loaded = current.value;

  const shown = (key: RuleField): string => edits[key] ?? configured(loaded, key);

  async function revise(event: React.FormEvent) {
    event.preventDefault();
    setFailure(null);
    const number = (key: RuleField): number | undefined => {
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
      await scheduling.reviseConstraints(body);
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
        {RULE_FIELDS.map(([key, label]) => (
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
