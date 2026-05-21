import React, { useState, useEffect, useCallback } from 'react';
import {
  BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Cell,
} from 'recharts';
import NavBar from './components/NavBar.jsx';
import PaymentSearch from './components/PaymentSearch.jsx';
import Chat from './components/Chat.jsx';
import {
  fetchOverview, fetchRails, fetchFraudMetrics,
  fetchAlerts, fetchSystemicHealth, fetchServiceHealth,
  cacheRead,
} from './api/clearflow.js';

// Dev token (HS256, expires 2030) — auto-injected on load
const DEV_TOKEN = 'eyJhbGciOiAiSFMyNTYiLCAidHlwIjogIkpXVCJ9.eyJzdWIiOiAiZGVtby1vcHMiLCAiaXNzIjogImNsZWFyZmxvdy1kZXYiLCAiaWF0IjogMTc3ODg2MTYxMSwgImV4cCI6IDE4OTM0NTYwMDAsICJzY29wZSI6ICJtY3A6cmVhZCBtY3A6YWRtaW4ifQ._Iz89MiCOyVY9m0MUsuSJhlFqsXY-OYvlV2ML2SFPuQ';

// ── Colours ──────────────────────────────────────────────────
const C = {
  bg: '#0d1117', surface: '#161b22', border: '#30363d',
  accent: '#58a6ff', success: '#3fb950', danger: '#f85149',
  warn: '#d29922', muted: '#8b949e', text: '#e6edf3', purple: '#a371f7',
};

const RAIL_COLORS = {
  SEPA_INSTANT: '#00d4aa', SWIFT_GPI: '#3b82f6', FEDWIRE: '#8b5cf6',
  CHIPS: '#f59e0b', FASTER_PAYMENTS: '#06b6d4', CHAPS: '#10b981',
  SEPA_CT: '#6366f1', SWIFT_MT103: '#ec4899', SEPA_CREDIT_TRANSFER: '#00d4aa',
  OTHER: '#64748b',
};
const FRAUD_COLORS = ['#3fb950', '#d29922', '#f85149', '#ff2222'];

const SERVICES = [
  { name: 'Gateway',          port: 8080 },
  { name: 'Fraud Scoring',    port: 8081 },
  { name: 'Validation',       port: 8082 },
  { name: 'AML Compliance',   port: 8083 },
  { name: 'Routing',          port: 8084 },
  { name: 'Settlement',       port: 8085 },
  { name: 'Audit',            port: 8086 },
  { name: 'MCP AI Gateway',   port: 8087 },
];

// ── Helpers ──────────────────────────────────────────────────
function fmt(n) { return n == null ? '—' : Number(n).toLocaleString(); }
function fmtTs(ts) { try { return new Date(ts).toLocaleTimeString(); } catch { return ts; } }

// ── Components ───────────────────────────────────────────────
function KPI({ label, value, sub, color }) {
  return (
    <div style={{
      background: C.surface, border: `1px solid ${C.border}`,
      borderTop: `3px solid ${color || C.accent}`, borderRadius: 8, padding: '16px 18px',
    }}>
      <div style={{ fontSize: 28, fontWeight: 700, color: color || C.accent }}>{value}</div>
      <div style={{ fontSize: 11, color: C.muted, textTransform: 'uppercase', letterSpacing: 1, marginTop: 4 }}>{label}</div>
      {sub && <div style={{ fontSize: 11, color: C.muted, marginTop: 2 }}>{sub}</div>}
    </div>
  );
}

function Card({ title, badge, children, style }) {
  return (
    <div style={{ background: C.surface, border: `1px solid ${C.border}`, borderRadius: 8, padding: 20, ...style }}>
      {(title || badge) && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 16 }}>
          {title && <div style={{ fontSize: 11, fontWeight: 700, color: C.muted, textTransform: 'uppercase', letterSpacing: 1 }}>{title}</div>}
          {badge && <span style={{ marginLeft: 'auto', fontSize: 10, fontWeight: 700, color: badge.color || C.muted, background: (badge.color || C.muted) + '22', border: `1px solid ${(badge.color || C.muted)}44`, borderRadius: 10, padding: '2px 8px', letterSpacing: 1 }}>{badge.label}</span>}
        </div>
      )}
      {children}
    </div>
  );
}

