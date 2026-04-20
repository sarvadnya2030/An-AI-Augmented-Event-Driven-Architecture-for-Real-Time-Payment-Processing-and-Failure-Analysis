package com.clearflow.mcp.tool;

import com.clearflow.mcp.llm.LLMClient;
import com.clearflow.mcp.llm.LLMMessage;
import com.clearflow.mcp.service.CodeGraphService;
import com.clearflow.mcp.service.ElasticsearchLogFetcher;
import com.clearflow.mcp.service.ElasticsearchLogFetcher.LogEntry;
import com.clearflow.mcp.service.PaymentTimelineReconstructor;
import com.clearflow.mcp.service.PaymentTimelineReconstructor.PaymentTimeline;
import com.clearflow.mcp.service.PaymentTimelineReconstructor.PipelineStage;
import com.clearflow.mcp.service.PaymentTimelineReconstructor.StageStatus;
import com.clearflow.mcp.service.PromptTemplates;
import com.clearflow.mcp.service.RootCauseAnalysisService;
import com.clearflow.mcp.service.RootCauseAnalysisService.ExplainResponse;
import com.clearflow.mcp.service.RootCauseClassifier;
import com.clearflow.mcp.service.RootCauseClassifier.ClassificationResult;
import com.clearflow.mcp.service.SystemicFailureDetector;
import com.clearflow.mcp.service.SystemicFailureDetector.SystemicReport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spring AI MCP tool definitions for ClearFlow.
 *
 * These methods are auto-discovered by the Spring AI MCP server via the
 * ToolCallbackProvider bean in McpToolsConfig. Any MCP client (Claude Desktop,
 * Cursor, custom agents) connecting to /mcp/sse will see these tools in
 * tools/list and can invoke them via tools/call.
 *
 * All tools are read-only — no writes to any system.
 */
@Component
public class ClearFlowMcpTools {

    private final RootCauseAnalysisService rootCauseService;
    private final ElasticsearchLogFetcher logFetcher;
    private final PaymentTimelineReconstructor reconstructor;
    private final SystemicFailureDetector systemicDetector;
    private final CodeGraphService codeGraphService;
    private final RootCauseClassifier classifier;
    private final LLMClient llmClient;
    private final ObjectMapper objectMapper;

