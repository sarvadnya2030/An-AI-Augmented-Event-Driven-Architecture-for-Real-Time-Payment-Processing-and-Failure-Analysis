package com.clearflow.mcp.controller;

import com.clearflow.mcp.service.CascadeFailureDetector;
import com.clearflow.mcp.service.CascadeFailureDetector.CascadePattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Production REST API for cascade failure detection.
 * Provides HTTP endpoints + Server-Sent Events (SSE) streaming.
 */
@RestController
@RequestMapping("/mcp/cascade")
public class CascadeDetectionController {

    private static final Logger log = LoggerFactory.getLogger(CascadeDetectionController.class);

    private final CascadeFailureDetector detector;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public CascadeDetectionController(CascadeFailureDetector detector) {
        this.detector = detector;
    }

    /**
     * Detect cascades in last N minutes (REST endpoint).
     * Usage: GET /mcp/cascade/detect?minutes=5
     */
    @GetMapping("/detect")
    public ResponseEntity<?> detectCascades(
        @RequestParam(defaultValue = "5") int minutes) {

        try {
            List<CascadePattern> cascades = detector.detectActiveCascades(minutes);

            return ResponseEntity.ok(Map.of(
                "timestamp", System.currentTimeMillis(),
                "window_minutes", minutes,
                "cascades_detected", cascades.size(),
                "cascades", cascades,
                "cache_size", detector.getRecentCascades().size()
            ));

        } catch (Exception ex) {
            log.error("Failed to detect cascades", ex);
            return ResponseEntity.status(500).body(Map.of(
                "error", ex.getMessage(),
                "timestamp", System.currentTimeMillis()
            ));
        }
    }

    /**
     * Get cached cascades (no ES query, instant response).
     * Usage: GET /mcp/cascade/recent
     */
    @GetMapping("/recent")
    public ResponseEntity<?> getRecentCascades() {
        List<CascadePattern> cascades = detector.getRecentCascades();

        return ResponseEntity.ok(Map.of(
            "timestamp", System.currentTimeMillis(),
            "cascades", cascades,
            "count", cascades.size()
        ));
    }

    /**
     * Stream cascade alerts in real-time (SSE).
     * Usage: EventSource.addEventListener("cascade", handler)
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamCascadeAlerts() {
        SseEmitter emitter = new SseEmitter(30000L);  // 30 second timeout
        emitters.add(emitter);

        emitter.onCompletion(() -> {
            emitters.remove(emitter);
            log.debug("SSE client disconnected");
        });
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            log.debug("SSE client timeout");
        });

        try {
            // Send initial connection message
            emitter.send(SseEmitter.event()
                .name("connected")
                .data(Map.of("status", "connected", "timestamp", System.currentTimeMillis()))
                .id(String.valueOf(System.nanoTime()))
                .reconnectTime(5000)
            );
        } catch (IOException ex) {
            log.debug("Failed to send initial SSE message", ex);
            emitters.remove(emitter);
        }

        return emitter;
    }

    /**
     * Broadcast cascade alert to all connected SSE clients.
     * Called by the monitoring service when a cascade is detected.
     */
    public void broadcastCascadeAlert(CascadePattern cascade) {
        String alert = detector.generateAlert(cascade);

        List<SseEmitter> deadEmitters = new ArrayList<>();

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                    .name("cascade")
                    .data(Map.of(
                        "id", cascade.id(),
                        "root_cause", cascade.rootCauseService(),
                        "type", cascade.cascadeType(),
                        "severity", cascade.severity(),
                        "affected_services", cascade.propagationChain().size(),
                        "propagation_speed_ms", String.format("%.1f", cascade.propagationSpeed()),
                        "alert", alert,
                        "timestamp", System.currentTimeMillis()
                    ))
                    .id(cascade.id())
                    .reconnectTime(5000)
                );
            } catch (IOException ex) {
                log.debug("Failed to send SSE alert, marking emitter as dead", ex);
                deadEmitters.add(emitter);
            }
        }

        deadEmitters.forEach(emitters::remove);
    }

    /**
     * Manual check for cascades (polling endpoint).
     * Returns paginated cascade details with full chain.
     * Usage: GET /mcp/cascade/check?minutes=10&severity=CRITICAL
     */
    @GetMapping("/check")
    public ResponseEntity<?> checkCascades(
        @RequestParam(defaultValue = "10") int minutes,
        @RequestParam(required = false) String severity) {

        try {
            List<CascadePattern> cascades = detector.detectActiveCascades(minutes);

            if (severity != null && !severity.isEmpty()) {
                cascades = cascades.stream()
                    .filter(c -> c.severity().equals(severity))
                    .toList();
            }

            return ResponseEntity.ok(Map.of(
                "status", "ok",
                "cascades_found", cascades.size(),
                "window_minutes", minutes,
                "filter_severity", severity != null ? severity : "any",
                "results", cascades.stream()
                    .map(c -> Map.of(
                        "id", c.id(),
                        "root_cause", c.rootCauseService(),
                        "cascade_type", c.cascadeType(),
                        "severity", c.severity(),
                        "affected_services", c.propagationChain().size(),
                        "propagation_speed_ms", String.format("%.1f", c.propagationSpeed()),
                        "services_chain", c.propagationChain().stream()
                            .map(e -> e.service() + "[" + e.stageNumber() + "]")
                            .toList()
                    ))
                    .toList(),
                "timestamp", System.currentTimeMillis()
            ));

        } catch (Exception ex) {
            log.error("Failed to check cascades", ex);
            return ResponseEntity.status(500).body(Map.of(
                "error", "Cascade detection failed",
                "reason", ex.getMessage()
            ));
        }
    }
}
