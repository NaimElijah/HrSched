import { App } from './App';
import { SessionProvider } from './auth/SessionProvider';
import { ErrorBoundary } from './components/ErrorBoundary';

/**
 * The application as `main.tsx` mounts it: the session, around the error boundary, around the
 * shell.
 *
 * The boundary sits inside the provider, so "Try again" re-renders the shell over the session it
 * had. Outside it, a retry would remount the provider and restore the session from the start: a
 * screen with no heading to take the focus the retry's button gave up (#257).
 */
export function Root() {
  return (
    <SessionProvider>
      <ErrorBoundary>
        <App />
      </ErrorBoundary>
    </SessionProvider>
  );
}
