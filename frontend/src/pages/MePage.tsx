import { useId, useRef, useState, type RefObject } from 'react';
import { scheduling } from '../api/scheduling';
import type { RosteredShift } from '../api/scheduling';
import { workforce } from '../api/workforce';
import type { Employee, PreferenceStrength } from '../api/workforce';
import { Busy, Failure } from '../components/Feedback';
import { shiftLabel, spanLabel } from '../components/localDates';
import { useAsync } from '../components/useAsync';
import { useSession, useSessionKey } from '../auth/SessionProvider';
import { Inbox } from './Inbox';

/** A field of the period both availability actions share. */
type PeriodField = 'from' | 'to';

/** Why the period entered cannot be sent, and which of its fields that is about. */
interface PeriodProblem {
  readonly message: string;
  readonly fields: readonly PeriodField[];
}

/** The action a success status is about, so that editing its form again clears it (C9). */
type Outcome = 'period' | 'withdrawal' | 'handOver';

/** A success to announce, and the action it came from. */
interface Done {
  readonly text: string;
  readonly of: Outcome;
}

/**
 * Whether focus can be moved without taking it from anybody: it is still on the control that
 * acted, or it was dropped to the body when that control went.
 */
function focusIsUnclaimed(control: Element | null): boolean {
  const active = document.activeElement;
  return active === null || active === document.body || active === control;
}

