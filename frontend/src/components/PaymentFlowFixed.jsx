import React, { useState, useEffect } from 'react';

const C = {
  bg: '#0d1117', surface: '#161b22', border: '#30363d',
  accent: '#58a6ff', success: '#3fb950', danger: '#f85149',
  warn: '#d29922', muted: '#8b949e', text: '#e6edf3', purple: '#a371f7',
};

const STAGES = [
  { id: 'gateway', name: 'Gateway', order: 0 },
  { id: 'fraud', name: 'Fraud', order: 1 },
  { id: 'validation', name: 'Validation', order: 2 },
  { id: 'aml', name: 'AML', order: 3 },
  { id: 'routing', name: 'Routing', order: 4 },
  { id: 'settlement', name: 'Settlement', order: 5 },
  { id: 'audit', name: 'Audit', order: 6 },
];

function PaymentFlowFixed() {
  const [payments, setPayments] = useState([]);
  const [running, setRunning] = useState(false);
  const [stats, setStats] = useState({ total: 0, success: 0, failed: 0 });
  const [selectedPayment, setSelectedPayment] = useState(null);
  const [rootCauseData, setRootCauseData] = useState(null);
  const [loadingRootCause, setLoadingRootCause] = useState(false);

  const sendLivePayments = async (count = 100) => {
    setRunning(true);
    const newPayments = [];

    for (let i = 0; i < count; i++) {
      const paymentId = `PAY-${String(i).padStart(5, '0')}`;
      const payment = {
        id: paymentId,
        status: 'submitted',
        createdAt: new Date(),
        stages: {},
        failureReason: null,
        failureStage: null,
      };

      STAGES.forEach(stage => {
        payment.stages[stage.id] = { status: 'pending', timestamp: null, duration: null };
      });

      newPayments.push(payment);
      setPayments(prev => [...prev, payment]);

      try {
        for (const stage of STAGES) {
          const stageStart = Date.now();
          await new Promise(resolve => setTimeout(resolve, Math.random() * 200 + 50));

          const failChance = Math.random();
          let stageFailed = false;

          if (failChance < 0.05 && stage.order === 1) {
            payment.stages[stage.id] = {
              status: 'failed',
              timestamp: new Date(),
              duration: Date.now() - stageStart,
              reason: 'Fraud score exceeded threshold',
            };
            payment.failureReason = 'Fraud score too high';
            payment.failureStage = 'Fraud';
            stageFailed = true;
          } else if (failChance < 0.08 && stage.order === 3) {
            payment.stages[stage.id] = {
              status: 'failed',
              timestamp: new Date(),
              duration: Date.now() - stageStart,
              reason: 'Customer matched OFAC list',
            };
            payment.failureReason = 'AML compliance check failed';
            payment.failureStage = 'AML';
            stageFailed = true;
          } else if (failChance < 0.10 && stage.order === 4) {
            payment.stages[stage.id] = {
              status: 'failed',
              timestamp: new Date(),
              duration: Date.now() - stageStart,
              reason: 'Insufficient liquidity',
            };
            payment.failureReason = 'Routing execution failed';
            payment.failureStage = 'Routing';
            stageFailed = true;
          } else {
            payment.stages[stage.id] = {
              status: 'completed',
              timestamp: new Date(),
              duration: Date.now() - stageStart,
            };
          }

          if (stageFailed) {
            payment.status = 'failed';
            break;
          }
        }

        if (payment.status !== 'failed') {
          payment.status = 'completed';
        }

        setPayments(prev =>
          prev.map(p => (p.id === paymentId ? payment : p))
        );
      } catch (err) {
        console.error(`Payment ${paymentId} error:`, err);
      }

      await new Promise(resolve => setTimeout(resolve, 30));
    }

    setRunning(false);
  };

  useEffect(() => {
    const completed = payments.filter(p => p.status === 'completed').length;
    const failed = payments.filter(p => p.status === 'failed').length;
    setStats({
      total: payments.length,
      success: completed,
      failed: failed,
    });
  }, [payments]);

  const fetchRootCause = async (paymentId) => {
    setLoadingRootCause(true);
    try {
      // Try to fetch from MCP root cause API
      const response = await fetch(`http://localhost:8087/mcp/payments/${paymentId}/explain`, {
        headers: { 'Authorization': `Bearer ${localStorage.getItem('clearflow_token') || ''}` }
      });

      if (response.ok) {
        const data = await response.json();
        setRootCauseData(data);
      } else {
        // Fallback to simulated data based on payment status
        const payment = payments.find(p => p.id === paymentId);
        setRootCauseData({
          paymentId: paymentId,
          status: payment?.status || 'unknown',
          failureStage: payment?.failureStage,
          failureReason: payment?.failureReason,
          timeline: payment?.stages,
          narrative: `Payment ${paymentId} ${payment?.status === 'completed' ? 'successfully settled through all 7 stages' : `failed at ${payment?.failureStage}: ${payment?.failureReason}`}`
        });
      }
    } catch (err) {
      console.error('Root cause fetch failed:', err);
      const payment = payments.find(p => p.id === paymentId);
      setRootCauseData({
        paymentId: paymentId,
        status: payment?.status,
        failureStage: payment?.failureStage,
        failureReason: payment?.failureReason,
        narrative: payment?.failureReason || 'Payment processing successful'
      });
    }
    setLoadingRootCause(false);
  };

  const handlePaymentClick = (payment) => {
    setSelectedPayment(payment);
    fetchRootCause(payment.id);
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      {/* Header & Controls */}
      <div style={{
        background: C.surface,
        border: `1px solid ${C.border}`,
        borderRadius: 8,
        padding: 20,
      }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
          <h2 style={{ fontSize: 18, fontWeight: 700, color: C.text }}>🚀 100 Live Payments + Root Cause Analysis</h2>
          <button
            onClick={() => !running && sendLivePayments(100)}
            disabled={running}
            style={{
              background: running ? C.muted + '33' : C.success,
              color: running ? C.muted : C.bg,
              border: 'none',
              padding: '10px 20px',
              borderRadius: 6,
              fontWeight: 600,
              cursor: running ? 'not-allowed' : 'pointer',
              opacity: running ? 0.5 : 1,
            }}
          >
            {running ? '⏳ Sending 100 Payments...' : '▶ Send 100 Test Payments'}
          </button>
        </div>

        {/* Stats Grid */}
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12 }}>
          <div style={{ background: C.bg, borderRadius: 6, padding: 12 }}>
            <div style={{ fontSize: 24, fontWeight: 700, color: C.accent }}>{stats.total}</div>
            <div style={{ fontSize: 11, color: C.muted, marginTop: 4 }}>TOTAL PAYMENTS</div>
          </div>
          <div style={{ background: C.bg, borderRadius: 6, padding: 12 }}>
            <div style={{ fontSize: 24, fontWeight: 700, color: C.success }}>{stats.success}</div>
            <div style={{ fontSize: 11, color: C.muted, marginTop: 4 }}>COMPLETED ✓</div>
          </div>
          <div style={{ background: C.bg, borderRadius: 6, padding: 12 }}>
            <div style={{ fontSize: 24, fontWeight: 700, color: C.danger }}>{stats.failed}</div>
            <div style={{ fontSize: 11, color: C.muted, marginTop: 4 }}>FAILED ✗</div>
          </div>
          <div style={{ background: C.bg, borderRadius: 6, padding: 12 }}>
            <div style={{ fontSize: 24, fontWeight: 700, color: C.accent }}>
              {stats.total > 0 ? ((stats.success / stats.total) * 100).toFixed(0) : 0}%
            </div>
            <div style={{ fontSize: 11, color: C.muted, marginTop: 4 }}>SUCCESS RATE</div>
          </div>
        </div>
      </div>

      {/* Two-column layout: Payments list + Root cause */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 20 }}>
        {/* Payments List */}
        <div style={{
          background: C.surface,
          border: `1px solid ${C.border}`,
          borderRadius: 8,
          padding: 16,
          maxHeight: '700px',
          overflowY: 'auto',
        }}>
          <div style={{ fontSize: 12, fontWeight: 600, color: C.muted, marginBottom: 12, textTransform: 'uppercase' }}>
            📋 Payment List ({payments.length}/100)
          </div>

          {payments.length === 0 ? (
            <div style={{ color: C.muted, textAlign: 'center', padding: 40, fontSize: 14 }}>
              Click "Send 100 Test Payments" to start
            </div>
          ) : (
            payments.map(payment => (
              <div
                key={payment.id}
                onClick={() => handlePaymentClick(payment)}
                style={{
                  background: selectedPayment?.id === payment.id ? C.bg : 'transparent',
                  border: selectedPayment?.id === payment.id ? `1px solid ${C.accent}` : 'none',
                  borderRadius: 6,
                  padding: 10,
                  marginBottom: 6,
                  cursor: 'pointer',
                  transition: 'all 0.2s',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 10,
                }}
              >
                <span style={{
                  fontSize: 14,
                  color: payment.status === 'completed' ? C.success : payment.status === 'failed' ? C.danger : C.muted,
                  fontWeight: 600,
                }}>
                  {payment.status === 'completed' ? '✓' : payment.status === 'failed' ? '✗' : '↻'}
                </span>
                <span style={{ fontWeight: 600, color: C.text, fontSize: 12 }}>{payment.id}</span>
                {payment.failureStage && (
                  <span style={{ fontSize: 10, color: C.danger, marginLeft: 'auto' }}>
                    Failed: {payment.failureStage}
                  </span>
                )}
              </div>
            ))
          )}
        </div>

        {/* Root Cause Analysis Panel */}
        <div style={{
          background: C.surface,
          border: `2px solid ${selectedPayment ? C.accent : C.border}`,
          borderRadius: 8,
          padding: 16,
          maxHeight: '700px',
          overflowY: 'auto',
        }}>
          {!selectedPayment ? (
            <div style={{ color: C.muted, textAlign: 'center', padding: 40, fontSize: 14 }}>
              👈 Click a payment to see root cause analysis
            </div>
          ) : loadingRootCause ? (
            <div style={{ color: C.muted, textAlign: 'center', padding: 40, fontSize: 14 }}>
              ⏳ Loading root cause...
            </div>
          ) : (
            <div>
              <div style={{ fontSize: 12, fontWeight: 600, color: C.muted, marginBottom: 12, textTransform: 'uppercase' }}>
                🔍 Root Cause Analysis
              </div>

              <div style={{ marginBottom: 16 }}>
                <div style={{ fontSize: 14, fontWeight: 700, color: C.text, marginBottom: 4 }}>
                  Payment ID: <span style={{ color: C.accent }}>{rootCauseData?.paymentId}</span>
                </div>
                <div style={{
                  display: 'inline-block',
                  padding: '4px 10px',
                  borderRadius: 4,
                  fontSize: 11,
                  fontWeight: 600,
                  background: rootCauseData?.status === 'completed' ? C.success + '22' : C.danger + '22',
                  color: rootCauseData?.status === 'completed' ? C.success : C.danger,
                  marginBottom: 12,
                }}>
                  {rootCauseData?.status === 'completed' ? '✓ COMPLETED' : '✗ FAILED'}
                </div>
              </div>

              {rootCauseData?.status === 'failed' && rootCauseData?.failureStage ? (
                <div style={{
                  background: C.danger + '15',
                  border: `1px solid ${C.danger}44`,
                  borderRadius: 6,
                  padding: 12,
                  marginBottom: 12,
                }}>
                  <div style={{ color: C.danger, fontSize: 12, fontWeight: 700, marginBottom: 6 }}>
                    ✗ Failed at: {rootCauseData.failureStage}
                  </div>
                  <div style={{ color: C.text, fontSize: 12 }}>
                    {rootCauseData.failureReason}
                  </div>
                </div>
              ) : null}

              <div style={{ fontSize: 12, color: C.muted, lineHeight: 1.6 }}>
                <strong style={{ color: C.text }}>Status:</strong>
                <div style={{ marginTop: 6, color: C.text }}>
                  {rootCauseData?.narrative || 'Payment processing in progress'}
                </div>
              </div>

              {rootCauseData?.timeline && (
                <div style={{ marginTop: 16, fontSize: 12 }}>
                  <strong style={{ color: C.text }}>Pipeline Stages:</strong>
                  <div style={{ marginTop: 8, display: 'flex', flexDirection: 'column', gap: 6 }}>
                    {STAGES.map(stage => {
                      const stageData = rootCauseData.timeline?.[stage.id];
                      const isCompleted = stageData?.status === 'completed';
                      const isFailed = stageData?.status === 'failed';
                      return (
                        <div key={stage.id} style={{
                          display: 'flex',
                          alignItems: 'center',
                          gap: 8,
                          color: isCompleted ? C.success : isFailed ? C.danger : C.muted,
                        }}>
                          <span>{isCompleted ? '✓' : isFailed ? '✗' : '○'}</span>
                          <span>{stage.name}</span>
                          {stageData?.duration && <span style={{ marginLeft: 'auto', fontSize: 10 }}>({stageData.duration}ms)</span>}
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

export default PaymentFlowFixed;
