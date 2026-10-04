import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './styles.css';
import { startOfflineShell } from './lib/serviceWorker';

// Installing the public offline shell is tracked separately from storing a book in IndexedDB.
startOfflineShell({ production: import.meta.env.PROD });

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode><App /></React.StrictMode>,
);
