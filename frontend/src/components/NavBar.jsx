import React from 'react';

const LINKS = [
  { id: 'dashboard', label: '📊 Dashboard' },
  { id: 'flow',      label: '🚀 Live Payments + Root Cause' },
  { id: 'graphify',  label: '🔗 Graphify' },
  { id: 'analytics', label: '📈 Analytics (Kibana/Grafana/Jaeger)' },
  { id: 'search',    label: '🤖 AI Root Cause' },
  { id: 'chat',      label: '💬 AI Chat' },
];

export default function NavBar({ page, navigate, services = [] }) {
  const downCount   = services.filter(s => s.status !== 'UP').length;
  const allUnknown  = services.every(s => s.status === 'UNKNOWN');
  const allUp       = downCount === 0 && !allUnknown;
  const dotColor    = allUnknown ? '#8b949e' : allUp ? '#3fb950' : '#f85149';
  const dotGlow     = allUnknown ? 'none' : `0 0 6px ${dotColor}`;
  const statusLabel = allUnknown ? 'Checking…' : allUp ? 'All systems operational' : `${downCount} service${downCount > 1 ? 's' : ''} down`;

  return (
    <nav className="navbar">
      <div className="navbar-brand">
        <span className="brand-icon">⚡</span>
        <span className="brand-name">ClearFlow</span>
        <span className="brand-sub">ISO 20022 · NVIDIA Nemotron</span>
      </div>
      <div className="navbar-links">
        {LINKS.map(l => (
          <button key={l.id} className={`nav-link ${page === l.id ? 'active' : ''}`} onClick={() => navigate(l.id)}>
            {l.label}
          </button>
        ))}
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 11, color: '#8b949e' }}>
        <span style={{ width: 7, height: 7, borderRadius: '50%', background: dotColor, boxShadow: dotGlow, display: 'inline-block' }} />
        {statusLabel}
      </div>
    </nav>
  );
}