function ServiceBadge({ svc }) {
  const up = svc.status === 'UP';
  const color = up ? C.success : svc.status === 'UNKNOWN' ? C.muted : C.danger;
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, background: '#21262d', borderRadius: 6, padding: '10px 12px' }}>
      <div style={{ width: 8, height: 8, borderRadius: '50%', background: color, boxShadow: up ? `0 0 6px ${color}` : 'none', flexShrink: 0 }} />
      <div style={{ flex: 1 }}>
        <div style={{ fontSize: 12, fontWeight: 600, color: C.text }}>{svc.name}</div>
        <div style={{ fontSize: 10, color: C.muted }}>:{svc.port}</div>
      </div>
      <span style={{ fontSize: 10, fontWeight: 700, padding: '1px 7px', borderRadius: 10, background: color + '22', color }}>{svc.status}</span>
    </div>
  );
}

function AlertRow({ service, count, i }) {
  const severity = count >= 50 ? 'CRITICAL' : count >= 10 ? 'HIGH' : 'MEDIUM';
  const color = severity === 'CRITICAL' ? C.danger : severity === 'HIGH' ? C.warn : C.accent;
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '8px 12px', background: i % 2 ? '#21262d' : '#0d1117', borderRadius: 6, marginBottom: 4 }}>
      <div style={{ width: 7, height: 7, borderRadius: '50%', background: color, boxShadow: `0 0 4px ${color}` }} />
      <span style={{ flex: 1, fontSize: 12, color: C.text }}>{service}</span>
      <span style={{ fontSize: 11, color: C.muted, background: '#30363d', borderRadius: 4, padding: '1px 8px' }}>{fmt(count)} alerts</span>
      <span style={{ fontSize: 10, fontWeight: 700, color, background: color + '22', borderRadius: 4, padding: '1px 6px' }}>{severity}</span>
    </div>
  );
}

// ── Pull stored cache for instant paint ─────────────────────
function seedFromCache() {
  const ov  = cacheRead('overview')?.data ?? null;
  const rl  = cacheRead('rails')?.data;
  const fr  = cacheRead('fraud')?.data;
  const sh  = cacheRead('services')?.data ?? {};
  const ts  = [cacheRead('overview'), cacheRead('rails'), cacheRead('fraud')]
    .filter(Boolean).reduce((min, r) => Math.min(min, r.ts), Infinity);
  return {
    overview: ov,
    rails: rl && typeof rl === 'object'
      ? Object.entries(rl).map(([rail, count]) => ({ rail, count: Number(count || 0) })).sort((a, b) => b.count - a.count)
      : [],
    fraud: fr && typeof fr === 'object'
      ? Object.entries(fr).map(([band, count]) => ({ band, count: Number(count || 0) }))
      : [],
    services: SERVICES.map(s => ({ ...s, status: sh[s.name] ?? 'UNKNOWN' })),
    staleTs: isFinite(ts) ? ts : null,
  };
}

