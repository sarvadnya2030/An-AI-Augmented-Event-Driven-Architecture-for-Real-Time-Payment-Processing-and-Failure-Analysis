import React, { useState, useEffect } from 'react';
import NavBar from './components/NavBar.jsx';
import Dashboard from './components/Dashboard.jsx';
import PaymentSearch from './components/PaymentSearch.jsx';
import Chat from './components/Chat.jsx';

function TokenModal({ onSave }) {
  const [val, setVal] = useState('');
  return (
    <div className="modal-overlay">
      <div className="modal">
        <h2>Set Auth Token</h2>
        <p className="modal-hint">Paste a Keycloak JWT for the ClearFlow realm.</p>
        <textarea
          className="token-input"
          rows={5}
          placeholder="eyJhbGciOiJSUzI1NiIsInR5..."
          value={val}
          onChange={(e) => setVal(e.target.value)}
        />
        <button className="btn-primary" onClick={() => onSave(val.trim())}>
          Save &amp; Continue
        </button>
      </div>
    </div>
  );
}

export default function App() {
  const [page, setPage] = useState(window.location.hash.replace('#', '') || 'dashboard');
  const [hasToken, setHasToken] = useState(!!localStorage.getItem('clearflow_token'));

  useEffect(() => {
    const onHash = () => setPage(window.location.hash.replace('#', '') || 'dashboard');
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  function saveToken(token) {
    localStorage.setItem('clearflow_token', token);
    setHasToken(true);
  }

  if (!hasToken) return <TokenModal onSave={saveToken} />;

  function navigate(p) {
    window.location.hash = p;
    setPage(p);
  }

  return (
    <div className="app">
      <NavBar
        page={page}
        navigate={navigate}
        onLogout={() => { localStorage.removeItem('clearflow_token'); setHasToken(false); }}
      />
      <main className="main-content">
        {page === 'dashboard' && <Dashboard />}
        {page === 'search' && <PaymentSearch />}
        {page === 'chat' && <Chat />}
      </main>
    </div>
  );
}
