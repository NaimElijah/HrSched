import { useState } from 'react';
import { scheduling } from '../api/scheduling';
import type { RosteredShift } from '../api/scheduling';
import { workforce } from '../api/workforce';
import type { Employee, PreferenceStrength } from '../api/workforce';
import { Busy, Failure } from '../components/Feedback';
import { shiftLabel, spanLabel } from '../components/localDates';
import { useAsync } from '../components/useAsync';
import { useSession, useSessionKey } from '../auth/SessionProvider';
import { Inbox } from './Inbox';

/**
 * Employee self-service: who I am, the work that names me, and when I cannot work.
 *
 * Unavailability is hard — nobody is rostered during it. A preference is soft and never blocks a
 * roster; today no generator weighs preferences, because the objectives it would optimise are not
 * modelled (ADR-010). This page says so rather than implying the preference will be honoured.
 *
 * What they declared is read back from the backend rather than remembered from the form (F6), so
 * the list shows what was stored, including anything an Admin or Scheduler declared for them. Each
 * entry can be withdrawn (F26) by sending back exactly what was read, since a declaration has no
 * identifier of its own (ADR-040).
 *
 * Own work comes from published rosters only (F5). Handing a shift to a colleague names that
 * colleague by their employee identifier, because an employee may not list colleagues and so has no
 * names to choose from (Q25). Asking decides nothing: a Scheduler answers (ADR-022).
 *
 * Acting on your own record requires the signed-in account to carry an employee link (ADR-012).
 * An Admin sets that link when they create the account; an account without one can sign in and do
 * its job, but has no record of its own to show.
 */
