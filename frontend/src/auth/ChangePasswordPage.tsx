import { useEffect, useId, useRef, useState } from 'react';
import type { ChangeEvent } from 'react';
import { ApiError } from '../api/problem';
import { useSession } from './SessionProvider';

/**
 * What an accepted change says. Exported because, from the forced change, this screen goes the
 * moment the change is accepted, and the shell says it instead.
 */
export const PASSWORD_CHANGED = 'Your password has been changed.';

const MISMATCH = 'The two new passwords do not match.';

/** What the status region says while a change is on its way. */
const CHANGING = 'Changing your password…';

/**
 * Why the last submission did not change the password, and which field it is about: the
 * confirmation for a mismatch, the new password for a backend refusal of it (422), the current
 * password for one the backend did not accept (403), and none for anything else.
 */
interface Refusal {
  readonly at: 'current' | 'replacement' | 'confirmation' | null;
  readonly message: string;
}

/**
 * Changing one's own password, and the only screen reachable while a temporary one is pending.
 *
 * **No length or shape is suggested here, and no field carries a default.** What a password must
 * satisfy is the backend's rule, and it says so in its own words when it refuses; repeating it in
 * the UI would be a second copy that drifts, and pre-filling anything would be this application
 * choosing a password for somebody.
 *
 * The confirmation field is checked here rather than sent, because two boxes disagreeing is a typo
 * rather than a decision the backend has any view about.
 *
 * A refusal about one field is shown beside it, marks it invalid and describes it (#257, C6). It
 * stays until the next send, because the backend's refusal is the only place its rule is stated,
 * and somebody writing a password that meets it may need to read it again. A success goes at the
 * first edit, because it is about what was sent, not what is being typed (C9).
 *
 * A mismatch moves focus to the confirmation, which it describes, so it is said by the field and
 * raises no alert; every other refusal leaves focus where it was and is an alert.
 */
export function ChangePasswordPage() {
  const { state, session, changePassword, signOut } = useSession();
  const [current, setCurrent] = useState('');
  const [replacement, setReplacement] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [refusal, setRefusal] = useState<Refusal | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // From the Password tab an accepted change alters nothing else on screen, so it is said here.
  const [done, setDone] = useState<string | null>(null);
  const confirmationInput = useRef<HTMLInputElement>(null);
  const currentRefusal = useId();
  const replacementRefusal = useId();
  const confirmationRefusal = useId();

  const required = state === 'password-change-required';

  // After the render that marks it invalid, so the field is announced with its description.
  useEffect(() => {
    if (refusal?.at === 'confirmation') {
      confirmationInput.current?.focus();
    }
  }, [refusal]);

  function editing(set: (value: string) => void) {
    return (event: ChangeEvent<HTMLInputElement>) => {
      set(event.target.value);
      setDone(null);
    };
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (submitting) {
      return;
    }
    setRefusal(null);
    setDone(null);
    if (replacement !== confirmation) {
      setRefusal({ at: 'confirmation', message: MISMATCH });
      return;
    }
    setSubmitting(true);
    try {
      await changePassword(current, replacement);
      setCurrent('');
      setReplacement('');
      setConfirmation('');
      setDone(PASSWORD_CHANGED);
    } catch (error) {
      setRefusal(
        error instanceof ApiError
          ? { at: refusedAt(error), message: error.message }
          : { at: null, message: String(error) },
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section className="change-password">
      <h2>Change your password</h2>
      {required ? (
        <p className="hint">
          Your password was set by somebody else. Change it before doing anything else.
        </p>
      ) : null}
      <form onSubmit={submit}>
        {/* Never shown: it tells a password manager which account the new password belongs to. */}
        <input
          type="text"
          value={session?.signInName ?? ''}
          autoComplete="username"
          readOnly
          hidden
        />
        <label>
          Current password
          <input
            type="password"
            value={current}
            onChange={editing(setCurrent)}
            autoComplete="current-password"
            required
            aria-invalid={refusal?.at === 'current' ? true : undefined}
            aria-describedby={refusal?.at === 'current' ? currentRefusal : undefined}
          />
        </label>
        <FieldRefusal id={currentRefusal} refusal={refusal} at="current" />
        <label>
          New password
          <input
            type="password"
            value={replacement}
            onChange={editing(setReplacement)}
            autoComplete="new-password"
            required
            aria-invalid={refusal?.at === 'replacement' ? true : undefined}
            aria-describedby={refusal?.at === 'replacement' ? replacementRefusal : undefined}
          />
        </label>
        <FieldRefusal id={replacementRefusal} refusal={refusal} at="replacement" />
        <label>
          New password again
          <input
            ref={confirmationInput}
            type="password"
            value={confirmation}
            onChange={editing(setConfirmation)}
            autoComplete="new-password"
            required
            aria-invalid={refusal?.at === 'confirmation' ? true : undefined}
            aria-describedby={refusal?.at === 'confirmation' ? confirmationRefusal : undefined}
          />
        </label>
        {/* Focus moves here, and the field it describes says it, so it is no alert as well. */}
        <FieldRefusal
          id={confirmationRefusal}
          refusal={refusal}
          at="confirmation"
          announced={false}
        />
        {/* aria-disabled rather than disabled, so focus stays on the button while it is in flight. */}
        <button type="submit" aria-disabled={submitting}>
          {submitting ? 'Changing…' : 'Change password'}
        </button>
      </form>
      <p className="done" role="status">
        {submitting ? CHANGING : done}
      </p>
      <FieldRefusal refusal={refusal} at={null} />
      {required ? (
        <p className="hint">
          Signed in as {session?.signInName}.{' '}
          <button type="button" className="link" onClick={() => void signOut()}>
            Sign out
          </button>
        </p>
      ) : null}
    </section>
  );
}

/** Which field a backend refusal is about: see {@link Refusal}. */
function refusedAt(error: ApiError): Refusal['at'] {
  if (error.isRefused) {
    return 'replacement';
  }
  return error.isForbidden ? 'current' : null;
}

/**
 * The refusal, where it is about the field `at`, or about none when `at` is null; an alert unless
 * `announced` is false, for a refusal that something else already says.
 */
function FieldRefusal({
  id,
  refusal,
  at,
  announced = true,
}: {
  id?: string;
  refusal: Refusal | null;
  at: Refusal['at'];
  announced?: boolean;
}) {
  if (refusal === null || refusal.at !== at) {
    return null;
  }
  return (
    <div id={id} className="failure" role={announced ? 'alert' : undefined}>
      {refusal.message}
    </div>
  );
}