    public ClearFlowMcpTools(RootCauseAnalysisService rootCauseService,
                              ElasticsearchLogFetcher logFetcher,
                              PaymentTimelineReconstructor reconstructor,
                              SystemicFailureDetector systemicDetector,
                              CodeGraphService codeGraphService,
                              RootCauseClassifier classifier,
                              LLMClient llmClient,
                              ObjectMapper objectMapper) {
        this.rootCauseService = rootCauseService;
        this.logFetcher = logFetcher;
        this.reconstructor = reconstructor;
        this.systemicDetector = systemicDetector;
        this.codeGraphService = codeGraphService;
        this.classifier = classifier;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    // ── Tool 1: Root cause analysis ───────────────────────────────────────────

    @Tool(description = """
            Explain why a payment failed. Fetches all log entries for the payment from
            Elasticsearch, reconstructs the 7-stage pipeline timeline (gateway → validation →
            fraud → AML → routing → settlement → audit), classifies the root cause
            (AML_SANCTIONS / FRAUD_CRITICAL / EMBARGO_BLOCKED / ROUTING_FAILURE / etc.),
            and returns a structured explanation with evidence and recommended action.
            Use this when an operator asks 'why did payment X fail?' or 'what happened to PAY-XXX?'
            """)
    public String explainPayment(String paymentId) {
        ExplainResponse resp = rootCauseService.explain(paymentId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("paymentId",        resp.paymentId());
        result.put("overallStatus",    resp.overallStatus());
        result.put("causeCategory",    String.valueOf(resp.causeCategory()));
        result.put("primaryCause",     String.valueOf(resp.primaryCause()));
        result.put("evidence",         String.valueOf(resp.primaryEvidence()));
        result.put("confidence",       String.valueOf(resp.classifierConfidence()));
        result.put("failedAtStage",    String.valueOf(resp.failedAtStage()));
        result.put("narrativeSummary", String.valueOf(resp.narrativeSummary()));
        result.put("immediateAction",  String.valueOf(resp.immediateAction()));
        result.put("regulatoryNote",   String.valueOf(resp.regulatoryNote()));
        result.put("analysisMs",       resp.analysisMs());
        result.put("totalLogEvents",   resp.timeline().totalLogEvents());
        return toJson(result);
    }

    // ── Tool 2: Payment pipeline timeline ────────────────────────────────────

    @Tool(description = """
            Returns the chronological pipeline timeline for a payment across all 7 services:
            gateway, validation-enrichment, fraud-scoring, aml-compliance, routing-execution,
            settlement, audit. Each stage shows its status (COMPLETED/FAILED/SKIPPED/PENDING),
            the key event logged, timestamp, and duration.
            Use this to trace exactly which stage a payment is at, or to show the full journey
            of a successfully settled payment.
            """)
    public String getPaymentTimeline(String paymentId) {
        List<LogEntry> logs = logFetcher.fetchLogsForPayment(paymentId);
        PaymentTimeline timeline = reconstructor.reconstruct(paymentId, logs);

        StringBuilder sb = new StringBuilder();
        sb.append("Payment: ").append(paymentId)
          .append(" | Status: ").append(timeline.overallStatus())
          .append(" | Log events: ").append(timeline.totalLogEvents()).append("\n\n");

        for (PipelineStage stage : timeline.stages()) {
            String icon = switch (stage.status()) {
                case COMPLETED -> "✅";
                case FAILED    -> "❌";
                case SKIPPED   -> "⏭ ";
                case PENDING   -> "⏳";
            };
            sb.append(String.format("Stage %d %-26s %s %-10s",
                    stage.order(), stage.displayName() + ":", icon, stage.status()));
            if (stage.keyEvent() != null)   sb.append(" [").append(stage.keyEvent()).append("]");
            if (stage.timestamp() != null)  sb.append(" at ").append(stage.timestamp().substring(11, 19));
            if (stage.durationMs() != null) sb.append(" (").append(stage.durationMs()).append("ms)");
            if (stage.status() == StageStatus.FAILED) sb.append(" ← FAILURE HERE");
            sb.append("\n");
            if (stage.keyDetail() != null && stage.status() == StageStatus.FAILED) {
                sb.append("       Evidence: ").append(stage.keyDetail()).append("\n");
            }
        }
        return sb.toString();
    }

    // ── Tool 3: Fraud score ───────────────────────────────────────────────────

    @Tool(description = """
            Returns the fraud score and risk classification for a specific payment.
            Output includes: fraudScore (0.0–1.0), riskBand (LOW/MEDIUM/HIGH/CRITICAL),
            and scoring duration. A CRITICAL score means the payment was blocked by
            the fraud-scoring service before reaching AML screening.
            """)
    public String getFraudScore(String paymentId) {
        List<LogEntry> logs = logFetcher.fetchLogsForPayment(paymentId);
        return logs.stream()
                .filter(e -> "fraud-scoring".equals(e.service()) && e.fraudScore() != null)
                .max((a, b) -> Double.compare(a.fraudScore(), b.fraudScore()))
                .map(e -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("paymentId=").append(paymentId).append("\n");
                    sb.append("fraudScore=").append(String.format("%.4f", e.fraudScore())).append("\n");
                    if (e.riskBand() != null)    sb.append("riskBand=").append(e.riskBand()).append("\n");
                    if (e.durationMs() != null)  sb.append("scoringLatencyMs=").append(e.durationMs()).append("\n");
                    if (e.eventType() != null)   sb.append("event=").append(e.eventType()).append("\n");
                    return sb.toString();
                })
                .orElse("No fraud score data found for paymentId=" + paymentId
                        + ". Payment may not have reached fraud-scoring stage.");
    }

    // ── Tool 4: AML compliance result ─────────────────────────────────────────

    @Tool(description = """
            Returns the AML (Anti-Money Laundering) screening result for a payment.
            Output includes: screeningResult (HIT or CLEAR), matchScore (0.0–1.0 fuzzy
            similarity), matched entity name from the SDN/PEP list (if HIT), and the
            specific list entry that triggered the block. Use this to understand AML
            sanctions decisions and provide compliance evidence.
            """)
    public String getAmlCompliance(String paymentId) {
        List<LogEntry> logs = logFetcher.fetchLogsForPayment(paymentId);
        return logs.stream()
                .filter(e -> "aml-compliance".equals(e.service()))
                .filter(e -> e.screeningResult() != null || e.eventType() != null)
                .findFirst()
                .map(e -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("paymentId=").append(paymentId).append("\n");
                    if (e.screeningResult() != null) sb.append("screeningResult=").append(e.screeningResult()).append("\n");
                    if (e.matchScore() != null)  sb.append("matchScore=").append(String.format("%.4f", e.matchScore())).append("\n");
                    if (e.listHit() != null)     sb.append("matchedEntity=").append(e.listHit()).append("\n");
                    if (e.eventType() != null)   sb.append("event=").append(e.eventType()).append("\n");
                    if (e.durationMs() != null)  sb.append("screeningLatencyMs=").append(e.durationMs()).append("\n");
                    return sb.toString();
                })
                .orElse("No AML compliance data found for paymentId=" + paymentId);
    }

