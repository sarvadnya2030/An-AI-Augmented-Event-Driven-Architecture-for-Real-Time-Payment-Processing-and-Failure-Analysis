package com.clearflow.mcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Production-ready real-time cascade failure detection using ELK logs.
 *
 * Detects failure propagation across the payment pipeline:
 * - Monitors for error/FAILED events across all services
 * - Correlates failures by timestamp and correlationId
 * - Reconstructs cascade chains (root cause → affected services)
 * - Classifies cascade type (broker outage, liquidity exhaustion, etc.)
 * - Generates actionable alerts for operators
 *
 * Thread-safe with in-memory cascade cache for high-frequency queries.
 */
@Service
public class CascadeFailureDetector {

    private static final Logger log = LoggerFactory.getLogger(CascadeFailureDetector.class);

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final Map<String, CascadePattern> recentCascades = new ConcurrentHashMap<>();

    @Value("${elasticsearch.host:http://localhost:9200}")
    private String esHost;

    private static final int CASCADE_CACHE_SIZE = 1000;
    private static final long CASCADE_TTL_MS = 5 * 60 * 1000;  // 5 minutes
    private static final long TIME_WINDOW_MS = 2000;  // 2 second window for cascade correlation
    private static final int MIN_SERVICES_FOR_CASCADE = 2;
    private static final double PROPAGATION_THRESHOLD_MS = 100;  // Services failing > 100ms apart likely not a cascade

    public CascadeFailureDetector(ObjectMapper mapper) {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        this.mapper = mapper;
        this.esHost = "http://localhost:9200";
    }

    public record FailureEvent(
        String paymentId,
        String correlationId,
        String service,
        String event,
        Instant timestamp,
        String failureReason,
        int stageNumber
    ) {}

    public record CascadePattern(
        String id,  // Unique cascade ID
        String rootCauseService,
        String rootCauseEvent,
        Instant rootCauseTime,
        List<FailureEvent> propagationChain,
        int affectedPayments,
        double propagationSpeed,  // ms/stage
        String cascadeType,
        long detectedAt,
        String severity  // CRITICAL, HIGH, MEDIUM
    ) {}

    /**
     * Detect active cascades in last N minutes (production-grade implementation).
     */
    public List<CascadePattern> detectActiveCascades(int lastMinutes) throws Exception {
        long now = System.currentTimeMillis();
        long sinceTime = now - (lastMinutes * 60 * 1000L);

        try {
            String query = String.format("""
                {
                  "size": 10000,
                  "query": {
                    "bool": {
                      "must": [
                        {"range": {"@timestamp": {"gte": %d, "lte": %d}}},
                        {"terms": {"level": ["ERROR", "FAILED"]}}
                      ]
                    }
                  }
                }
                """, sinceTime, now);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(esHost + "/clearflow-*/_search"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(query))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("ES query returned status {}", response.statusCode());
                return new ArrayList<>();
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode hits = root.path("hits").path("hits");

            List<FailureEvent> allFailures = new ArrayList<>();

            for (JsonNode hit : hits) {
                FailureEvent event = parseFailureEventFromJson(hit);
                if (event != null) {
                    allFailures.add(event);
                }
            }

            log.info("Found {} failure events in last {} minutes", allFailures.size(), lastMinutes);

            Map<String, List<FailureEvent>> failuresByCorrelationId = allFailures.stream()
                .collect(Collectors.groupingByConcurrent(FailureEvent::correlationId));

            List<CascadePattern> cascades = new ArrayList<>();

            for (List<FailureEvent> paymentFailures : failuresByCorrelationId.values()) {
                if (paymentFailures.size() >= MIN_SERVICES_FOR_CASCADE) {
                    CascadePattern cascade = reconstructCascade(paymentFailures);
                    if (cascade != null) {
                        cascades.add(cascade);
                        cacheRecentCascade(cascade);
                    }
                }
            }

            log.info("Detected {} cascade patterns", cascades.size());
            return cascades;

        } catch (Exception ex) {
            log.error("Failed to query Elasticsearch for cascade detection", ex);
            return new ArrayList<>();
        }
    }

    /**
     * Parse a JsonNode from Elasticsearch into a FailureEvent.
     */
    private FailureEvent parseFailureEventFromJson(JsonNode hit) {
        try {
            JsonNode source = hit.path("_source");

            String paymentId = source.path("paymentId").asText("");
            String correlationId = source.path("correlationId").asText("");
            String service = source.path("service").asText("");
            String level = source.path("level").asText("");
            String message = source.path("message").asText("");
            String timestamp = source.path("@timestamp").asText("");

            if (correlationId.isEmpty() || service.isEmpty()) {
                return null;
            }

            Instant ts = timestamp.isEmpty() ? Instant.now() : Instant.parse(timestamp);
            int stageNumber = inferStageNumber(service);

            return new FailureEvent(
                paymentId,
                correlationId,
                service,
                level,
                ts,
                message,
                stageNumber
            );
        } catch (Exception ex) {
            log.debug("Failed to parse failure event", ex);
            return null;
        }
    }


