import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { ErrorBoundary } from './components/ErrorBoundary';
import { SessionProvider } from './auth/SessionProvider';
import './styles.css';

const root = document.getElementById('root');
if (root === null) {
  throw new Error('index.html must contain #root');
}

// The boundary sits inside the provider, so "Try again" re-renders the shell over the session it
// had. Outside it, a retry would remount the provider and restore the session from the start.
createRoot(root).render(
  <StrictMode>
    <SessionProvider>
      <ErrorBoundary>
        <App />
      </ErrorBoundary>
    </SessionProvider>
  </StrictMode>,
);
