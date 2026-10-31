import { useSession } from './SessionProvider';

/**
 * Who is signed in, and the two things they can do about it.
 *
 * It reports a fact and offers no way to change it: who is acting comes from the access token, so
 * nothing in the UI can claim another role or organisation (ADR-031).
 *
 * @param onChangePassword called when the Change password button is pressed
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
      </span>{' '}
      {/*
        Shows the two parts apart; hidden from a screen reader, which would read it out as "middle
        dot". The spaces either side are what it reads them apart by.
      */}
      <span aria-hidden="true">·</span>{' '}
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
