import { useSession } from './SessionProvider';

/**
 * Who is signed in, and the two things they can do about it.
 *
 * Replaces the acting-as bar, and the difference is the point: that one let anybody claim any role
 * in any organisation, because the backend believed whatever it was sent. This one reports a fact
 * and offers no way to change it.
 */
export function SessionBar({ onChangePassword }: { onChangePassword: () => void }) {
  const { session, signOut } = useSession();
  if (session === null) {
    return null;
  }
  return (
    <div className="session-bar">
      <span>
        Signed in as <strong>{session.signInName}</strong>
      </span>
      <span>
        {session.kind === 'OPERATOR' ? (
          'Platform operator'
        ) : (
          <>
            {session.role}
            {session.organisation === null ? null : (
              <>
                {' in '}
                <code>{session.organisation}</code>
              </>
            )}
          </>
        )}
      </span>
      <button type="button" onClick={onChangePassword}>
        Change password
      </button>
      <button type="button" onClick={() => void signOut()}>
        Sign out
      </button>
    </div>
  );
}
