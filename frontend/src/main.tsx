import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { ErrorBoundary } from './components/ErrorBoundary';
import { ActingAsProvider } from './identity/ActingAs';
import './styles.css';

const root = document.getElementById('root');
if (root === null) {
  throw new Error('index.html must contain #root');
}

createRoot(root).render(
  <StrictMode>
    <ErrorBoundary>
      <ActingAsProvider>
        <App />
      </ActingAsProvider>
    </ErrorBoundary>
  </StrictMode>,
);
