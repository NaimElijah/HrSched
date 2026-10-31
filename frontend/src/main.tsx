import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { Root } from './Root';
import './styles.css';

const root = document.getElementById('root');
if (root === null) {
  throw new Error('index.html must contain #root');
}

createRoot(root).render(
  <StrictMode>
    <Root />
  </StrictMode>,
);
