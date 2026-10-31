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

/**
 * Why the last submission did not change the password, and which field it is about: the
 * confirmation for a mismatch, the new password for a backend refusal of it, and none for anything
 * else, such as a current password the backend did not accept.
 */
interface Refusal {
  readonly at: 'replacement' | 'confirmation' | null;
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
 * A refusal about one field is shown beside it, marks it invalid and describes it (#257, C6), and
 * any edit clears it, as it does a success, because either is about what was sent, not what is
 * being typed (C9).
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
      setRefusal(null);
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
          ? { at: error.isRefused ? 'replacement' : null, message: error.message }
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
          />
        </label>
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
        <FieldRefusal id={confirmationRefusal} refusal={refusal} at="confirmation" />
        {/* aria-disabled rather than disabled, so focus stays on the button while it is in flight. */}
        <button type="submit" aria-disabled={submitting}>
          {submitting ? 'Changing…' : 'Change password'}
        </button>
      </form>
      <p className="done" role="status">
        {done}
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

/** The refusal, where it is about the field `at`, or about none when `at` is null. */
function FieldRefusal({
  id,
  refusal,
  at,
}: {
  id?: string;
  refusal: Refusal | null;
  at: Refusal['at'];
}) {
  if (refusal === null || refusal.at !== at) {
    return null;
  }
  return (
    <div id={id} className="failure" role="alert">
      {refusal.message}
    </div>
  );
}