/**
 * Employee self-service: who I am, the work that names me, and when I cannot work.
 *
 * Unavailability is hard — nobody is rostered during it. A preference is soft and never blocks a
 * roster. A draft's check counts the preferences it goes against (ADR-010), and generation honours
 * them where it can, after filling every place it can (ADR-044). This page says so rather than
 * implying the preference will be honoured.
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
 *
 * Whatever refuses an action is shown where the action was taken: the period's own checks and the
 * backend's refusal of it in the "When I cannot work" section, a refused hand-over in its shift, and
 * a refused withdrawal in its entry.
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
  const [periodProblem, setPeriodProblem] = useState<PeriodProblem | null>(null);
  // The backend's refusal of declaring or stating, shown beside the form that asked.
  const [declarationRefused, setDeclarationRefused] = useState<unknown>(null);
  // The backend's refusal of a withdrawal, shown in the entry it was about.
  const [withdrawalRefused, setWithdrawalRefused] = useState<{
    entry: string;
    error: unknown;
  } | null>(null);
  const [done, setDone] = useState<Done | null>(null);
  const [writing, setWriting] = useState(false);
  const periodMessage = useId();
  const absencesHeading = useRef<HTMLHeadingElement>(null);
  const preferencesHeading = useRef<HTMLHeadingElement>(null);

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

  /** A success stays only until its form is edited again, when it no longer describes the form. */
  const editedAgain = (outcome: Outcome) =>
    setDone((previous) => (previous?.of === outcome ? null : previous));

  const isInvalid = (field: PeriodField): boolean => periodProblem?.fields.includes(field) ?? false;

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
    const missing: PeriodField[] = [];
    if (Number.isNaN(from.getTime())) {
      missing.push('from');
    }
    if (Number.isNaN(to.getTime())) {
      missing.push('to');
    }
    if (missing.length > 0) {
      setDone(null);
      setPeriodProblem({ message: 'Give both a start and an end.', fields: missing });
      return null;
    }
    if (to <= from) {
      setDone(null);
      setPeriodProblem({ message: 'The end has to come after the start.', fields: ['to'] });
      return null;
    }
    setPeriodProblem(null);
    return { start: from.toISOString(), end: to.toISOString() };
  }

  /** @param declared the list the action adds to, read again once the backend has stored it */
  async function act(
    action: (when: { start: string; end: string }) => Promise<unknown>,
    message: string,
    declared: { reload: () => void },
  ) {
    if (writing) {
      return;
    }
    setDeclarationRefused(null);
    const when = enteredPeriod();
    if (when === null) {
      return;
    }
    await write(
      () => action(when),
      { text: message, of: 'period' },
      declared,
      setDeclarationRefused,
    );
  }

  /**
   * Declaring, stating and withdrawing take turns. The backend applies two changes to one person
   * one after the other (ADR-041), but this page re-reads the list after each answer: with two in
   * flight, the list read after the first could be one the second is still changing, shown under a
   * confirmation that no longer describes it.
   *
   * The list is read again whatever the answer: a refusal can mean the list on screen is stale, an
   * entry withdrawn already by an Admin on the person's behalf or in another tab.
   *
   * @param stored what to announce once it is stored
   * @param declared the list the write changes, read again once the backend has answered
   * @param refused shows a refusal where the action was taken
   * @returns whether the write was stored
   */
  async function write(
    action: () => Promise<unknown>,
    stored: Done,
    declared: { reload: () => void },
    refused: (error: unknown) => void,
  ): Promise<boolean> {
    if (writing) {
      return false;
    }
    setWriting(true);
    setDeclarationRefused(null);
    setWithdrawalRefused(null);
    setDone(null);
    try {
      await action();
      setDone(stored);
      return true;
    } catch (error) {
      refused(error);
      return false;
    } finally {
      setWriting(false);
      declared.reload();
    }
  }

  /**
   * Withdraws one entry. The entry leaves its list once the list is read again, and its button with
   * it, so focus moves to the list's heading.
   *
   * @param entry names the entry, so a refusal is shown in it
   * @param control the Withdraw pressed
   * @param declared the list the entry is in, whose entries wait while it is read again
   * @param heading that list's heading
   */
  async function withdraw(
    entry: string,
    control: HTMLElement,
    action: () => Promise<unknown>,
    message: string,
    declared: { reload: () => void; busy: boolean },
    heading: RefObject<HTMLHeadingElement | null>,
  ) {
    if (writing || declared.busy) {
      return;
    }
    const withdrawn = await write(action, { text: message, of: 'withdrawal' }, declared, (error) =>
      setWithdrawalRefused({ entry, error }),
    );
    if (withdrawn && focusIsUnclaimed(control)) {
      heading.current?.focus();
    }
  }

  /** @throws whatever the backend refused the request with, for the shift to show */
  async function handOver(requester: string, work: RosteredShift, colleague: string) {
    setDone(null);
    await scheduling.requestSwap({
      roster: work.roster,
      shift: work.shift,
      requester,
      counterparty: colleague,
    });
    setDone({
      text: 'Asked. A Scheduler decides, and your colleague has been told you asked.',
      of: 'handOver',
    });
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

      {/* Always in the page, so an outcome is announced when it arrives rather than skipped. */}
      <p className="done" role="status">
        {done?.text}
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
            onEdit={() => editedAgain('handOver')}
          />
        ))}
      </ul>

      <Inbox employee={employee} nameOf={nameOf} />

      <h3>When I cannot work</h3>
      <p className="hint">
        Unavailability is hard: nobody is rostered during it. A preference is soft and never blocks
        a roster. Generation honours it where it can, if your organisation weighs preferences, but
        filling every shift comes first.
      </p>

      <form className="grid">
        <label>
          From
          <input
            type="datetime-local"
            value={start}
            aria-invalid={isInvalid('from')}
            aria-describedby={isInvalid('from') ? periodMessage : undefined}
            onChange={(event) => {
              setStart(event.target.value);
              setPeriodProblem(null);
              setDeclarationRefused(null);
              editedAgain('period');
            }}
          />
        </label>
        <label>
          To
          <input
            type="datetime-local"
            value={end}
            aria-invalid={isInvalid('to')}
            aria-describedby={isInvalid('to') ? periodMessage : undefined}
            onChange={(event) => {
              setEnd(event.target.value);
              setPeriodProblem(null);
              setDeclarationRefused(null);
              editedAgain('period');
            }}
          />
        </label>
        <label>
          Preference
          <select
            value={strength}
            onChange={(event) => {
              setStrength(event.target.value as PreferenceStrength);
              setDeclarationRefused(null);
              editedAgain('period');
            }}
          >
            <option value="PREFERS">Would like to work this</option>
            <option value="PREFERS_NOT">Would rather not</option>
          </select>
        </label>
        {/* aria-disabled rather than disabled, so focus stays on the button while a write is out. */}
        <button
          type="button"
          aria-disabled={writing}
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
          aria-disabled={writing}
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
      {periodProblem === null ? null : (
        <p id={periodMessage} className="failure" role="alert">
          {periodProblem.message}
        </p>
      )}
      <Failure error={declarationRefused} />

      <h3>What I declared</h3>
      <h4 ref={absencesHeading} tabIndex={-1}>
        Times I cannot work
      </h4>
      <Failure error={unavailability.error} />
      <Busy busy={unavailability.busy} />
      {unavailability.value?.length === 0 ? (
        <p>You have declared no time you cannot work.</p>
      ) : null}
      <ul className="spans">
        {(unavailability.value ?? []).map((period) => {
          const label = spanLabel(period.start, period.end);
          // Unique: two declared absences may not overlap (R7), so no two share a span.
          const entry = `absence|${period.start}|${period.end}`;
          return (
            <li key={entry}>
              <span>{label}</span>{' '}
              <button
                type="button"
                aria-disabled={writing || unavailability.busy}
                aria-label={`Withdraw absence: ${label}`}
                onClick={(event) =>
                  void withdraw(
                    entry,
                    event.currentTarget,
                    () => workforce.withdrawUnavailability(employee, period.start, period.end),
                    'Withdrawn: that absence no longer keeps you off a roster.',
                    unavailability,
                    absencesHeading,
                  )
                }
              >
                Withdraw
              </button>
              {withdrawalRefused?.entry === entry ? (
                <Failure error={withdrawalRefused.error} />
              ) : null}
            </li>
          );
        })}
      </ul>

      <h4 ref={preferencesHeading} tabIndex={-1}>
        Preferences
      </h4>
      <Failure error={preferences.error} />
      <Busy busy={preferences.busy} />
      {preferences.value?.length === 0 ? <p>You have stated no preferences.</p> : null}
      <ul className="spans">
        {(preferences.value ?? []).map((preference, index) => {
          const label = `${spanLabel(preference.start, preference.end)}: ${FEELING[preference.strength]}`;
          // Indexed: preferences may repeat one another exactly (R26), so a span alone is no key.
          const entry = `preference|${index}|${preference.start}|${preference.end}|${preference.strength}`;
          return (
            <li key={entry}>
              <span>{label}</span>{' '}
              <button
                type="button"
                aria-disabled={writing || preferences.busy}
                aria-label={`Withdraw preference: ${label}`}
                onClick={(event) =>
                  void withdraw(
                    entry,
                    event.currentTarget,
                    () =>
                      workforce.withdrawPreference(
                        employee,
                        preference.start,
                        preference.end,
                        preference.strength,
                      ),
                    'Withdrawn: that preference no longer stands.',
                    preferences,
                    preferencesHeading,
                  )
                }
              >
                Withdraw
              </button>
              {withdrawalRefused?.entry === entry ? (
                <Failure error={withdrawalRefused.error} />
              ) : null}
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

const NO_COLLEAGUE = 'Give the identifier of the colleague who would take it.';

function OwnShift({
  work,
  onHandOver,
  onEdit,
}: {
  work: RosteredShift;
  /** Asks for the shift to go to this colleague; rejects with the backend's refusal. */
  onHandOver: (colleague: string) => Promise<void>;
  /** The identifier is being edited again, which starts the next request. */
  onEdit: () => void;
}) {
  const [colleague, setColleague] = useState('');
  // Each request is stored, so a second one sent while the first is on its way would be a duplicate.
  const [asking, setAsking] = useState(false);
  const [blank, setBlank] = useState(false);
  const [refused, setRefused] = useState<unknown>(null);
  const message = useId();
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
          setRefused(null);
          const counterparty = colleague.trim();
          if (counterparty === '') {
            setBlank(true);
            return;
          }
          setBlank(false);
          setAsking(true);
          void onHandOver(counterparty)
            .then(
              () => setColleague(''),
              (error: unknown) => setRefused(error),
            )
            .finally(() => setAsking(false));
        }}
      >
        {/* Each name starts with the visible label and then says which shift, since one repeats per shift. */}
        <label>
          Colleague&apos;s employee identifier
          <input
            value={colleague}
            onChange={(event) => {
              setColleague(event.target.value);
              setBlank(false);
              setRefused(null);
              onEdit();
            }}
            spellCheck={false}
            aria-label={`Colleague's employee identifier, to take ${when}`}
            aria-invalid={blank}
            aria-describedby={blank ? message : undefined}
          />
        </label>
        {/* aria-disabled rather than disabled, so focus stays on the button while asking. */}
        <button type="submit" aria-disabled={asking} aria-label={`Ask to hand it over: ${when}`}>
          Ask to hand it over
        </button>
      </form>
      {blank ? (
        <p id={message} className="failure" role="alert">
          {NO_COLLEAGUE}
        </p>
      ) : null}
      <Failure error={refused} />
    </li>
  );
}
