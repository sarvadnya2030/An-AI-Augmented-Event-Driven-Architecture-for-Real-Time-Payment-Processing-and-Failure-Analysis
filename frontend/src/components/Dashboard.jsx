import React, { useEffect, useState } from 'react';
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Cell } from 'recharts';
import { mcpApi } from '../api/mcpApi.js';

const RAIL_COLORS = {
  SEPA_INSTANT: '#00d4aa',
  SWIFT_GPI: '#3b82f6',
  FEDWIRE: '#8b5cf6',
  CHIPS: '#f59e0b',
  FASTER_PAYMENTS: '#06b6d4',
  CHAPS: '#10b981',
  SEPA_CT: '#6366f1',
  OTHER: '#64748b',
};

const MOCK_RAILS = [
  { rail: 'SEPA_INSTANT', count: 4821 },
  { rail: 'SWIFT_GPI', count: 3204 },
  { rail: 'FEDWIRE', count: 1897 },
  { rail: 'CHIPS', count: 1102 },
  { rail: 'FASTER_PAYMENTS', count: 967 },
  { rail: 'CHAPS', count: 512 },
  { rail: 'SEPA_CT', count: 388 },
];

const MOCK_FRAUD = [
  { band: 'LOW (0-30)', count: 11203 },
  { band: 'MEDIUM (30-60)', count: 1847 },
  { band: 'HIGH (60-80)', count: 423 },
  { band: 'CRITICAL (80+)', count: 61 },
];

const SERVICE_STATUS = [
  { name: 'Gateway', port: 8080, status: 'UP' },
  { name: 'Fraud Scoring', port: 8081, status: 'UP' },
  { name: 'Validation', port: 8082, status: 'UP' },
  { name: 'AML Compliance', port: 8083, status: 'UP' },
  { name: 'Routing', port: 8084, status: 'UP' },
  { name: 'Settlement', port: 8085, status: 'UP' },
  { name: 'Audit', port: 8086, status: 'UP' },
  { name: 'MCP Gateway', port: 8087, status: 'UP' },
];

function StatCard({ label, value, sub, accent }) {
  return (
    <div className="stat-card" style={{ borderTopColor: accent }}>
      <div className="stat-value" style={{ color: accent }}>{value}</div>
      <div className="stat-label">{label}</div>
      {sub && <div className="stat-sub">{sub}</div>}
    </div>
  );
}