    private int inferStageNumber(String service) {
        return switch (service.toLowerCase()) {
            case "gateway" -> 0;
            case "fraud-scoring" -> 1;
            case "validation-enrichment" -> 2;
            case "aml-compliance" -> 3;
            case "routing-execution" -> 4;
            case "settlement" -> 5;
            case "audit" -> 6;
            default -> 7;
        };
    }

    /**
     * Reconstruct cascade: sort by timestamp to identify root cause and propagation chain.
     */
    private CascadePattern reconstructCascade(List<FailureEvent> failureEvents) {
        if (failureEvents.isEmpty()) return null;

        failureEvents.sort(Comparator.comparing(FailureEvent::timestamp));

        FailureEvent rootCause = failureEvents.get(0);
        List<FailureEvent> propagation = new ArrayList<>(failureEvents);

        long startTime = rootCause.timestamp().toEpochMilli();
        long endTime = propagation.get(propagation.size() - 1).timestamp().toEpochMilli();
        long totalDuration = endTime - startTime;

        double propagationSpeed = propagation.size() > 1 ?
            (double) totalDuration / (propagation.size() - 1) : 0;

        // Only classify as cascade if propagation is reasonable (not too fast, not too slow)
        if (propagationSpeed > 10000) {  // Failures > 10s apart likely unrelated
            return null;
        }

        String cascadeType = classifyCascadeType(rootCause);
        String severity = computeSeverity(propagation, propagationSpeed);

        String cascadeId = UUID.randomUUID().toString();

        CascadePattern cascade = new CascadePattern(
            cascadeId,
            rootCause.service(),
            rootCause.event(),
            rootCause.timestamp(),
            propagation,
            propagation.size(),
            propagationSpeed,
            cascadeType,
            System.currentTimeMillis(),
            severity
        );

        log.warn("Cascade detected: {} {} ({} services in {:.0f}ms, speed={:.1f}ms/stage)",
            cascadeId, cascadeType, propagation.size(), totalDuration, propagationSpeed);

        return cascade;
    }

    private String classifyCascadeType(FailureEvent rootCause) {
        String reason = rootCause.failureReason().toLowerCase();
        String service = rootCause.service().toLowerCase();

        if (reason.contains("broker") || reason.contains("kafka") || reason.contains("activemq")) {
            return "BROKER_OUTAGE";
        } else if (reason.contains("liquidity") || reason.contains("nostro") || reason.contains("fund") ||
                   reason.contains("insufficient")) {
            return "LIQUIDITY_EXHAUSTED";
        } else if (reason.contains("queue") || reason.contains("backpressure") ||
                   reason.contains("timeout") || reason.contains("pool exhausted")) {
            return "QUEUE_BACKPRESSURE";
        } else if (reason.contains("circuit") || reason.contains("breaker")) {
            return "CIRCUIT_BREAKER_OPEN";
        } else if (service.contains("aml")) {
            return "AML_REJECT_SPIKE";
        } else if (service.contains("routing")) {
            return "ROUTING_FAILURE";
        } else {
            return "UNKNOWN";
        }
    }

    private String computeSeverity(List<FailureEvent> propagation, double propagationSpeed) {
        if (propagation.size() >= 5 && propagationSpeed < 200) {
            return "CRITICAL";  // Many services failing quickly
        } else if (propagation.size() >= 3) {
            return "HIGH";
        } else {
            return "MEDIUM";
        }
    }

    private void cacheRecentCascade(CascadePattern cascade) {
        if (recentCascades.size() >= CASCADE_CACHE_SIZE) {
            recentCascades.entrySet().stream()
                .filter(e -> System.currentTimeMillis() - e.getValue().detectedAt() > CASCADE_TTL_MS)
                .map(Map.Entry::getKey)
                .forEach(recentCascades::remove);
        }
        recentCascades.put(cascade.id(), cascade);
    }

    /**
     * Get recent cascades from cache (for fast access without ES query).
     */
    public List<CascadePattern> getRecentCascades() {
        return recentCascades.values().stream()
            .filter(c -> System.currentTimeMillis() - c.detectedAt() < CASCADE_TTL_MS)
            .sorted(Comparator.comparing(CascadePattern::detectedAt).reversed())
            .collect(Collectors.toList());
    }

    /**
     * Generate alert message for a cascade.
     */
    public String generateAlert(CascadePattern cascade) {
        return String.format(
            "🚨 CASCADE FAILURE DETECTED [%s]\n" +
            "ID: %s\n" +
            "Root Cause: %s (%s)\n" +
            "Type: %s | Severity: %s\n" +
            "Timeline: %s\n" +
            "Affected Services: %d\n" +
            "Propagation Speed: %.1f ms/stage\n" +
            "Chain: %s\n" +
            "Action: Review logs for service %s first, then trace downstream failures",
            new java.text.SimpleDateFormat("HH:mm:ss").format(new Date(cascade.detectedAt())),
            cascade.id(),
            cascade.rootCauseService(),
            cascade.rootCauseEvent(),
            cascade.cascadeType(),
            cascade.severity(),
            cascade.rootCauseTime(),
            cascade.propagationChain().size(),
            cascade.propagationSpeed(),
            cascade.propagationChain().stream()
                .map(e -> String.format("%s[%d]", e.service(), e.stageNumber()))
                .collect(Collectors.joining(" → ")),
            cascade.rootCauseService()
        );
    }
}