    // ── Tool 5: Systemic failure detection ───────────────────────────────────

    @Tool(description = """
            Detects systemic (infrastructure-wide) payment failures by aggregating
            alert counts across all services in the last N minutes.
            Returns: isSystemic (true/false), affected services, pattern description,
            severity (CRITICAL/HIGH/MEDIUM/LOW), and suggested triage action.
            Use this when an operator suspects a platform-wide incident rather than
            a single payment failure — e.g. 'is there a widespread issue right now?'
            windowMinutes defaults to 15 if not specified.
            """)
    public String detectSystemicFailures(int windowMinutes) {
        SystemicReport report = systemicDetector.detect(windowMinutes <= 0 ? 15 : windowMinutes);
        return toJson(Map.of(
                "isSystemic",       report.isSystemic(),
                "affectedServices", report.affectedServices(),
                "pattern",          String.valueOf(report.pattern()),
                "severity",         String.valueOf(report.severity()),
                "suggestedAction",  String.valueOf(report.suggestedAction()),
                "windowMinutes",    report.windowMinutes(),
                "alertsByService",  report.alertsByService()
        ));
    }

    // ── Tool 6: Recent payment logs ───────────────────────────────────────────

    @Tool(description = """
            Returns the most recent raw log entries for a specific service in the
            last N minutes. Useful for debugging a specific service or checking
            what events a service has been processing recently.
            Valid serviceId values: gateway, validation-enrichment, fraud-scoring,
            aml-compliance, routing-execution, settlement, audit.
            """)
    public String getServiceLogs(String serviceId, int minutes) {
        List<LogEntry> logs = logFetcher.fetchServiceLogs(serviceId, minutes <= 0 ? 15 : minutes);
        if (logs.isEmpty()) return "No logs found for service=" + serviceId + " in last " + minutes + " minutes.";

        StringBuilder sb = new StringBuilder();
        sb.append("Recent logs for ").append(serviceId)
          .append(" (last ").append(minutes).append("m, ").append(logs.size()).append(" entries):\n\n");
        logs.stream().limit(20).forEach(e -> {
            sb.append(e.timestamp() != null ? e.timestamp().substring(11, 23) : "?").append(" ");
            sb.append(String.format("%-5s", e.level() != null ? e.level() : "?")).append(" ");
            if (e.eventType() != null) sb.append("[").append(e.eventType()).append("] ");
            if (e.message() != null) {
                String msg = e.message();
                sb.append(msg.length() > 100 ? msg.substring(0, 100) + "…" : msg);
            }
            sb.append("\n");
        });
        if (logs.size() > 20) sb.append("... and ").append(logs.size() - 20).append(" more entries\n");
        return sb.toString();
    }

