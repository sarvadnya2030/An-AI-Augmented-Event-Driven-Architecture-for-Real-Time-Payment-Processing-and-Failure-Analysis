// Hardcoded local demo data for offline/hardcoded frontend behavior
const STATIC_DATA = {
  rails: {
    SEPA_INSTANT: 4821,
    SWIFT_GPI: 3204,
    FEDWIRE: 1897,
    CHIPS: 1102,
    FASTER_PAYMENTS: 967,
    CHAPS: 512,
    SEPA_CT: 388,
  },
  fraud: {
    'LOW (0-30)': 11203,
    'MEDIUM (30-60)': 1847,
    'HIGH (60-80)': 423,
    'CRITICAL (80+)': 61,
  },
  overview: {
    paymentsSubmitted: 17152,
    settled: 16397,
    fraudFlagged: 1284,
    amlBlocked: 113,
    avgLatencyMs: 184,
    activeRails: 7,
  },
  systemic: {
    isSystemic: true,
    severity: 'HIGH',
    affectedServices: ['Fraud Scoring', 'Routing'],
    pattern: 'Velocity spike with suspicious locations',
    suggestedAction: 'Pause high-risk rails and reroute through secondary validation',
  },
  alerts: {
    alertsByService: {
      'Fraud Scoring': 4,
      Routing: 2,
      'AML Compliance': 1,
    },
  },
  explainById: {
    'PAY-DEMO-AML-001': {
      paymentId: 'PAY-DEMO-AML-001',
      overallStatus: 'ALERT',
      causeCategory: 'AML_SANCTIONS',
      primaryCause: 'Sanctions hit on creditor IBAN in watchlist',
      primaryEvidence: 'Sanctions flagged due to {name} match',
      classifierConfidence: '90%',
      failedAtService: 'AML Compliance',
      failedAtStage: 'Sanctions Screening',
      narrativeSummary: 'A sanctions match was detected for this transaction leading to automatic block.',
      immediateAction: 'Review sanctions list and verify payee details before releasing assets.',
      regulatoryNote: 'Requires SAR escalation under 1970 regulations.',
      timeline: {
        paymentId: 'PAY-DEMO-AML-001',
        overallStatus: 'BLOCKED',
        stages: [
          { order: 1, displayName: 'Client Submit', status: 'COMPLETED', keyEvent: 'submitted', durationMs: 42, timestamp: '2026-03-13T10:00:00.123Z' },
          { order: 2, displayName: 'Fraud Scoring', status: 'COMPLETED', keyEvent: 'score=87', durationMs: 190, timestamp: '2026-03-13T10:00:00.315Z' },
          { order: 3, displayName: 'AML Screening', status: 'FAILED', keyEvent: 'sanctions', durationMs: 515, timestamp: '2026-03-13T10:00:00.830Z', keyDetail: 'matched: example-sanctioned-eft-id' },
        ],
        failureStage: 'AML Screening',
        failureService: 'AML Compliance',
        firstEventTimestamp: '2026-03-13T10:00:00.123Z',
        lastEventTimestamp: '2026-03-13T10:00:00.830Z',
        totalLogEvents: 18,
      },
      analysisMs: 617,
    },
    'PAY-DEMO-FRAUD-001': {
      paymentId: 'PAY-DEMO-FRAUD-001',
      overallStatus: 'BLOCKED',
      causeCategory: 'FRAUD_CRITICAL',
      primaryCause: 'Velocity and IP pattern match to known account takeover',
      primaryEvidence: 'Cross-check in 3rd party fraud feed returned >95% likely fraud',
      classifierConfidence: '98%',
      failedAtService: 'Fraud Scoring',
      failedAtStage: 'Fraud Check',
      narrativeSummary: 'Transaction blocked due to multiple red flags consistent with synthetic identity fraud.',
      immediateAction: 'Pause account and contact KYC team.',
      regulatoryNote: 'Potential Money Laundering watchlist candidate.',
      timeline: {
        paymentId: 'PAY-DEMO-FRAUD-001',
        overallStatus: 'BLOCKED',
        stages: [
          { order: 1, displayName: 'Client Submit', status: 'COMPLETED', keyEvent: 'submitted', durationMs: 33, timestamp: '2026-03-13T10:01:11.123Z' },
          { order: 2, displayName: 'Fraud Scoring', status: 'FAILED', keyEvent: 'score=95', durationMs: 298, timestamp: '2026-03-13T10:01:11.421Z', keyDetail: 'ip=192.0.2.123, device=xr-034' },
        ],
        failureStage: 'Fraud Check',
        failureService: 'Fraud Scoring',
        firstEventTimestamp: '2026-03-13T10:01:11.123Z',
        lastEventTimestamp: '2026-03-13T10:01:11.421Z',
        totalLogEvents: 14,
      },
      analysisMs: 342,
    },
    default: {
      paymentId: 'PAY-DEMO-UNKNOWN',
      overallStatus: 'NOT_FOUND',
      causeCategory: 'NO_DATA',
      primaryCause: 'No payment events found for this ID',
      primaryEvidence: 'No ingestion records in the demo dataset',
      classifierConfidence: '0.0',
      failedAtService: null,
      failedAtStage: null,
      narrativeSummary: 'No data is currently available in the offline demo mode.',
      immediateAction: 'Use one of preloaded IDs from the quick chips.',
      regulatoryNote: 'N/A',
      timeline: {
        paymentId: 'PAY-DEMO-UNKNOWN',
        overallStatus: 'NOT_FOUND',
        stages: [],
        failureStage: null,
        failureService: null,
        firstEventTimestamp: null,
        lastEventTimestamp: null,
        totalLogEvents: 0,
      },
      analysisMs: 5,
    },
  },
};

// For user flow, we choose hardcoded results first. This ensures frontend works even if backend is broken.
const wait = (ms) => new Promise((res) => setTimeout(res, ms));

export const mcpApi = {
  getExplain: async (paymentId) => {
    await wait(200);
    return STATIC_DATA.explainById[paymentId] || STATIC_DATA.explainById.default;
  },
  getTimeline: async (paymentId) => {
    await wait(150);
    const explain = STATIC_DATA.explainById[paymentId] || STATIC_DATA.explainById.default;
    return explain.timeline;
  },
  getRisk: async () => ({ score: 68, threshold: 80, status: 'MEDIUM' }),
  getCompliance: async () => ({ jurisdiction: 'EU', risk: 'MEDIUM', sanctions: 2 }),
  getSystemic: async () => {
    await wait(120);
    return STATIC_DATA.systemic;
  },
  getAlerts: async () => {
    await wait(120);
    return STATIC_DATA.alerts;
  },
  getRails: async () => {
    await wait(120);
    return STATIC_DATA.rails;
  },
  getFraud: async () => {
    await wait(120);
    return STATIC_DATA.fraud;
  },
  getOverview: async () => {
    await wait(120);
    return STATIC_DATA.overview;
  },
  chat: async (question, paymentId, history) => {
    await wait(150);
    return {
      response: 'Preloaded demo assistant: This payment has high risk due to sanctions match and will be blocked.',
      paymentId,
      question,
      history,
    };
  },
};
