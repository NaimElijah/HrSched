import { useState } from 'react';
import { scheduling } from '../api/scheduling';
import type { ConstraintSetRequest } from '../api/scheduling';
import { Busy, Failure } from '../components/Feedback';
import { useAsync } from '../components/useAsync';
import { identityKey } from '../api/client';
import { useActingAs } from '../identity/ActingAs';

/**
 * Admin: this organisation's working-time rules.
 *
 * Every field is blank by default and stays blank unless somebody fills it in. There is no
 * suggested value anywhere on this page and there must never be one: HrSched hardcodes no
 * jurisdiction, and a number offered by the UI would be a legal threshold chosen by a developer.
 * A jurisdiction is a preset somebody loads, with its source cited.
 */
export function RulesPage() {
  const { identity } = useActingAs();
  const [form, setForm] = useState<Record<keyof ConstraintSetRequest, string>>({
    minimumRestMinutes: '',
    maximumDailyMinutes: '',
    maximumWeeklyMinutes: '',
    maximumConsecutiveDays: '',
  });
  const [failure, setFailure] = useState<unknown>(null);

  const current = useAsync(() => scheduling.currentConstraints(identity), identityKey(identity));

  function field(key: keyof ConstraintSetRequest, label: string) {
    return (
      <label>
        {label}
        <input
          type="number"
          min="1"
          value={form[key]}
          placeholder="not enforced"
          onChange={(event) => setForm({ ...form, [key]: event.target.value })}
        />
      </label>
    );
  }

  async function revise(event: React.FormEvent) {
    event.preventDefault();
    setFailure(null);
    const request: ConstraintSetRequest = {};
    const numeric = (value: string): number | undefined =>
      value.trim() === '' ? undefined : Number(value);
    const body: Record<string, number | undefined> = {
      minimumRestMinutes: numeric(form.minimumRestMinutes),
      maximumDailyMinutes: numeric(form.maximumDailyMinutes),
      maximumWeeklyMinutes: numeric(form.maximumWeeklyMinutes),
      maximumConsecutiveDays: numeric(form.maximumConsecutiveDays),
    };
    try {
      await scheduling.reviseConstraints(identity, { ...request, ...body });
      current.reload();
    } catch (error) {
      setFailure(error);
    }
  }

  return (
    <section>
      <h2>Working-time rules</h2>
      <p className="hint">
        Blank means a rule is not enforced. No value is suggested here on purpose — HrSched encodes
        no country&apos;s law, and every threshold is configuration.
      </p>

      <p>
        Current version: <strong>{current.value?.version ?? '—'}</strong>
      </p>
      <ul>
        {(current.value?.constraints ?? []).map((constraint) => (
          <li key={constraint}>{constraint}</li>
        ))}
      </ul>

      <form onSubmit={revise} className="grid">
        {field('minimumRestMinutes', 'Minimum rest between assignments (minutes)')}
        {field('maximumDailyMinutes', 'Maximum daily working time (minutes)')}
        {field('maximumWeeklyMinutes', 'Maximum working time over the roster period (minutes)')}
        {field('maximumConsecutiveDays', 'Maximum consecutive working days')}
        <button type="submit">Revise rules</button>
      </form>

      <Failure error={failure} />
      <Failure error={current.error} />
      <Busy busy={current.busy} />
    </section>
  );
}