export function MePage({
  span,
  people,
}: {
  span: { from: string; to: string };
  /** Colleagues the acting role may list; empty for an employee, whose inbox then shows identifiers. */
  people: readonly Employee[];
}) {
  const { session } = useSession();
  const sessionKey = useSessionKey();
  const personById = new Map(people.map((person) => [person.id, person]));
  const nameOf = (colleague: string): string => personById.get(colleague)?.name ?? colleague;
  const [start, setStart] = useState('');
  const [end, setEnd] = useState('');
  const [strength, setStrength] = useState<PreferenceStrength>('PREFERS');
  const [failure, setFailure] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);
  const [withdrawing, setWithdrawing] = useState(false);

  // The employee link comes from the session the backend established, never from anything typed:
  // a user is not an employee, and the two are linked by the account rather than by matching ids.
  const employee = session?.employee ?? null;

  // Without an employee link there is nobody for "my" schedule to be about, and asking would only
  // earn a refusal the page already explains.
  const schedule = useAsync(
    () =>
      employee === null ? Promise.resolve([]) : scheduling.findOwnSchedule(span.from, span.to),
    `${sessionKey}|${span.from}|${span.to}`,
  );
  const unavailability = useAsync(
    () => (employee === null ? Promise.resolve([]) : workforce.findUnavailability(employee)),
    `${sessionKey}|${employee ?? ''}`,
  );
  const preferences = useAsync(
    () => (employee === null ? Promise.resolve([]) : workforce.findPreferences(employee)),
    `${sessionKey}|${employee ?? ''}`,
  );

  /**
   * Both availability actions share one period, so it is validated once, here.
   *
   * The `required` attributes on the inputs do nothing on their own: neither control submits the
   * form, so the browser never runs constraint validation and an empty field would reach
   * `new Date('')` and surface as a bare "Invalid time value".
   */
  function enteredPeriod(): { start: string; end: string } | null {
    const from = new Date(start);
    const to = new Date(end);
    if (Number.isNaN(from.getTime()) || Number.isNaN(to.getTime())) {
      setDone(null);
      setFailure(new Error('Give both a start and an end.'));
      return null;
    }
    if (to <= from) {
      setDone(null);
      setFailure(new Error('The end has to come after the start.'));
      return null;
    }
    return { start: from.toISOString(), end: to.toISOString() };
  }

  /** @param declared the list the action adds to, read again once the backend has stored it */
  async function act(
    action: (when: { start: string; end: string }) => Promise<unknown>,
    message: string,
    declared: { reload: () => void },
  ) {
    const when = enteredPeriod();
    if (when === null) {
      return;
    }
    setFailure(null);
    setDone(null);
    try {
      await action(when);
      setDone(message);
      declared.reload();
    } catch (error) {
      setFailure(error);
    }
  }

  /**
   * Withdrawals take turns: a second one sent while the first is on its way could name what the
   * first has just removed, and be refused for it.
   *
   * @param declared the list the withdrawal removes from, read again once the backend has removed it
   */
  async function withdraw(
    action: () => Promise<unknown>,
    message: string,
    declared: { reload: () => void },
  ) {
    if (withdrawing) {
      return;
    }
    setWithdrawing(true);
    setFailure(null);
    setDone(null);
    try {
      await action();
      setDone(message);
      declared.reload();
    } catch (error) {
      setFailure(error);
    } finally {
      setWithdrawing(false);
    }
  }

  /** @returns whether the request was stored, so the form clears only when it was */
  async function handOver(
    requester: string,
    work: RosteredShift,
    colleague: string,
  ): Promise<boolean> {
    setDone(null);
    if (colleague.trim() === '') {
      setFailure(new Error('Give the identifier of the colleague who would take it.'));
      return false;
    }
    setFailure(null);
    try {
      await scheduling.requestSwap({
        roster: work.roster,
        shift: work.shift,
        requester,
        counterparty: colleague.trim(),
      });
      setDone('Asked. A Scheduler decides, and your colleague has been told you asked.');
      return true;
    } catch (error) {
      setFailure(error);
      return false;
    }
  }

  if (employee === null) {
    return (
      <section>
        <h2>Me</h2>
        <p className="hint">
          Your account is not linked to a personnel record, so there is nothing here that is yours.
          An account and an employee are different identities on purpose, so the link has to be
          stated rather than guessed — ask an Admin to set it.
        </p>
      </section>
    );
  }

  return (
    <section>
      <h2>Me</h2>
      <p className="hint">
        You are employee <code>{employee}</code>. A colleague handing you a shift needs this
        identifier: an employee cannot look colleagues up, because that listing says when they
        cannot work.
      </p>

      <Failure error={failure} />
      {/* Always in the page, so an outcome is announced when it arrives rather than skipped. */}
      <p className="done" role="status">
        {done}
      </p>

      <h3>My work</h3>
      <p className="hint">
        Published rosters only, over the planning span above. A draft binds nobody until it is
        published.
      </p>
      <Failure error={schedule.error} />
      <Busy busy={schedule.busy} />
      {schedule.value?.length === 0 ? <p>Nothing published names you in this span.</p> : null}
      <ul className="work">
        {(schedule.value ?? []).map((work) => (
          <OwnShift
            key={`${work.roster}|${work.shift}`}
            work={work}
            onHandOver={(colleague) => handOver(employee, work, colleague)}
          />
        ))}
      </ul>

      <Inbox employee={employee} nameOf={nameOf} />

      <h3>When I cannot work</h3>
      <p className="hint">
        Unavailability is hard: nobody is rostered during it. A preference is soft and never blocks
        a roster — and nothing optimises for it yet, because objectives are not modelled.
      </p>

      <form className="grid">
        <label>
          From
          <input
            type="datetime-local"
            value={start}
            onChange={(event) => setStart(event.target.value)}
          />
        </label>
        <label>
          To
          <input
            type="datetime-local"
            value={end}
            onChange={(event) => setEnd(event.target.value)}
          />
        </label>
        <label>
          Preference
          <select
            value={strength}
            onChange={(event) => setStrength(event.target.value as PreferenceStrength)}
          >
            <option value="PREFERS">Would like to work this</option>
            <option value="PREFERS_NOT">Would rather not</option>
          </select>
        </label>
        <button
          type="button"
          onClick={() =>
            void act(
              (when) => workforce.declareUnavailability(employee, when.start, when.end),
              'Recorded: you cannot work then.',
              unavailability,
            )
          }
        >
          I cannot work this
        </button>
        <button
          type="button"
          onClick={() =>
            void act(
              (when) => workforce.statePreference(employee, when.start, when.end, strength),
              'Recorded: a preference, which never blocks a roster.',
              preferences,
            )
          }
        >
          State a preference
        </button>
      </form>

      <h3>What I declared</h3>
      <h4>Times I cannot work</h4>
      <Failure error={unavailability.error} />
      <Busy busy={unavailability.busy} />
      {unavailability.value?.length === 0 ? (
        <p>You have declared no time you cannot work.</p>
      ) : null}
      <ul className="spans">
        {(unavailability.value ?? []).map((period) => {
          const label = spanLabel(period.start, period.end);
          return (
            // Unique: two declared absences may not overlap (R7), so no two share a span.
            <li key={`${period.start}|${period.end}`}>
              <span>{label}</span>{' '}
              <button
                type="button"
                disabled={withdrawing}
                aria-label={`Withdraw absence: ${label}`}
                onClick={() =>
                  void withdraw(
                    () => workforce.withdrawUnavailability(employee, period.start, period.end),
                    'Withdrawn: that absence no longer keeps you off a roster.',
                    unavailability,
                  )
                }
              >
                Withdraw
              </button>
            </li>
          );
        })}
      </ul>

      <h4>Preferences</h4>
      <Failure error={preferences.error} />
      <Busy busy={preferences.busy} />
      {preferences.value?.length === 0 ? <p>You have stated no preferences.</p> : null}
      <ul className="spans">
        {(preferences.value ?? []).map((preference, index) => {
          const label = `${spanLabel(preference.start, preference.end)}: ${FEELING[preference.strength]}`;
          return (
            // Indexed: preferences may repeat one another exactly (R26), so a span alone is no key.
            <li key={`${index}|${preference.start}|${preference.end}|${preference.strength}`}>
              <span>{label}</span>{' '}
              <button
                type="button"
                disabled={withdrawing}
                aria-label={`Withdraw preference: ${label}`}
                onClick={() =>
                  void withdraw(
                    () =>
                      workforce.withdrawPreference(
                        employee,
                        preference.start,
                        preference.end,
                        preference.strength,
                      ),
                    'Withdrawn: that preference no longer stands.',
                    preferences,
                  )
                }
              >
                Withdraw
              </button>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

const FEELING: Readonly<Record<PreferenceStrength, string>> = {
  PREFERS: 'would like to work this',
  PREFERS_NOT: 'would rather not work this',
};

function OwnShift({
  work,
  onHandOver,
}: {
  work: RosteredShift;
  onHandOver: (colleague: string) => Promise<boolean>;
}) {
  const [colleague, setColleague] = useState('');
  // Each request is stored, so a second one sent while the first is on its way would be a duplicate.
  const [asking, setAsking] = useState(false);
  const when = shiftLabel(work.start, work.end);

  return (
    <li>
      <p>
        <strong>{when}</strong>
      </p>
      <form
        className="row"
        onSubmit={(event) => {
          event.preventDefault();
          if (asking) {
            return;
          }
          setAsking(true);
          void onHandOver(colleague).then((asked) => {
            setAsking(false);
            if (asked) {
              setColleague('');
            }
          });
        }}
      >
        {/* Each name starts with the visible label and then says which shift, since one repeats per shift. */}
        <label>
          Colleague&apos;s employee identifier
          <input
            value={colleague}
            onChange={(event) => setColleague(event.target.value)}
            spellCheck={false}
            aria-label={`Colleague's employee identifier, to take ${when}`}
          />
        </label>
        <button type="submit" disabled={asking} aria-label={`Ask to hand it over: ${when}`}>
          Ask to hand it over
        </button>
      </form>
    </li>
  );
}