    // ── Tool 7: Search payments by criteria ──────────────────────────────────

    @Tool(description = """
            Search payments across the full ELK dataset by any combination of:
              - riskBand: LOW | MEDIUM | HIGH | CRITICAL
              - fraudPattern: ACCOUNT_TAKEOVER | VELOCITY_BURST | ROUND_TRIP |
                              MULE_NETWORK | IMPOSSIBLE_GEOGRAPHY | EMBARGOED_TRANSIT | NONE
              - finalStatus: SETTLED | BLOCKED | REJECTED
              - creditorCountry: ISO-2 country code (e.g. KP, IR, RU for embargoed)
              - windowMinutes: how far back to search (default 43200 = 30 days)
              - limit: max results (default 20)
            Pass null or empty string for any filter you want to skip.
            Use this to answer: 'show me all CRITICAL fraud today', 'find payments to Iran',
            'which VELOCITY_BURST payments were settled?', 'how many payments were blocked?'
            """)
    public String searchPayments(String riskBand, String fraudPattern,
                                 String finalStatus, String creditorCountry,
                                 int windowMinutes, int limit) {
        int win = windowMinutes <= 0 ? 43200 : windowMinutes;
        int lim = limit <= 0 ? 20 : Math.min(limit, 100);
        List<ElasticsearchLogFetcher.LogEntry> results =
                logFetcher.searchPayments(riskBand, fraudPattern, finalStatus, creditorCountry, win, lim);

        if (results.isEmpty()) return "No payments found matching criteria.";

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Found %d payments (window=%dm", results.size(), win));
        if (riskBand    != null && !riskBand.isBlank())    sb.append(" riskBand=").append(riskBand);
        if (fraudPattern!= null && !fraudPattern.isBlank())sb.append(" pattern=").append(fraudPattern);
        if (finalStatus != null && !finalStatus.isBlank()) sb.append(" status=").append(finalStatus);
        if (creditorCountry != null && !creditorCountry.isBlank()) sb.append(" country=").append(creditorCountry);
        sb.append("):\n\n");

        results.forEach(e -> {
            sb.append("  paymentId=").append(e.paymentId() != null ? e.paymentId() : "?");
            if (e.riskBand()        != null) sb.append(" risk=").append(e.riskBand());
            if (e.fraudScore()      != null) sb.append(" score=").append(String.format("%.2f", e.fraudScore()));
            if (e.screeningResult() != null) sb.append(" aml=").append(e.screeningResult());
            if (e.rail()            != null) sb.append(" rail=").append(e.rail());
            if (e.amount()          != null) sb.append(" amount=").append(String.format("%.2f", e.amount()));
            if (e.currency()        != null) sb.append(" ").append(e.currency());
            if (e.creditorCountry() != null) sb.append(" to=").append(e.creditorCountry());
            if (e.timestamp()       != null) sb.append(" @").append(e.timestamp().substring(0, 19));
            sb.append("\n");
        });
        return sb.toString();
    }

    // ── Tool 8: All-service health snapshot ──────────────────────────────────

    @Tool(description = """
            Returns a health snapshot for all 7 ClearFlow microservices:
            gateway, validation-enrichment, fraud-scoring, aml-compliance,
            routing-execution, settlement, audit.
            For each service: total events processed, error/blocked events,
            and HIGH-priority alerts in the given time window.
            Use this to answer: 'are all services healthy?', 'which service has the
            most errors right now?', 'is fraud-scoring having issues?'
            windowMinutes defaults to 60 if not specified.
            """)
    public String getServiceHealth(int windowMinutes) {
        int win = windowMinutes <= 0 ? 60 : windowMinutes;
        var health = logFetcher.serviceHealthSnapshot(win);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Service Health Snapshot (last %dm)\n", win));
        sb.append(String.format("%-28s %10s %10s %10s %s\n",
                "Service", "Events", "Errors", "Alerts", "Status"));
        sb.append("-".repeat(70)).append("\n");

        health.forEach((svc, metrics) -> {
            long total  = metrics.getOrDefault("totalEvents",  0L);
            long errors = metrics.getOrDefault("errorEvents",  0L);
            long alerts = metrics.getOrDefault("highAlerts",   0L);
            double errRate = total > 0 ? errors * 100.0 / total : 0;
            String status = alerts > 0 ? "⚠ ALERT" : (errRate > 5 ? "⚡ DEGRADED" : "✅ OK");
            sb.append(String.format("%-28s %10d %10d %10d  %s (err=%.1f%%)\n",
                    svc, total, errors, alerts, status, errRate));
        });
        return sb.toString();
    }

    // ── Tool 9: Alert queue ───────────────────────────────────────────────────

    @Tool(description = """
            Returns the HIGH-priority alert queue from the clearflow-alerts-* index —
            the queue that a payment operations team would triage.
            Each alert shows: paymentId, service that raised it, event type,
            amount/currency, and the log message with failure detail.
            Use this to answer: 'what alerts need attention right now?',
            'show me open fraud alerts', 'what AML hits came in today?'
            limit defaults to 15 if not specified.
            """)
    public String getAlertQueue(int limit) {
        int lim = limit <= 0 ? 15 : Math.min(limit, 50);
        List<ElasticsearchLogFetcher.LogEntry> alerts = logFetcher.fetchAlertQueue(lim);

        if (alerts.isEmpty()) return "Alert queue is empty — no HIGH alerts found.";

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Alert Queue — %d HIGH-priority events:\n\n", alerts.size()));
        alerts.forEach(e -> {
            sb.append("  [").append(e.alertLevel() != null ? e.alertLevel() : "HIGH").append("] ");
            if (e.eventType()  != null) sb.append(e.eventType()).append(" | ");
            if (e.service()    != null) sb.append("service=").append(e.service()).append(" | ");
            if (e.paymentId()  != null) sb.append("paymentId=").append(e.paymentId()).append(" | ");
            if (e.amount()     != null) sb.append(String.format("amount=%.2f", e.amount())).append(" ");
            if (e.currency()   != null) sb.append(e.currency()).append(" | ");
            if (e.timestamp()  != null) sb.append("@").append(e.timestamp().substring(0, 19));
            sb.append("\n");
            if (e.message() != null) {
                String msg = e.message();
                sb.append("      ").append(msg.length() > 120 ? msg.substring(0, 120) + "…" : msg).append("\n");
            }
            sb.append("\n");
        });
        return sb.toString();
    }

    // ── Tool 10: Real-time ops metrics ───────────────────────────────────────

    @Tool(description = """
            Returns real-time payment operations metrics aggregated from Elasticsearch:
              - paymentsSubmitted, settled, settlementRate
              - amlHits, amlHitRate
              - fraudCritical, fraudRate
              - highAlerts count
              - breakdown by event type, fraud pattern, risk band
            This is the equivalent of an ops dashboard — the numbers a payments engineer
            watches during an incident. Use this for: 'what is our fraud rate right now?',
            'how many payments settled in the last hour?', 'are AML hits elevated?'
            windowMinutes defaults to 60 if not specified.
            """)
    public String getOpsMetrics(int windowMinutes) {
        int win = windowMinutes <= 0 ? 60 : windowMinutes;
        return toJson(logFetcher.paymentMetrics(win));
    }

    // ── Tool 11: Triage by failure type ──────────────────────────────────────

    @Tool(description = """
            Aggregates all payments that hit a specific failure event type,
            showing total count, total blocked volume, breakdown by service,
            creditor country, and fraud pattern.
            Common eventType values:
              AML_SANCTIONS_HIT     — OFAC/SDN sanctions match
              PAYMENT_STATUS_UPDATE — fraud-blocked (CRITICAL risk)
              EMBARGO_CHECK         — embargoed country hit
              FRAUD_SCORE_COMPUTED  — all fraud scoring events
              SETTLEMENT_COMPLETE   — successfully settled
              RAIL_SELECTED         — routed to a payment rail
            Use this to answer: 'how many payments were blocked by AML today?',
            'which countries are generating the most embargo hits?',
            'how many VELOCITY_BURST fraud attempts came from Germany?'
            windowMinutes defaults to 43200 (30 days) if not specified.
            """)
    public String triageFailures(String eventType, int windowMinutes) {
        int win = windowMinutes <= 0 ? 43200 : windowMinutes;
        return toJson(logFetcher.triageByEventType(eventType, win));
    }

    // ── Tool 12: Code-level incident explanation ──────────────────────────────

    @Tool(description = """
            Deep incident analysis that combines ELK log data WITH the Graphify
            codebase knowledge graph to give code-level root cause and fix steps.

            For a given paymentId, this tool:
              1. Fetches all log entries from Elasticsearch across all 7 services
              2. Reconstructs the pipeline timeline and classifies the failure
              3. Looks up which Java classes and methods in the codebase own the
                 failed service (using the pre-built code dependency graph)
              4. Calls the LLM with both incident data AND code context

            Returns JSON with:
              - failedClass: the Java class where the failure originated
              - failedMethod: the specific method to inspect
              - sourceFile: exact path in the repo (e.g. fraud-scoring/src/main/java/...)
              - rootCause: 1-2 sentence root cause
              - fixSteps: ordered list of concrete engineering steps to resolve it
              - regulatoryRisk: any compliance/regulatory implications
              - codeGraphCoverage: how many classes are indexed per module

            Use this instead of explainPayment() when you need code-level guidance,
            e.g. 'which file caused the AML false positive and how do I fix it?'
            or 'where in the code is the circuit breaker misconfigured?'
            """)
    public String explainIncidentWithCode(String paymentId) {
        long start = System.currentTimeMillis();

        // 1. Fetch logs and reconstruct timeline
        List<LogEntry> logs = logFetcher.fetchLogsForPayment(paymentId);
        if (logs.isEmpty()) {
            return "No log data found for paymentId=" + paymentId
                    + ". Ensure the payment was processed and ES is reachable.";
        }

        PaymentTimeline timeline = reconstructor.reconstruct(paymentId, logs);
        ClassificationResult classification = classifier.classify(timeline);

        // 2. Determine failed service for code context lookup
        String failedService = classification.failureStage() != null
                ? mapStageToService(classification.failureStage())
                : null;
        String failureType = classification.primaryCause() != null
                ? classification.primaryCause()
                : "";

        // 3. Get code context from the graph
        String codeContext = (failedService != null)
                ? codeGraphService.getCodeContext(failedService, failureType)
                : codeGraphService.getCodeContext("", failureType);

        // 4. Call LLM with combined incident + code context
        String systemPrompt = PromptTemplates.incidentWithCodeSystem();
        String userPrompt   = PromptTemplates.incidentWithCodeUser(timeline, classification, codeContext);

        String llmResponse;
        try {
            llmResponse = llmClient.chat(List.of(
                    new LLMMessage("system", systemPrompt),
                    new LLMMessage("user",   userPrompt)
            ));
        } catch (Exception e) {
            llmResponse = "{\"error\": \"LLM unavailable: " + e.getMessage() + "\"}";
        }

        // 5. Build combined response
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("paymentId",         paymentId);
        result.put("overallStatus",     timeline.overallStatus());
        result.put("causeCategory",     String.valueOf(classification.category()));
        result.put("failedAtStage",     String.valueOf(classification.failureStage()));
        result.put("failedService",     failedService != null ? failedService : "unknown");
        result.put("codeGraphLoaded",   codeGraphService.isLoaded());
        result.put("codeGraphCoverage", codeGraphService.getCoverage());
        result.put("llmAnalysis",       parseLlmJson(llmResponse));
        result.put("analysisMs",        System.currentTimeMillis() - start);
        return toJson(result);
    }

    // ── Tool 13: Broker cascade trace ─────────────────────────────────────────

    @Tool(description = """
            Traces a cascading failure across all 3 message brokers (Kafka, ActiveMQ Artemis,
            Solace) for a given payment. Combines:
              - ES cascade event logs (CIRCUIT_BREAKER / SAGA_COMPENSATION / RETRY_STORM /
                DOWNSTREAM_STARVATION events ingested during the 100k payment simulation)
              - Broker topology graph (which class produces/consumes which topic/queue)
              - Circuit breaker config (Resilience4j CB names, fallback chains)
              - Saga compensation flow (SagaCompensationRoute choreography steps)
              - DLQ config (max retries, exponential backoff, dead letter destination)
              - Full pipeline diagram (gateway → validation → AML → routing → settlement)
              - LLM analysis of propagation path

            Returns:
              - propagationPath: exact service→broker→service hops
              - rootService + rootClass: where the cascade started
              - brokerBottleneck: which topic/queue backed up
              - fixSteps: ordered engineering steps to resolve
              - preventionConfig: config tuning to prevent recurrence
              - Plus a human-readable cascade timeline and full pipeline topology

            Use this when: 'how did payment X cascade?', 'which broker was the bottleneck?',
            'trace the saga compensation for PAY-XYZ', 'why did the DLQ fill up?'
            """)
    public String traceBrokerCascade(String paymentId) {
        long start = System.currentTimeMillis();

        // 1. Fetch all logs including cascade events for this payment
        List<LogEntry> logs = logFetcher.fetchLogsForPayment(paymentId);
        if (logs.isEmpty()) {
            return "No log data found for paymentId=" + paymentId;
        }

        // 2. Filter to cascade events only
        Set<String> CASCADE_EVENTS = Set.of(
                "SERVICE_TIMEOUT", "CIRCUIT_BREAKER_OPEN", "CIRCUIT_BREAKER_FALLBACK",
                "PAYMENT_QUEUED", "PAYMENT_RETRY", "DEAD_LETTER_QUEUE", "QUEUE_OVERFLOW_WARNING",
                "SETTLEMENT_TIMEOUT", "SAGA_COMPENSATION_STARTED", "ROUTING_RESERVATION_RELEASED",
                "AML_HOLD_RELEASED", "FRAUD_RESERVATION_VOIDED", "PAYMENT_COMPENSATED",
                "DB_CONNECTION_POOL_EXHAUSTED", "SETTLEMENT_ACK_TIMEOUT", "PAYMENT_STUCK",
                "PAYMENT_STATUS_UNKNOWN", "BACKPRESSURE_APPLIED", "EXTERNAL_LOOKUP_TIMEOUT"
        );

        List<LogEntry> cascadeEvents = logs.stream()
                .filter(e -> e.eventType() != null && CASCADE_EVENTS.contains(e.eventType()))
                .toList();

        // 3. Determine cascade type from event patterns
        String cascadeType = detectCascadeType(cascadeEvents);

        // 4. Determine primary service involved
        String primaryService = cascadeEvents.stream()
                .filter(e -> e.service() != null)
                .map(LogEntry::service)
                .findFirst()
                .orElse("gateway");

        // 5. Get broker context + pipeline topology
        String brokerContext = codeGraphService.getBrokerContext(primaryService, cascadeType);
        String pipeline      = codeGraphService.getFullPipelineTopology();

        // 6. Build cascade event summary
        String eventSummary = buildCascadeEventSummary(cascadeEvents);

        // 7. LLM analysis
        String llmResponse = "(LLM unavailable)";
        try {
            llmResponse = llmClient.chat(List.of(
                    new LLMMessage("system", PromptTemplates.brokerCascadeSystem()),
                    new LLMMessage("user",   PromptTemplates.brokerCascadeUser(
                            paymentId, cascadeType, eventSummary, brokerContext, pipeline))
            ));
        } catch (Exception e) {
            llmResponse = "{\"error\": \"" + e.getMessage() + "\"}";
        }

        // 8. Build full human-readable response
        StringBuilder sb = new StringBuilder();
        sb.append("═".repeat(70)).append("\n");
        sb.append("CASCADE TRACE  paymentId=").append(paymentId).append("\n");
        sb.append("cascadeType=").append(cascadeType)
          .append("  service=").append(primaryService)
          .append("  cascadeEvents=").append(cascadeEvents.size())
          .append("  totalLogs=").append(logs.size()).append("\n");
        sb.append("═".repeat(70)).append("\n\n");

        sb.append("TIMELINE:\n").append(eventSummary).append("\n");
        sb.append(pipeline).append("\n");
        sb.append("BROKER CONTEXT:\n").append(brokerContext).append("\n");
        sb.append("LLM ANALYSIS:\n").append(parseLlmJson(llmResponse)).append("\n");
        sb.append("\n[analysisMs=").append(System.currentTimeMillis() - start)
          .append("  topologyLoaded=").append(codeGraphService.isTopologyLoaded()).append("]");

        return sb.toString();
    }

    private String detectCascadeType(List<LogEntry> cascadeEvents) {
        if (cascadeEvents.isEmpty()) return "UNKNOWN";
        boolean hasCB   = cascadeEvents.stream().anyMatch(e -> "CIRCUIT_BREAKER_OPEN".equals(e.eventType()));
        boolean hasSaga = cascadeEvents.stream().anyMatch(e -> "SAGA_COMPENSATION_STARTED".equals(e.eventType()));
        boolean hasDLQ  = cascadeEvents.stream().anyMatch(e -> "DEAD_LETTER_QUEUE".equals(e.eventType())
                                                            || "QUEUE_OVERFLOW_WARNING".equals(e.eventType()));
        boolean hasPool = cascadeEvents.stream().anyMatch(e -> "DB_CONNECTION_POOL_EXHAUSTED".equals(e.eventType()));
        if (hasCB)   return "CIRCUIT_BREAKER";
        if (hasSaga) return "SAGA_COMPENSATION";
        if (hasPool) return "DOWNSTREAM_STARVATION";
        if (hasDLQ)  return "RETRY_STORM";
        return "UNKNOWN";
    }

    private String buildCascadeEventSummary(List<LogEntry> cascadeEvents) {
        if (cascadeEvents.isEmpty()) return "  (no cascade events found for this payment)\n";
        StringBuilder sb = new StringBuilder();
        cascadeEvents.forEach(e -> {
            String ts = e.timestamp() != null ? e.timestamp().substring(11, 23) : "?";
            sb.append(String.format("  %s [%-30s] %s\n",
                    ts,
                    (e.service() != null ? e.service() : "?"),
                    (e.eventType() != null ? e.eventType() : "?")));
        });
        return sb.toString();
    }

    private String mapStageToService(Object stage) {
        if (stage == null) return null;
        String s = stage.toString().toLowerCase();
        if (s.contains("gateway"))    return "gateway";
        if (s.contains("valid"))      return "validation-enrichment";
        if (s.contains("fraud"))      return "fraud-scoring";
        if (s.contains("aml"))        return "aml-compliance";
        if (s.contains("rout"))       return "routing-execution";
        if (s.contains("settl"))      return "settlement";
        if (s.contains("audit"))      return "audit";
        return s.replace("_", "-");
    }

    private Object parseLlmJson(String raw) {
        if (raw == null || raw.isBlank()) return Map.of("raw", "");
        try {
            // Strip markdown code fences if present
            String cleaned = raw.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
            }
            return objectMapper.readValue(cleaned, Object.class);
        } catch (Exception e) {
            return Map.of("raw", raw);
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private String toJson(Object obj) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return obj.toString();
        }
    }
}
