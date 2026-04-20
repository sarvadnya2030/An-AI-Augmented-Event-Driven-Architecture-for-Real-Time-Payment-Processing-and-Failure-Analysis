package com.clearflow.mcp.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.clearflow.mcp.llm.LLMClient;
import com.clearflow.mcp.llm.LLMMessage;
import com.clearflow.mcp.service.AccessLogService;
import com.clearflow.mcp.service.McpMetricsService;
import com.clearflow.mcp.service.McpRateLimiter;
import com.clearflow.mcp.service.RootCauseAnalysisService;
import com.clearflow.mcp.service.SystemicFailureDetector;
import com.clearflow.mcp.tool.MCPTool;

@RestController
@RequestMapping("/mcp")
public class MCPController {

    private static final String SYSTEM_PROMPT = """
            You are ClearFlow AI, an intelligent assistant for the ClearFlow ISO 20022 payment orchestration platform.
            You help operators understand payment flows, fraud scores, AML compliance results, and rail routing decisions.
            Be concise, factual, and security-aware. Never reveal raw credentials, keys, or internal IPs.
            Respond in plain text. If payment context is provided below, use it to answer accurately.
            """;

    private final McpRateLimiter rateLimiter;
    private final AccessLogService accessLogService;
    private final LLMClient llmClient;
    private final List<MCPTool> tools;
    private final RootCauseAnalysisService rootCauseService;
    private final SystemicFailureDetector systemicDetector;
    private final McpMetricsService mcpMetricsService;

    public MCPController(McpRateLimiter rateLimiter,
                         AccessLogService accessLogService,
                         LLMClient llmClient,
                         List<MCPTool> tools,
                         RootCauseAnalysisService rootCauseService,
                         SystemicFailureDetector systemicDetector,
                         McpMetricsService mcpMetricsService) {
        this.rateLimiter = rateLimiter;
        this.accessLogService = accessLogService;
        this.llmClient = llmClient;
        this.tools = tools;
        this.rootCauseService = rootCauseService;
        this.systemicDetector = systemicDetector;
        this.mcpMetricsService = mcpMetricsService;
    }

