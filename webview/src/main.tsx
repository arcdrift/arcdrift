import React from 'react';
import ReactDOM from 'react-dom/client';
import { ArcCanvas } from './components/ArcCanvas';

const rootEl = document.getElementById('root');
if (rootEl) {
  ReactDOM.createRoot(rootEl).render(
    <React.StrictMode>
      <ArcCanvas />
    </React.StrictMode>
  );
}
