import React, { useState, useRef } from 'react';

const C = {
  bg: '#0d1117', surface: '#161b22', border: '#30363d',
  accent: '#58a6ff', success: '#3fb950', danger: '#f85149',
  warn: '#d29922', muted: '#8b949e', text: '#e6edf3', purple: '#a371f7',
};

const PARTIES = [
  { name: 'Alpine Logistics GmbH',  iban: 'DE89370400440532013000',     bic: 'DEUTDEDBXXX', country: 'DE' },
  { name: 'Euro Trade SARL',        iban: 'FR7630006000011234567890189', bic: 'BNPAFRPPXXX', country: 'FR' },
  { name: 'HSBC Holdings PLC',      iban: 'GB29NWBK60161331926819',     bic: 'HBUKGB4BXXX', country: 'GB' },
  { name: 'UBS AG Zurich',          iban: 'CH5604835012345678009',       bic: 'UBSWCHZHXXX', country: 'CH' },
  { name: 'ING Bank NV',            iban: 'NL91ABNA0417164300',         bic: 'INGBNL2AXXX', country: 'NL' },
  { name: 'Banco Santander SA',     iban: 'ES9121000418450200051332',   bic: 'BSCHESMM',    country: 'ES' },
  { name: 'UniCredit SpA',          iban: 'IT60X0542811101000000123456', bic: 'UNCRITMM',    country: 'IT' },
  { name: 'Raiffeisen Bank Intl',   iban: 'AT611904300234573201',       bic: 'RZOOAT2L',    country: 'AT' },
];

const CHANNELS = ['SEPA', 'SWIFT_GPI', 'FASTER_PAYMENTS', 'FEDWIRE'];

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = Math.random() * 16 | 0;
    return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
  });
}

function buildPayload() {
  const debtorIdx = Math.floor(Math.random() * PARTIES.length);
  let creditorIdx = Math.floor(Math.random() * PARTIES.length);
  while (creditorIdx === debtorIdx) creditorIdx = Math.floor(Math.random() * PARTIES.length);

  const debtor   = PARTIES[debtorIdx];
  const creditor = PARTIES[creditorIdx];
  const today    = new Date();
  const tomorrow = new Date(today); tomorrow.setDate(today.getDate() + 1);
  const valueDateStr = tomorrow.toISOString().split('T')[0];

  return {
    instructionId:  uuid(),
    endToEndId:     `E2E-${today.toISOString().slice(0,10).replace(/-/g,'')}-${Math.floor(Math.random()*90000+10000)}`,
    uetr:           uuid(),
    debtor:  { name: debtor.name,   iban: debtor.iban,   bic: debtor.bic,   address: `${debtor.country} HQ`,     country: debtor.country },
    creditor:{ name: creditor.name, iban: creditor.iban, bic: creditor.bic, address: `${creditor.country} Branch`, country: creditor.country },
    amount:       parseFloat((Math.random() * 249900 + 100).toFixed(2)),
    currency:     ['EUR','USD','GBP','CHF'][Math.floor(Math.random()*4)],
    valueDate:    valueDateStr,
    purpose:      'SUPP',
    remittanceInfo: `Invoice INV-${Math.floor(Math.random()*90000+10000)} settlement`,
    channel:      CHANNELS[Math.floor(Math.random() * CHANNELS.length)],
  };
}

const DEV_TOKEN = 'eyJhbGciOiAiSFMyNTYiLCAidHlwIjogIkpXVCJ9.eyJzdWIiOiAiZGVtby1vcHMiLCAiaXNzIjogImNsZWFyZmxvdy1kZXYiLCAiaWF0IjogMTc3ODg2MTYxMSwgImV4cCI6IDE4OTM0NTYwMDAsICJzY29wZSI6ICJtY3A6cmVhZCBtY3A6YWRtaW4ifQ._Iz89MiCOyVY9m0MUsuSJhlFqsXY-OYvlV2ML2SFPuQ';

function statusColor(s) {
  if (s === 'accepted') return C.success;
  if (s === 'rejected') return C.danger;
  return C.muted;
}
function statusIcon(s) {
  if (s === 'accepted') return '✓';
  if (s === 'rejected') return '✗';
  return '⏳';
}