    /**
     * Root cause analysis — the flagship endpoint.
     * Fetches ES logs, reconstructs timeline, classifies failure, generates narrative.
     * Example: GET /mcp/payments/PAY-001/explain
     */
    @GetMapping("/payments/{paymentId}/explain")
    public ResponseEntity<?> explain(@PathVariable String paymentId, @AuthenticationPrincipal Jwt jwt) {
        String subject = subject(jwt);
        if (!rateLimiter.allow(subject)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("message", "Rate limit exceeded"));
        }
        accessLogService.log(paymentId, subject, "/mcp/payments/{paymentId}/explain");
        return ResponseEntity.ok(rootCauseService.explain(paymentId));
    }

    /**
     * Systemic failure detection — aggregated cross-payment health check.
     * Example: GET /mcp/diagnostics/systemic?windowMinutes=15
     */
    @GetMapping("/diagnostics/systemic")
    public ResponseEntity<?> systemic(
            @RequestParam(defaultValue = "15") int windowMinutes,
            @AuthenticationPrincipal Jwt jwt) {
        String subject = subject(jwt);
        if (!rateLimiter.allow(subject)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("message", "Rate limit exceeded"));
        }
        accessLogService.log("systemic", subject, "/mcp/diagnostics/systemic");
        return ResponseEntity.ok(systemicDetector.detect(windowMinutes));
    }

    /**
     * Active HIGH alerts in the last N minutes, grouped by service.
     * Example: GET /mcp/alerts/active?windowMinutes=30
     */
    @GetMapping("/alerts/active")
    public ResponseEntity<?> alerts(
            @RequestParam(defaultValue = "30") int windowMinutes,
            @AuthenticationPrincipal Jwt jwt) {
        String subject = subject(jwt);
        if (!rateLimiter.allow(subject)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("message", "Rate limit exceeded"));
        }
        accessLogService.log("alerts", subject, "/mcp/alerts/active");
        return ResponseEntity.ok(Map.of(
                "windowMinutes", windowMinutes,
                "alertsByService", rootCauseService != null
                        ? systemicDetector.detect(windowMinutes).alertsByService()
                        : Map.of()
        ));
    }

    @GetMapping("/payments/{paymentId}/timeline")
    public ResponseEntity<?> timeline(@PathVariable String paymentId, @AuthenticationPrincipal Jwt jwt) {
        return guarded(paymentId, jwt, "/mcp/payments/{paymentId}/timeline",
                Map.of("paymentId", paymentId, "timeline", "audit-source"));
    }

    @GetMapping("/payments/{paymentId}/risk")
    public ResponseEntity<?> risk(@PathVariable String paymentId, @AuthenticationPrincipal Jwt jwt) {
        return guarded(paymentId, jwt, "/mcp/payments/{paymentId}/risk",
                Map.of("paymentId", paymentId, "risk", "redis+mongo-source"));
    }

    @GetMapping("/payments/{paymentId}/compliance")
    public ResponseEntity<?> compliance(@PathVariable String paymentId, @AuthenticationPrincipal Jwt jwt) {
        return guarded(paymentId, jwt, "/mcp/payments/{paymentId}/compliance",
                Map.of("paymentId", paymentId, "compliance", "oracle-source"));
    }

    @GetMapping("/metrics/rails")
    public ResponseEntity<?> rails(@AuthenticationPrincipal Jwt jwt) {
        String subject = subject(jwt);
        if (!rateLimiter.allow(subject)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("message", "Rate limit exceeded"));
        }
        accessLogService.log("metrics", subject, "/mcp/metrics/rails");
        return ResponseEntity.ok(mcpMetricsService.railDistribution(24 * 60));
    }

    @GetMapping("/metrics/fraud")
    public ResponseEntity<?> fraud(@AuthenticationPrincipal Jwt jwt) {
        String subject = subject(jwt);
        if (!rateLimiter.allow(subject)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("message", "Rate limit exceeded"));
        }
        accessLogService.log("metrics", subject, "/mcp/metrics/fraud");
        return ResponseEntity.ok(mcpMetricsService.fraudHistogram(24 * 60));
    }

    @GetMapping("/metrics/overview")
    public ResponseEntity<?> overview(@AuthenticationPrincipal Jwt jwt) {
        String subject = subject(jwt);
        if (!rateLimiter.allow(subject)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("message", "Rate limit exceeded"));
        }
        accessLogService.log("metrics", subject, "/mcp/metrics/overview");
        return ResponseEntity.ok(mcpMetricsService.overview(24 * 60));
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest req, @AuthenticationPrincipal Jwt jwt) {
        String subject = subject(jwt);
        if (!rateLimiter.allow(subject)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("message", "Rate limit exceeded"));
        }

        StringBuilder context = new StringBuilder();
        if (req.paymentId() != null && !req.paymentId().isBlank()) {
            Map<String, Object> toolInput = Map.of("paymentId", req.paymentId());
            for (MCPTool tool : tools) {
                Object result = tool.execute(toolInput);
                context.append(tool.name()).append(": ").append(result).append("\n");
            }
            accessLogService.log(req.paymentId(), subject(jwt), "/mcp/chat");
        }

        List<LLMMessage> messages = new ArrayList<>();
        String systemContent = SYSTEM_PROMPT + (context.isEmpty() ? "" : "\nPayment context:\n" + context);
        messages.add(new LLMMessage("system", systemContent));
        if (req.history() != null) {
            messages.addAll(req.history());
        }
        messages.add(new LLMMessage("user", req.question()));

        String answer = llmClient.chat(messages);
        return ResponseEntity.ok(Map.of(
                "answer", answer,
                "provider", llmClient.providerName()
        ));
    }

    private ResponseEntity<?> guarded(String paymentId, Jwt jwt, String path, Object payload) {
        String subject = subject(jwt);
        if (!rateLimiter.allow(subject)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("message", "Rate limit exceeded"));
        }
        accessLogService.log(paymentId, subject, path);
        return ResponseEntity.ok(payload);
    }

    private static String subject(Jwt jwt) {
        return jwt != null ? jwt.getSubject() : "anonymous";
    }
}