// ── Main Dashboard ───────────────────────────────────────────
function Dashboard({ onServicesChange }) {
  const seed = seedFromCache();
  const [overview,   setOverview]   = useState(seed.overview);
  const [rails,      setRails]      = useState(seed.rails);
  const [fraud,      setFraud]      = useState(seed.fraud);
  const [alerts,     setAlerts]     = useState(null);
  const [systemic,   setSystemic]   = useState(null);
  const [services,   setServices]   = useState(seed.services);
  const [lastUpdate, setLastUpdate] = useState(null);
  const [loading,    setLoading]    = useState(seed.staleTs === null); // skip spinner if we have cache
  const [staleTs,    setStaleTs]    = useState(seed.staleTs);

  const refresh = useCallback(async () => {
    const [ov, rl, fr, al, sy, sh] = await Promise.all([
      fetchOverview(), fetchRails(), fetchFraudMetrics(),
      fetchAlerts(60), fetchSystemicHealth(15), fetchServiceHealth(),
    ]);

    // Track whether ANY response came from cache
    const anyFromCache = [ov, rl, fr, al, sy, sh].some(r => r?.fromCache);
    const oldestCacheTs = [ov, rl, fr, al, sy, sh]
      .filter(r => r?.fromCache && r?.ts)
      .reduce((min, r) => Math.min(min, r.ts), Infinity);

    if (ov?.data)   setOverview(ov.data);
    if (rl?.data && typeof rl.data === 'object') {
      setRails(Object.entries(rl.data).map(([rail, count]) => ({ rail, count: Number(count || 0) })).sort((a, b) => b.count - a.count));
    }
    if (fr?.data && typeof fr.data === 'object') {
      setFraud(Object.entries(fr.data).map(([band, count]) => ({ band, count: Number(count || 0) })));
    }
    if (al?.data) setAlerts(al.data);
    if (sy?.data) setSystemic(sy.data);

    const shData = sh?.data ?? {};
    const updated = SERVICES.map(s => ({ ...s, status: shData[s.name] ?? 'UNKNOWN' }));
    setServices(updated);
    onServicesChange?.(updated);

    setStaleTs(anyFromCache && isFinite(oldestCacheTs) ? oldestCacheTs : null);
    setLastUpdate(new Date());
    setLoading(false);
  }, []);

  // Propagate seeded services to NavBar immediately on mount
  useEffect(() => { onServicesChange?.(seed.services); }, []); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { refresh(); const id = setInterval(refresh, 20000); return () => clearInterval(id); }, [refresh]);

  const submitted = overview?.paymentsSubmitted ?? 0;
  const settled   = overview?.settled ?? 0;
  const settlePct = submitted > 0 ? ((settled / submitted) * 100).toFixed(1) : '0';

  const alertsByService = alerts?.alertsByService || {};
  const alertEntries    = Object.entries(alertsByService).filter(([, c]) => c > 0).sort(([, a], [, b]) => b - a);

  const funnel = [
    { stage: 'Submitted',  count: submitted },
    { stage: 'AML OK',     count: submitted - (overview?.amlBlocked ?? 0) },
    { stage: 'Routed',     count: overview?.routed ?? settled },
    { stage: 'Settled',    count: settled },
  ].filter(s => s.count > 0);

  const staleAgeMin = staleTs ? Math.round((Date.now() - staleTs) / 60000) : 0;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>

      {/* ── Stale data banner ── */}
      {staleTs && (
        <div style={{
          display: 'flex', alignItems: 'center', gap: 10,
          background: 'rgba(210,153,34,0.10)', border: `1px solid ${C.warn}55`,
          borderLeft: `3px solid ${C.warn}`, borderRadius: 8, padding: '10px 16px',
        }}>
          <span style={{ fontSize: 14 }}>⚠</span>
          <span style={{ fontSize: 12, color: C.warn, fontWeight: 600 }}>
            Backend offline — showing cached data from {staleAgeMin < 1 ? 'just now' : `${staleAgeMin} min ago`}
          </span>
          <span style={{ fontSize: 11, color: C.muted, marginLeft: 'auto' }}>
            Charts and KPIs reflect last successful poll. Start services to go live.
          </span>
        </div>
      )}

      {/* ── Header ── */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
        <h1 style={{ fontSize: 22, fontWeight: 700, color: C.text }}>Operations Dashboard</h1>
        {staleTs
          ? <span style={{ background: 'rgba(210,153,34,0.15)', color: C.warn, fontSize: 10, fontWeight: 700, padding: '3px 8px', borderRadius: 20, border: `1px solid rgba(210,153,34,0.3)`, letterSpacing: 1 }}>● CACHED</span>
          : <span style={{ background: 'rgba(63,185,80,0.15)', color: C.success, fontSize: 10, fontWeight: 700, padding: '3px 8px', borderRadius: 20, border: `1px solid rgba(63,185,80,0.3)`, letterSpacing: 1 }}>● LIVE</span>
        }
        <span style={{ marginLeft: 'auto', fontSize: 11, color: C.muted }}>{lastUpdate ? `Updated ${fmtTs(lastUpdate)}` : 'Loading…'}</span>
        <button onClick={refresh} style={{ background: 'transparent', border: `1px solid ${C.border}`, color: C.muted, cursor: 'pointer', padding: '5px 14px', borderRadius: 6, fontSize: 12 }}>
          Refresh
        </button>
      </div>

      {/* ── KPI row ── */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))', gap: 14 }}>
        <KPI label="Payments (24h)"  value={loading ? '…' : fmt(submitted)}    sub="submitted to pipeline"            color={C.accent}  />
        <KPI label="Settlement Rate" value={loading ? '…' : `${settlePct}%`}    sub={`${fmt(settled)} settled`}        color={C.success} />
        <KPI label="Fraud Flagged"   value={loading ? '…' : fmt(overview?.fraudFlagged ?? 0)}  sub="high-risk payments"  color={C.warn}    />
        <KPI label="AML Blocked"     value={loading ? '…' : fmt(overview?.amlBlocked ?? 0)}    sub="sanctions & embargo" color={C.danger}  />
        <KPI label="Avg Latency"     value={loading ? '…' : `${overview?.avgLatencyMs ?? 0}ms`} sub="pipeline end-to-end" color={C.purple}  />
        <KPI label="Active Rails"    value={loading ? '…' : fmt(rails.length || overview?.activeRails)} sub="payment rails"  color={C.accent}  />
      </div>

      {/* ── Service Health ── */}
      <Card title="Service Health" badge={{ label: '8 MICROSERVICES', color: C.success }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(190px, 1fr))', gap: 8 }}>
          {services.map(s => <ServiceBadge key={s.port} svc={s} />)}
        </div>
      </Card>

      {/* ── Charts row ── */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16 }}>

        {/* Pipeline funnel */}
        <Card title="Pipeline Funnel (24h)">
          {funnel.length === 0
            ? <div style={{ color: C.muted, fontSize: 12, textAlign: 'center', padding: 32 }}>{loading ? 'Loading…' : 'No data yet — run a payment batch'}</div>
            : <ResponsiveContainer width="100%" height={220}>
                <BarChart data={funnel} margin={{ top: 4, right: 4, left: -20, bottom: 0 }}>
                  <XAxis dataKey="stage" tick={{ fill: C.muted, fontSize: 10 }} />
                  <YAxis tick={{ fill: C.muted, fontSize: 10 }} />
                  <Tooltip contentStyle={{ background: '#21262d', border: `1px solid ${C.border}`, color: C.text }} />
                  <Bar dataKey="count" fill={C.accent} radius={[4, 4, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
          }
        </Card>

        {/* Rail distribution */}
        <Card title="Rail Distribution (24h)">
          {rails.length === 0
            ? <div style={{ color: C.muted, fontSize: 12, textAlign: 'center', padding: 32 }}>{loading ? 'Loading…' : 'No data'}</div>
            : <ResponsiveContainer width="100%" height={220}>
                <BarChart data={rails} margin={{ top: 4, right: 4, left: -20, bottom: 0 }}>
                  <XAxis dataKey="rail" tick={{ fill: C.muted, fontSize: 9 }} />
                  <YAxis tick={{ fill: C.muted, fontSize: 10 }} />
                  <Tooltip contentStyle={{ background: '#21262d', border: `1px solid ${C.border}`, color: C.text }} />
                  <Bar dataKey="count" radius={[4, 4, 0, 0]}>
                    {rails.map(e => <Cell key={e.rail} fill={RAIL_COLORS[e.rail] || RAIL_COLORS.OTHER} />)}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
          }
        </Card>

        {/* Fraud risk bands */}
        <Card title="Fraud Risk Bands (24h)">
          {fraud.length === 0
            ? <div style={{ color: C.muted, fontSize: 12, textAlign: 'center', padding: 32 }}>{loading ? 'Loading…' : 'No data'}</div>
            : <ResponsiveContainer width="100%" height={220}>
                <BarChart data={fraud} margin={{ top: 4, right: 4, left: -20, bottom: 0 }}>
                  <XAxis dataKey="band" tick={{ fill: C.muted, fontSize: 9 }} />
                  <YAxis tick={{ fill: C.muted, fontSize: 10 }} />
                  <Tooltip contentStyle={{ background: '#21262d', border: `1px solid ${C.border}`, color: C.text }} />
                  <Bar dataKey="count" radius={[4, 4, 0, 0]}>
                    {fraud.map((e, i) => <Cell key={e.band} fill={FRAUD_COLORS[i] || C.muted} />)}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
          }
        </Card>
      </div>

      {/* ── Alerts + Systemic row ── */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>

        {/* Active alerts */}
        <Card title="Active Alerts (last 60 min)">
          {alertEntries.length === 0
            ? <div style={{ color: C.muted, fontSize: 12, textAlign: 'center', padding: 24 }}>{loading ? 'Loading…' : 'No active alerts'}</div>
            : alertEntries.map(([svc, count], i) => <AlertRow key={svc} service={svc} count={count} i={i} />)
          }
        </Card>

        {/* Systemic diagnostics */}
        <Card
          title="Systemic Diagnostics — AI Analysis"
          badge={systemic ? { label: systemic.severity || 'NORMAL', color: systemic.isSystemic ? C.danger : C.success } : undefined}
        >
          {!systemic
            ? <div style={{ color: C.muted, fontSize: 12, textAlign: 'center', padding: 24 }}>{loading ? 'Loading…' : 'No data'}</div>
            : <div style={{ display: 'flex', flexDirection: 'column', gap: 10, fontSize: 13 }}>
                <div>
                  <span style={{ color: C.muted, fontSize: 11 }}>STATUS: </span>
                  <span style={{ color: systemic.isSystemic ? C.danger : C.success, fontWeight: 700 }}>
                    {systemic.isSystemic ? 'SYSTEMIC ISSUE DETECTED' : 'NORMAL'}
                  </span>
                </div>
                {systemic.affectedServices?.length > 0 && (
                  <div><span style={{ color: C.muted, fontSize: 11 }}>AFFECTED: </span>{systemic.affectedServices.join(', ')}</div>
                )}
                {systemic.pattern && (
                  <div><span style={{ color: C.muted, fontSize: 11 }}>PATTERN: </span>{systemic.pattern}</div>
                )}
                {systemic.llmNarrative && (
                  <div style={{ background: 'rgba(163,113,247,0.08)', border: `1px solid ${C.purple}44`, borderLeft: `3px solid ${C.purple}`, borderRadius: 6, padding: '10px 12px', marginTop: 4 }}>
                    <div style={{ fontSize: 10, color: C.purple, fontWeight: 700, marginBottom: 4 }}>🤖 NVIDIA NEMOTRON ANALYSIS</div>
                    <div style={{ color: C.text, lineHeight: 1.5 }}>{systemic.llmNarrative}</div>
                  </div>
                )}
                {systemic.suggestedAction && (
                  <div style={{ background: 'rgba(88,166,255,0.08)', borderLeft: `2px solid ${C.accent}`, borderRadius: 4, padding: '8px 12px' }}>
                    <div style={{ fontSize: 10, color: C.accent, fontWeight: 700, marginBottom: 2 }}>ACTION</div>
                    <div style={{ color: C.text, fontSize: 12 }}>{systemic.suggestedAction}</div>
                  </div>
                )}
              </div>
          }
        </Card>
      </div>

      {/* ── Quick links ── */}
      <Card title="Quick Links">
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10 }}>
          {[
            { label: '📊 Kibana', url: 'http://localhost:5601', color: C.warn },
            { label: '📈 Grafana', url: 'http://localhost:3001', color: C.accent },
            { label: '🔍 Jaeger Traces', url: 'http://localhost:16686', color: C.purple },
            { label: '🛡 Prometheus', url: 'http://localhost:9090', color: C.danger },
            { label: '🗄 Swagger UI', url: 'http://localhost:8087/swagger-ui.html', color: C.success },
          ].map(({ label, url, color }) => (
            <a key={url} href={url} target="_blank" rel="noreferrer" style={{
              display: 'inline-block', background: color + '15',
              color, border: `1px solid ${color}44`, borderRadius: 6,
              padding: '6px 14px', fontSize: 12, fontWeight: 600, textDecoration: 'none',
              transition: 'background 0.15s',
            }}>
              {label}
            </a>
          ))}
        </div>
      </Card>
    </div>
  );
}

// ── Root app ─────────────────────────────────────────────────
export default function App() {
  // Auto-inject dev token — no modal blocking
  useEffect(() => {
    if (!localStorage.getItem('clearflow_token')) {
      localStorage.setItem('clearflow_token', DEV_TOKEN);
    }
  }, []);

  const [page,     setPage]     = useState(window.location.hash.replace('#', '') || 'dashboard');
  const [services, setServices] = useState(SERVICES.map(s => ({ ...s, status: 'UNKNOWN' })));

  useEffect(() => {
    const onHash = () => setPage(window.location.hash.replace('#', '') || 'dashboard');
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  function navigate(p) { window.location.hash = p; setPage(p); }

  return (
    <div className="app">
      <NavBar page={page} navigate={navigate} services={services} />
      <main className="main-content">
        {page === 'dashboard' && <Dashboard onServicesChange={setServices} />}
        {page === 'search'    && <PaymentSearch />}
        {page === 'chat'      && <Chat />}
      </main>
    </div>
  );
}
