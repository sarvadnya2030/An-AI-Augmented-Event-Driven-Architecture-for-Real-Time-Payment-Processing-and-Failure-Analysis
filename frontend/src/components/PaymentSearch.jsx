import React, { useState } from 'react';
import { mcpApi } from '../api/mcpApi.js';

const DEMO_IDS = [
  'PAY-DEMO-AML-001',
  'PAY-DEMO-FRAUD-001',
  'PAY-DEMO-SETTLED-001',
  'PAY-DEMO-EMBARGO-001',
  'PAY-DEMO-ROUTING-001',
];

const STATUS_COLOR = {
  SETTLED:     '#00d4aa',
  BLOCKED:     '#ef4444',
  FAILED:      '#f59e0b',
  IN_PROGRESS: '#3b82f6',
  NOT_FOUND:   '#6b7280',
};

const STAGE_ICON = {
  COMPLETED: '✅',
  FAILED:    '❌',
  SKIPPED:   '⏭',
  PENDING:   '⏳',
};

const CATEGORY_COLOR = {
  AML_SANCTIONS:      '#ef4444',
  FRAUD_CRITICAL:     '#f59e0b',
  FRAUD_VELOCITY:     '#f97316',
  EMBARGO_BLOCKED:    '#dc2626',
  DUPLICATE_PAYMENT:  '#8b5cf6',
  VALIDATION_FAILURE: '#6366f1',
  ROUTING_FAILURE:    '#0ea5e9',
  SETTLEMENT_FAILURE: '#ec4899',
  SYSTEM_ERROR:       '#64748b',
  UNKNOWN:            '#9ca3af',
};

function StatusBadge({ status }) {
  const color = STATUS_COLOR[status] || '#9ca3af';
  return (
    <span style={{
      background: color + '22',
      color,
      border: `1px solid ${color}`,
      borderRadius: 6,
      padding: '2px 10px',
      fontWeight: 700,
      fontSize: 13,
      letterSpacing: 1,
    }}>
      {status}
    </span>
  );
}

function CategoryPill({ category }) {
  const color = CATEGORY_COLOR[category] || '#9ca3af';
  return (
    <span style={{
      background: color + '22',
      color,
      border: `1px solid ${color}`,
      borderRadius: 12,
      padding: '3px 12px',
      fontWeight: 600,
      fontSize: 12,
    }}>
      {category?.replace(/_/g, ' ')}
    </span>
  );
}

