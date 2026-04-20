import React from 'react';

export default function NavBar({ page, navigate, onLogout }) {
  const links = [
    { id: 'dashboard', label: 'Dashboard' },
    { id: 'search', label: 'Payment Search' },
    { id: 'chat', label: 'AI Chat' },
  ];

  return (
    <nav className="navbar">
      <div className="navbar-brand">
        <span className="brand-icon">⚡</span>
        <span className="brand-name">ClearFlow</span>
        <span className="brand-sub">ISO 20022 OPS</span>
      </div>
      <div className="navbar-links">
        {links.map((l) => (
          <button
            key={l.id}
            className={`nav-link ${page === l.id ? 'active' : ''}`}
            onClick={() => navigate(l.id)}
          >
            {l.label}
          </button>
        ))}
      </div>
      <button className="btn-ghost" onClick={onLogout}>
        Sign out
      </button>
    </nav>
  );
}