function RootCausePanel({ result, loading, error }) {
  if (loading) return (
    <div style={{ display:'flex', alignItems:'center', justifyContent:'center', height:200, color: C.muted, fontSize:13 }}>
      ⏳ Querying MCP root cause engine…
    </div>
  );
  if (error) return (
    <div style={{ padding:16, color: C.warn, fontSize:12 }}>
      ⚠ {error}
    </div>
  );
  if (!result) return (
    <div style={{ display:'flex', alignItems:'center', justifyContent:'center', height:200, color: C.muted, fontSize:13 }}>
      Click a payment ID — or enter one below — to run root cause analysis.
    </div>
  );

  const ok = result.overallStatus === 'SETTLED' || result.overallStatus === 'COMPLETED';
  const notFound = result.overallStatus === 'NOT_FOUND';

  return (
    <div style={{ fontSize: 12, lineHeight: 1.65 }}>
      {/* Header */}
      <div style={{ marginBottom: 14 }}>
        <div style={{ fontSize: 13, fontWeight: 700, color: C.text, marginBottom: 6 }}>
          Payment ID: <span style={{ color: C.accent, fontFamily: 'monospace' }}>{result.paymentId}</span>
        </div>
        <span style={{
          padding: '3px 10px', borderRadius: 4, fontSize: 11, fontWeight: 700,
          background: ok ? C.success+'22' : notFound ? C.muted+'22' : C.danger+'22',
          color: ok ? C.success : notFound ? C.muted : C.danger,
        }}>
          {result.overallStatus}
        </span>
      </div>

      {notFound && (
        <div style={{ color: C.muted, fontSize: 12 }}>
          No Elasticsearch logs found for this payment ID yet. Wait ~10s after sending and retry — Logstash ingestion has a small delay.
        </div>
      )}

      {!notFound && (
        <>
          {/* Classification */}
          {result.causeCategory && result.causeCategory !== 'NO_DATA' && (
            <div style={{ background: ok ? C.success+'10' : C.danger+'12', border: `1px solid ${ok ? C.success : C.danger}33`, borderRadius: 6, padding: 10, marginBottom: 12 }}>
              <div style={{ fontWeight: 700, color: ok ? C.success : C.danger, marginBottom: 4 }}>
                {ok ? '✓ Successfully settled' : `✗ Root cause: ${result.causeCategory}`}
              </div>
              {result.primaryCause && (
                <div style={{ color: C.text }}>{result.primaryCause}</div>
              )}
              {result.primaryEvidence && (
                <div style={{ color: C.muted, marginTop: 4, fontSize: 11 }}>{result.primaryEvidence}</div>
              )}
              {result.failedAtService && (
                <div style={{ color: C.danger, marginTop: 6, fontSize: 11 }}>
                  Failed service: <strong>{result.failedAtService}</strong>{result.failedAtStage ? ` (${result.failedAtStage})` : ''}
                </div>
              )}
            </div>
          )}

          {/* LLM narrative */}
          {result.narrativeSummary && (
            <div style={{ marginBottom: 12 }}>
              <div style={{ color: C.muted, fontWeight: 700, marginBottom: 4, textTransform: 'uppercase', fontSize: 10, letterSpacing: 1 }}>
                AI Analysis {result.llmProvider ? `(${result.llmProvider})` : ''}
              </div>
              <div style={{ color: C.text }}>{result.narrativeSummary}</div>
            </div>
          )}

          {result.immediateAction && (
            <div style={{ marginBottom: 12 }}>
              <div style={{ color: C.warn, fontWeight: 700, marginBottom: 4, textTransform: 'uppercase', fontSize: 10, letterSpacing: 1 }}>Recommended Action</div>
              <div style={{ color: C.text }}>{result.immediateAction}</div>
            </div>
          )}

          {result.regulatoryNote && (
            <div style={{ marginBottom: 12 }}>
              <div style={{ color: C.purple, fontWeight: 700, marginBottom: 4, textTransform: 'uppercase', fontSize: 10, letterSpacing: 1 }}>Regulatory Note</div>
              <div style={{ color: C.text }}>{result.regulatoryNote}</div>
            </div>
          )}

          {/* Timeline */}
          {result.timeline?.stages && result.timeline.stages.length > 0 && (
            <div style={{ marginTop: 8 }}>
              <div style={{ color: C.muted, fontWeight: 700, marginBottom: 8, textTransform: 'uppercase', fontSize: 10, letterSpacing: 1 }}>Pipeline Timeline</div>
              {result.timeline.stages.map((s, i) => {
                const col = s.status === 'COMPLETED' ? C.success : s.status === 'FAILED' ? C.danger : C.muted;
                return (
                  <div key={i} style={{ display: 'flex', alignItems: 'flex-start', gap: 8, marginBottom: 6 }}>
                    <span style={{ color: col, fontWeight: 700, width: 12, flexShrink: 0 }}>
                      {s.status === 'COMPLETED' ? '✓' : s.status === 'FAILED' ? '✗' : '○'}
                    </span>
                    <div style={{ flex: 1 }}>
                      <span style={{ color: col, fontWeight: 600 }}>{s.service}</span>
                      {s.durationMs != null && (
                        <span style={{ color: C.muted, marginLeft: 6, fontSize: 10 }}>{s.durationMs}ms</span>
                      )}
                      {s.error && (
                        <div style={{ color: C.danger, fontSize: 11, marginTop: 2 }}>{s.error}</div>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          )}

          {result.analysisMs != null && (
            <div style={{ marginTop: 12, color: C.muted, fontSize: 10, textAlign: 'right' }}>
              Analysis completed in {result.analysisMs}ms · Confidence: {result.classifierConfidence || result.narrativeConfidence || '—'}
            </div>
          )}
        </>
      )}
    </div>
  );
}

export default function PaymentFlowFixed() {
  const [payments,     setPayments]     = useState([]);
  const [sending,      setSending]      = useState(false);
  const [progress,     setProgress]     = useState(0);
  const [selected,     setSelected]     = useState(null);
  const [rcResult,     setRcResult]     = useState(null);
  const [rcLoading,    setRcLoading]    = useState(false);
  const [rcError,      setRcError]      = useState(null);
  const [searchId,     setSearchId]     = useState('');
  const abortRef = useRef(false);

  const stats = {
    total:    payments.length,
    accepted: payments.filter(p => p.status === 'accepted').length,
    rejected: payments.filter(p => p.status === 'rejected').length,
  };

  async function runRootCause(paymentId) {
    setSelected(paymentId);
    setRcResult(null);
    setRcError(null);
    setRcLoading(true);
    try {
      const token = localStorage.getItem('clearflow_token') || DEV_TOKEN;
      const resp = await fetch(`/mcp/payments/${paymentId}/explain`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      if (!resp.ok) {
        setRcError(`MCP returned HTTP ${resp.status}. Payment may not be indexed yet — wait ~10s and retry.`);
      } else {
        setRcResult(await resp.json());
      }
    } catch (e) {
      setRcError(`Could not reach MCP gateway: ${e.message}`);
    }
    setRcLoading(false);
  }

  async function sendPayments() {
    abortRef.current = false;
    setSending(true);
    setPayments([]);
    setProgress(0);
    setRcResult(null);
    setSelected(null);

    const batch = Array.from({ length: 100 }, (_, i) => ({ seq: i, payload: buildPayload() }));

    for (const { seq, payload } of batch) {
      if (abortRef.current) break;
      try {
        const resp = await fetch('/api/v1/payments', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload),
        });
        const body = await resp.json().catch(() => ({}));
        const id   = body.paymentId || payload.instructionId;
        setPayments(prev => [...prev, {
          seq,
          id,
          status:   resp.status === 202 ? 'accepted' : 'rejected',
          httpCode: resp.status,
          debtor:   payload.debtor.name,
          amount:   payload.amount,
          currency: payload.currency,
          channel:  payload.channel,
        }]);
      } catch {
        setPayments(prev => [...prev, {
          seq,
          id: payload.instructionId,
          status: 'rejected',
          httpCode: 0,
          debtor:   payload.debtor.name,
          amount:   payload.amount,
          currency: payload.currency,
          channel:  payload.channel,
        }]);
      }
      setProgress(seq + 1);
      await new Promise(r => setTimeout(r, 30));
    }

    setSending(false);
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>

      {/* ── Controls + Stats ─────────────────────────────────── */}
      <div style={{ background: C.surface, border: `1px solid ${C.border}`, borderRadius: 8, padding: 16 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 14, flexWrap: 'wrap' }}>
          <h2 style={{ fontSize: 16, fontWeight: 700, color: C.text, margin: 0 }}>
            🚀 100 Live Payments — Real Pipeline Test
          </h2>
          <button
            onClick={sendPayments}
            disabled={sending}
            style={{
              marginLeft: 'auto',
              background: sending ? C.muted + '33' : C.success,
              color: sending ? C.muted : C.bg,
              border: 'none', borderRadius: 6,
              padding: '8px 18px', fontWeight: 700, fontSize: 12,
              cursor: sending ? 'not-allowed' : 'pointer',
            }}
          >
            {sending ? `⏳ Sending… ${progress}/100` : payments.length > 0 ? '↺ Resend 100' : '▶ Send 100 Live Payments'}
          </button>
        </div>

        {/* Progress bar */}
        {sending && (
          <div style={{ background: C.border, borderRadius: 4, height: 4, marginBottom: 12 }}>
            <div style={{ background: C.accent, borderRadius: 4, height: '100%', width: `${progress}%`, transition: 'width 0.1s' }} />
          </div>
        )}

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 10 }}>
          {[
            { label: 'SENT',     value: stats.total,    color: C.accent },
            { label: 'ACCEPTED', value: stats.accepted, color: C.success },
            { label: 'REJECTED', value: stats.rejected, color: C.danger },
            { label: 'ACCEPT %', value: stats.total ? `${((stats.accepted/stats.total)*100).toFixed(0)}%` : '—', color: C.accent },
          ].map(({ label, value, color }) => (
            <div key={label} style={{ background: C.bg, borderRadius: 6, padding: '10px 12px' }}>
              <div style={{ fontSize: 22, fontWeight: 700, color }}>{value}</div>
              <div style={{ fontSize: 10, color: C.muted, marginTop: 2 }}>{label}</div>
            </div>
          ))}
        </div>

        <div style={{ marginTop: 12, fontSize: 11, color: C.muted }}>
          Each payment is a real ISO 20022 pacs.008 POST to the gateway → fraud → validation → AML → routing → settlement → audit.
          After sending, click any ID in the list <em>or</em> enter one below to see real root cause from MCP + Elasticsearch.
        </div>
      </div>

      {/* ── Two-column layout ─────────────────────────────────── */}
      <div style={{ display: 'grid', gridTemplateColumns: '420px 1fr', gap: 16 }}>

        {/* LEFT — payment list */}
        <div style={{ background: C.surface, border: `1px solid ${C.border}`, borderRadius: 8, padding: 12, maxHeight: 700, overflowY: 'auto' }}>
          <div style={{ fontSize: 10, fontWeight: 700, color: C.muted, textTransform: 'uppercase', letterSpacing: 1, marginBottom: 10 }}>
            Payment IDs ({payments.length}/100) — click to analyse
          </div>

          {payments.length === 0 ? (
            <div style={{ color: C.muted, fontSize: 13, textAlign: 'center', padding: '40px 0' }}>
              Click "Send 100 Live Payments" above
            </div>
          ) : payments.map(p => (
            <div
              key={p.id}
              onClick={() => runRootCause(p.id)}
              style={{
                display: 'flex', alignItems: 'center', gap: 8,
                padding: '7px 8px', marginBottom: 3, borderRadius: 6, cursor: 'pointer',
                background: selected === p.id ? C.accent + '18' : 'transparent',
                border: selected === p.id ? `1px solid ${C.accent}55` : '1px solid transparent',
              }}
            >
              <span style={{ color: statusColor(p.status), fontWeight: 700, width: 12, flexShrink: 0 }}>
                {statusIcon(p.status)}
              </span>
              <span style={{ fontFamily: 'monospace', fontSize: 11, color: C.text, flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {p.id}
              </span>
              <span style={{ fontSize: 10, color: C.muted, flexShrink: 0 }}>
                {p.currency} {Number(p.amount).toLocaleString()}
              </span>
            </div>
          ))}
        </div>

        {/* RIGHT — root cause */}
        <div style={{ background: C.surface, border: `2px solid ${selected ? C.accent : C.border}`, borderRadius: 8, padding: 16, maxHeight: 700, overflowY: 'auto' }}>
          <div style={{ fontSize: 10, fontWeight: 700, color: C.muted, textTransform: 'uppercase', letterSpacing: 1, marginBottom: 12 }}>
            🔍 Root Cause Analysis — MCP + Elasticsearch
          </div>

          {/* Search by ID */}
          <div style={{ display: 'flex', gap: 8, marginBottom: 14 }}>
            <input
              value={searchId}
              onChange={e => setSearchId(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && searchId.trim() && runRootCause(searchId.trim())}
              placeholder="Paste any payment UUID and press Enter…"
              style={{
                flex: 1, background: C.bg, border: `1px solid ${C.border}`, borderRadius: 6,
                color: C.text, fontSize: 12, padding: '7px 10px', outline: 'none',
              }}
            />
            <button
              onClick={() => searchId.trim() && runRootCause(searchId.trim())}
              disabled={!searchId.trim() || rcLoading}
              style={{
                background: C.accent, color: C.bg, border: 'none', borderRadius: 6,
                padding: '7px 14px', fontWeight: 700, fontSize: 12,
                cursor: !searchId.trim() || rcLoading ? 'not-allowed' : 'pointer',
                opacity: !searchId.trim() || rcLoading ? 0.5 : 1,
              }}
            >
              Analyse
            </button>
          </div>

          <RootCausePanel result={rcResult} loading={rcLoading} error={rcError} />
        </div>
      </div>
    </div>
  );
}
