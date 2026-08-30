import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { ActingAsProvider } from './identity/ActingAs';
import './styles.css';

const root = document.getElementById('root');
if (root === null) {
  throw new Error('index.html must contain #root');
}

createRoot(root).render(
  <StrictMode>
    <ActingAsProvider>
      <App />
    </ActingAsProvider>
  </StrictMode>,
);