function Timeline({ stages }) {
  const [open, setOpen] = useState(null);
  if (!stages?.length) return null;

  return (
    <div style={{ marginTop: 8 }}>
      {stages.map((s) => {
        const isOpen = open === s.order;
        const isFailed = s.status === 'FAILED';
        return (
          <div key={s.order} style={{
            border: `1px solid ${isFailed ? '#ef444444' : '#ffffff18'}`,
            borderRadius: 8,
            marginBottom: 6,
            background: isFailed ? '#ef444408' : '#ffffff05',
            overflow: 'hidden',
          }}>
            <button
              onClick={() => setOpen(isOpen ? null : s.order)}
              style={{
                width: '100%', textAlign: 'left', background: 'none',
                border: 'none', cursor: 'pointer', padding: '10px 14px',
                color: '#e2e8f0', display: 'flex', alignItems: 'center', gap: 10,
              }}
            >
              <span style={{ minWidth: 24, fontSize: 16 }}>{STAGE_ICON[s.status] || '○'}</span>
              <span style={{ minWidth: 22, color: '#94a3b8', fontSize: 12 }}>#{s.order}</span>
              <span style={{ flex: 1, fontWeight: isFailed ? 700 : 400 }}>{s.displayName}</span>
              {s.keyEvent && (
                <span style={{
                  fontSize: 11, color: '#94a3b8',
                  background: '#ffffff10', borderRadius: 4,
                  padding: '1px 7px', fontFamily: 'monospace',
                }}>
                  {s.keyEvent}
                </span>
              )}
              {s.timestamp && (
                <span style={{ fontSize: 11, color: '#64748b', minWidth: 90, textAlign: 'right' }}>
                  {s.timestamp.slice(11, 19)}
                </span>
              )}
              {isFailed && (
                <span style={{ color: '#ef4444', fontSize: 11, fontWeight: 700 }}>← FAILURE</span>
              )}
              <span style={{ color: '#64748b', fontSize: 13 }}>{isOpen ? '▲' : '▼'}</span>
            </button>

            {isOpen && (
              <div style={{ padding: '0 14px 12px 50px', fontSize: 12, color: '#94a3b8' }}>
                {s.keyDetail && (
                  <div style={{
                    fontFamily: 'monospace', background: '#0f172a',
                    borderRadius: 6, padding: '8px 12px', marginBottom: 8,
                    color: '#e2e8f0', fontSize: 11, wordBreak: 'break-all',
                  }}>
                    {s.keyDetail}
                  </div>
                )}
                <div style={{ color: '#64748b' }}>
                  {s.durationMs != null && <span style={{ marginRight: 16 }}>⏱ {s.durationMs}ms</span>}
                  {s.logs?.length > 0 && <span>{s.logs.length} log event{s.logs.length !== 1 ? 's' : ''}</span>}
                </div>
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

function ExplainCard({ data }) {
  const color = STATUS_COLOR[data.overallStatus] || '#9ca3af';

  return (
    <div className="chart-card" style={{ borderLeftColor: color, borderLeftWidth: 3, borderLeftStyle: 'solid' }}>
      {/* Header row */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
        <h3 className="chart-title" style={{ margin: 0 }}>{data.paymentId}</h3>
        <StatusBadge status={data.overallStatus} />
        {data.causeCategory && data.causeCategory !== 'UNKNOWN' && (
          <CategoryPill category={data.causeCategory} />
        )}
        <span style={{ marginLeft: 'auto', fontSize: 12, color: '#64748b' }}>
          {data.analysisMs}ms • {data.totalLogEvents ?? 0} log events
        </span>
      </div>

      {/* LLM Narrative */}
      {data.narrativeSummary && (
        <div style={{
          background: '#1e293b', borderRadius: 8, padding: '12px 16px',
          marginBottom: 16, borderLeft: '3px solid #3b82f6',
        }}>
          <div style={{ fontSize: 11, color: '#64748b', marginBottom: 6, textTransform: 'uppercase', letterSpacing: 1 }}>
            AI Root Cause Analysis · via {data.llmProvider}
          </div>
          <div style={{ color: '#e2e8f0', lineHeight: 1.6 }}>{data.narrativeSummary}</div>
          {data.immediateAction && (
            <div style={{ marginTop: 10, color: '#fbbf24', fontSize: 13 }}>
              <strong>Action:</strong> {data.immediateAction}
            </div>
          )}
          {data.regulatoryNote && (
            <div style={{ marginTop: 6, fontSize: 12, color: '#94a3b8' }}>
              <strong>Regulatory:</strong> {data.regulatoryNote}
            </div>
          )}
        </div>
      )}

      {/* Primary cause + evidence */}
      {data.primaryCause && (
        <div style={{ marginBottom: 16 }}>
          <div style={{ fontSize: 11, color: '#64748b', marginBottom: 6, textTransform: 'uppercase', letterSpacing: 1 }}>
            Classifier Finding · confidence: {data.classifierConfidence}
          </div>
          <div style={{ color: '#e2e8f0', marginBottom: 6 }}>{data.primaryCause}</div>
          {data.primaryEvidence && (
            <div style={{
              fontFamily: 'monospace', fontSize: 11, color: '#94a3b8',
              background: '#0f172a', borderRadius: 6, padding: '6px 10px',
              wordBreak: 'break-all',
            }}>
              {data.primaryEvidence}
            </div>
          )}
        </div>
      )}

      {/* Pipeline timeline */}
      {data.timeline?.stages?.length > 0 && (
        <div>
          <div style={{ fontSize: 11, color: '#64748b', marginBottom: 8, textTransform: 'uppercase', letterSpacing: 1 }}>
            Pipeline Timeline
          </div>
          <Timeline stages={data.timeline.stages} />
        </div>
      )}

      {/* Timestamps */}
      {data.timeline?.firstEventTimestamp && (
        <div style={{ marginTop: 12, fontSize: 11, color: '#475569', display: 'flex', gap: 16 }}>
          <span>Start: {data.timeline.firstEventTimestamp}</span>
          <span>End: {data.timeline.lastEventTimestamp}</span>
        </div>
      )}
    </div>
  );
}

export default function PaymentSearch() {
  const [paymentId, setPaymentId] = useState('');
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);

  async function search(id) {
    const pid = (id || paymentId).trim();
    if (!pid) return;
    setPaymentId(pid);
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      const data = await mcpApi.getExplain(pid);
      setResult(data);
    } catch (e) {
      setError(e.message === 'UNAUTHORIZED'
        ? 'Invalid token — sign out and re-enter your JWT.'
        : e.message);
    } finally {
      setLoading(false);
    }
  }

  function handleKey(e) {
    if (e.key === 'Enter') search();
  }

  return (
    <div className="page">
      <div className="page-header">
        <h1>Payment Search</h1>
        <span className="badge-ai">Root Cause Analysis</span>
      </div>

      <div className="search-bar">
        <input
          className="search-input"
          type="text"
          placeholder="Enter Payment ID (e.g. PAY-DEMO-AML-001)"
          value={paymentId}
          onChange={(e) => setPaymentId(e.target.value)}
          onKeyDown={handleKey}
        />
        <button className="btn-primary" onClick={() => search()} disabled={loading}>
          {loading ? 'Analysing...' : 'Explain'}
        </button>
      </div>

      {/* Demo quick-launch chips */}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 20 }}>
        {DEMO_IDS.map((id) => (
          <button
            key={id}
            className="suggestion-chip"
            onClick={() => search(id)}
            disabled={loading}
            style={{ fontSize: 12 }}
          >
            {id}
          </button>
        ))}
      </div>

      {error && <div className="alert-error">{error}</div>}

      {result && <ExplainCard data={result} />}

      {!result && !loading && !error && (
        <div className="empty-state">
          Enter a payment ID to get AI-powered root cause analysis with full pipeline timeline.
        </div>
      )}
    </div>
  );
}