export default function Dashboard() {
  const [railData, setRailData] = useState(MOCK_RAILS);
  const [fraudData, setFraudData] = useState(MOCK_FRAUD);
  const [overview, setOverview] = useState(null);
  const [systemic, setSystemic] = useState(null);
  const [alerts, setAlerts] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    async function loadAll() {
      try {
        const [rails, fraud, summary, systemicReport, activeAlerts] = await Promise.all([
          mcpApi.getRails(),
          mcpApi.getFraud(),
          mcpApi.getOverview(),
          mcpApi.getSystemic(60),
          mcpApi.getAlerts(60),
        ]);

        if (rails && typeof rails === 'object') {
          setRailData(Object.entries(rails).map(([rail, count]) => ({ rail, count: Number(count || 0) })));
        }

        if (fraud && typeof fraud === 'object') {
          setFraudData(Object.entries(fraud).map(([band, count]) => ({ band, count: Number(count || 0) })));
        }

        setOverview(summary);
        setSystemic(systemicReport);
        setAlerts(activeAlerts);
      } catch (err) {
        setError('Failed to load MCP metrics: ' + err.message);
      } finally {
        setLoading(false);
      }
    }

    loadAll();
  }, []);

  return (
    <div className="page">
      <div className="page-header">
        <h1>Operations Dashboard</h1>
        <span className="badge-live">LIVE</span>
      </div>

      {error && <div className="alert-warn">{error}</div>}

      <div className="stat-grid">
        <StatCard
          label="Payments (24h)"
          value={overview ? overview.paymentsSubmitted : '...'}
          sub={overview ? `submitted in last 24h` : 'loading...'}
          accent="#00d4aa"
        />
        <StatCard
          label="Settled"
          value={overview ? overview.settled : '...'}
          sub={overview ? `${overview.settled > 0 && overview.paymentsSubmitted > 0 ? Math.round((overview.settled / overview.paymentsSubmitted) * 100) : 0}% success` : 'loading...'}
          accent="#10b981"
        />
        <StatCard
          label="Fraud Flagged"
          value={overview ? overview.fraudFlagged : '...'}
          sub={overview ? `${overview.fraudFlagged} payments blocked` : 'loading...'}
          accent="#f59e0b"
        />
        <StatCard
          label="AML Blocked"
          value={overview ? overview.amlBlocked : '...'}
          sub={overview ? `${overview.amlBlocked} sanctions hits` : 'loading...'}
          accent="#ef4444"
        />
        <StatCard
          label="Avg Latency"
          value={overview ? `${overview.avgLatencyMs}ms` : '...'}
          sub={overview ? 'avg pipeline latency' : 'loading...'}
          accent="#3b82f6"
        />
        <StatCard
          label="Active Rails"
          value={overview ? overview.activeRails : railData.length}
          sub="from routing model"
          accent="#8b5cf6"
        />
      </div>

      <div className="chart-grid">
        <div className="chart-card">
          <h3 className="chart-title">Rail Distribution (24h)</h3>
          <ResponsiveContainer width="100%" height={240}>
            <BarChart data={railData} margin={{ top: 8, right: 8, left: -16, bottom: 0 }}>
              <XAxis dataKey="rail" tick={{ fill: '#94a3b8', fontSize: 11 }} />
              <YAxis tick={{ fill: '#94a3b8', fontSize: 11 }} />
              <Tooltip
                contentStyle={{ background: '#1e293b', border: '1px solid #334155', color: '#f1f5f9' }}
              />
              <Bar dataKey="count" radius={[4, 4, 0, 0]}>
                {railData.map((entry) => (
                  <Cell key={entry.rail} fill={RAIL_COLORS[entry.rail] || RAIL_COLORS.OTHER} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>

        <div className="chart-card">
          <h3 className="chart-title">Fraud Score Distribution (24h)</h3>
          <ResponsiveContainer width="100%" height={240}>
            <BarChart data={fraudData} margin={{ top: 8, right: 8, left: -16, bottom: 0 }}>
              <XAxis dataKey="band" tick={{ fill: '#94a3b8', fontSize: 11 }} />
              <YAxis tick={{ fill: '#94a3b8', fontSize: 11 }} />
              <Tooltip
                contentStyle={{ background: '#1e293b', border: '1px solid #334155', color: '#f1f5f9' }}
              />
              <Bar dataKey="count" radius={[4, 4, 0, 0]} fill="#3b82f6" />
            </BarChart>
          </ResponsiveContainer>
        </div>
      </div>

      <div className="chart-card" style={{ marginTop: 20 }}>
        <h3 className="chart-title">Systemic Alert Summary</h3>
        {loading && <p style={{ color: '#94a3b8' }}>Loading systemic metrics…</p>}
        {systemic && (
          <>
            <p style={{ marginBottom: 8 }}>
              <strong>Systemic status:</strong> {systemic.isSystemic ? 'DETECTED' : 'NORMAL'}
              {systemic.severity && ` (${systemic.severity})`}
            </p>
            <p style={{ marginBottom: 4 }}><strong>Affected services:</strong> {systemic.affectedServices?.join(', ') || 'none'}</p>
            <p style={{ marginBottom: 4 }}><strong>Pattern:</strong> {systemic.pattern || 'No systemic pattern detected'}</p>
            <p style={{ marginBottom: 4 }}><strong>Suggested action:</strong> {systemic.suggestedAction}</p>
          </>
        )}
        {alerts && (
          <>
            <div style={{ marginTop: 12, color: '#94a3b8' }}>Active HIGH alerts (last 60m):</div>
            <ul style={{ margin: '8px 0 0 16px', color: '#e2e8f0', fontSize: 13 }}>
              {Object.entries(alerts.alertsByService || {}).map(([svc, count]) => (
                <li key={svc}>{svc}: {count}</li>
              ))}
            </ul>
          </>
        )}
      </div>

      <div className="chart-card">
        <h3 className="chart-title">Service Health</h3>
        <div className="service-grid">
          {SERVICE_STATUS.map((s) => (
            <div key={s.name} className="service-card">
              <div className={`status-dot ${s.status === 'UP' ? 'up' : 'down'}`} />
              <div>
                <div className="service-name">{s.name}</div>
                <div className="service-port">:{s.port}</div>
              </div>
              <span className={`badge ${s.status === 'UP' ? 'badge-green' : 'badge-red'}`}>
                {s.status}
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
