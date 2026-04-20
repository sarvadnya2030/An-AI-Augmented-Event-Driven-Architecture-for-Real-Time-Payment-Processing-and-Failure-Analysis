package com.clearflow.mcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

// java.util.* covers ArrayList, Arrays, Collections, LinkedHashMap, HashMap, List, Map

/**
 * Loads the Graphify code knowledge graph (graphify-out/graph.json) AND the
 * broker topology (graphify-out/queue_topology.json) at startup.
 *
 * Exposes two layers of context for the MCP incident tools:
 *   1. Code graph — which Java classes/methods own each service
 *   2. Broker topology — which Kafka topics / ActiveMQ queues each service
 *      produces to and consumes from, circuit breaker config, saga flow
 *
 * Together these let the LLM trace a cascading failure across service
 * boundaries: "Circuit breaker KAFKA opened in KafkaEventPublisher.java →
 * fallback to ActiveMQPublisher → CLEARFLOW.PAYMENT.INITIATED backed up →
 * ValidationEnrichmentCamelRoute starved → DLQ overflow after 3 retries"
 */
@Service
public class CodeGraphService {

    private static final Logger log = LoggerFactory.getLogger(CodeGraphService.class);

    // Service name → list of class-level graph nodes
    private final Map<String, List<CodeNode>> serviceIndex = new LinkedHashMap<>();

    // Failure keyword → list of relevant class nodes (cross-service)
    private final Map<String, List<CodeNode>> failureIndex = new LinkedHashMap<>();

    // All class-level nodes by label (lower-cased) for quick lookup
    private final Map<String, CodeNode> classByLabel = new LinkedHashMap<>();

    // Broker topology: topic/queue name → QueueNode
    private final Map<String, QueueNode> queueIndex = new LinkedHashMap<>();

    // Service → queues it produces to or consumes from
    private final Map<String, List<QueueNode>> serviceQueueMap = new LinkedHashMap<>();

    // Raw pipeline flow steps from topology JSON
    private final List<String> pipelineFlow = new ArrayList<>();

    // Saga flow description
    private String sagaFlowSummary = "";

    // DLQ config summary
    private String dlqConfigSummary = "";

    // Circuit breaker descriptions (name → description)
    private final Map<String, String> circuitBreakerDesc = new LinkedHashMap<>();

    private boolean loaded = false;
    private boolean topologyLoaded = false;

    @Value("${clearflow.code-graph.path:../graphify-out/graph.json}")
    private String graphPath;

    private final ObjectMapper mapper;

    public CodeGraphService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * A single class-level node from the code graph.
     */
    public record CodeNode(
            String id,
            String label,       // class or file name
            String sourceFile,  // relative path from repo root
            String module,      // derived microservice name
            List<String> methods // method names declared in this class
    ) {}

    /**
     * A message queue/topic node from the broker topology.
     */
    public record QueueNode(
            String name,
            String broker,          // KAFKA | ACTIVEMQ | SOLACE
            String purpose,
            boolean isDLQ,
            boolean isSagaTrigger,
            List<String> producerServices,
            List<String> producerClasses,
            List<String> producerFiles,
            List<String> consumerServices,
            List<String> consumerClasses,
            List<String> consumerFiles
    ) {}

    @PostConstruct
    public void load() {
        loadCodeGraph();
        loadBrokerTopology();
    }

    private void loadCodeGraph() {
        File f = new File(graphPath);
        if (!f.exists()) {
            f = new File("/home/admin-/Desktop/EDI6/clearflow/graphify-out/graph.json");
        }
        if (!f.exists()) {
            log.warn("CodeGraphService: graph.json not found at {} — code context disabled", graphPath);
            return;
        }

        try {
            JsonNode root = mapper.readTree(f);
            JsonNode nodes = root.path("nodes");

            Map<String, List<String>> methodsByFile = new HashMap<>();
            List<JsonNode> rawNodes = new ArrayList<>();
            nodes.forEach(rawNodes::add);

            for (JsonNode n : rawNodes) {
                String label = n.path("label").asText("");
                String srcFile = n.path("source_file").asText("");
                if (srcFile.endsWith(".java") && label.startsWith(".")) {
                    String methodName = label.replaceFirst("^\\.", "").replaceAll("\\(.*\\)", "()");
                    methodsByFile.computeIfAbsent(srcFile, k -> new ArrayList<>()).add(methodName);
                }
            }

            for (JsonNode n : rawNodes) {
                String label   = n.path("label").asText("");
                String srcFile = n.path("source_file").asText("");
                if (!srcFile.endsWith(".java")) continue;
                if (!label.endsWith(".java")) continue;

                String module = deriveModule(srcFile);
                List<String> methods = methodsByFile.getOrDefault(srcFile, List.of());
                CodeNode node = new CodeNode(n.path("id").asText(""),
                        label.replace(".java", ""), srcFile, module, methods);
                serviceIndex.computeIfAbsent(module, k -> new ArrayList<>()).add(node);
                classByLabel.put(label.toLowerCase().replace(".java", ""), node);
                indexForFailure(node);
            }

            loaded = true;
            int total = serviceIndex.values().stream().mapToInt(List::size).sum();
            log.info("CodeGraphService: {} classes across {} modules loaded from {}",
                    total, serviceIndex.size(), f.getName());
        } catch (Exception e) {
            log.warn("CodeGraphService: graph.json load failed — {}", e.getMessage());
        }
    }

    private void loadBrokerTopology() {
        // Derive topology path from graph path (sibling file)
        String topoPath = graphPath.replace("graph.json", "queue_topology.json");
        File f = new File(topoPath);
        if (!f.exists()) {
            f = new File("/home/admin-/Desktop/EDI6/clearflow/graphify-out/queue_topology.json");
        }
        if (!f.exists()) {
            log.warn("CodeGraphService: queue_topology.json not found — broker context disabled");
            return;
        }

        try {
            JsonNode root = mapper.readTree(f);

            // Load Kafka topics
            root.path("brokers").path("kafka").path("topics").fields().forEachRemaining(e -> {
                QueueNode node = parseQueueNode(e.getKey(), "KAFKA", e.getValue());
                queueIndex.put(e.getKey(), node);
                indexQueueByService(node);
            });

            // Load ActiveMQ queues
            root.path("brokers").path("activemq").path("queues").fields().forEachRemaining(e -> {
                QueueNode node = parseQueueNode(e.getKey(), "ACTIVEMQ", e.getValue());
                queueIndex.put(e.getKey(), node);
                indexQueueByService(node);
            });

            // Load Solace topics
            root.path("brokers").path("solace").path("topics").fields().forEachRemaining(e -> {
                QueueNode node = parseQueueNode(e.getKey(), "SOLACE", e.getValue());
                queueIndex.put(e.getKey(), node);
                indexQueueByService(node);
            });

            // Pipeline flow
            root.path("pipelineFlow").forEach(step -> {
                String s = String.format("Step %d [%s]: %s",
                        step.path("step").asInt(),
                        step.path("service").asText("?"),
                        step.path("action").asText(""));
                pipelineFlow.add(s);
            });

            // Saga flow
            JsonNode saga = root.path("sagaFlow");
            if (!saga.isMissingNode()) {
                StringBuilder sb = new StringBuilder();
                sb.append("Trigger: ").append(saga.path("trigger").asText()).append("\n");
                sb.append("Route: ").append(saga.path("compensationClass").asText()).append("\n");
                saga.path("steps").forEach(s -> sb.append("  ").append(s.asText()).append("\n"));
                sagaFlowSummary = sb.toString();
            }

            // DLQ config
            JsonNode dlq = root.path("dlqConfig");
            if (!dlq.isMissingNode()) {
                dlqConfigSummary = String.format(
                        "maxRetries=%d delay=%s backoff=%s destination=%s",
                        dlq.path("maximumRedeliveries").asInt(3),
                        dlq.path("redeliveryDelayMs").asText("1000ms"),
                        dlq.path("backoff").asText("exponential"),
                        dlq.path("dlqDestination").asText("CLEARFLOW.PAYMENT.DLQ"));
            }

            // Circuit breakers
            root.path("circuitBreakers").forEach(cb -> {
                String name = cb.path("name").asText("");
                String desc = String.format("wraps %s in %s — fallback: %s — opens when: %s",
                        cb.path("wraps").asText("?"),
                        cb.path("file").asText("?"),
                        cb.path("fallback").asText("?"),
                        cb.path("opensWhen").asText("?"));
                circuitBreakerDesc.put(name, desc);
            });

            topologyLoaded = true;
            log.info("CodeGraphService: broker topology loaded — {} queues/topics, {} services indexed",
                    queueIndex.size(), serviceQueueMap.size());
        } catch (Exception e) {
            log.warn("CodeGraphService: queue_topology.json load failed — {}", e.getMessage());
        }
    }

    private QueueNode parseQueueNode(String name, String broker, JsonNode node) {
        List<String> pSvcs = new ArrayList<>(), pCls = new ArrayList<>(), pFiles = new ArrayList<>();
        List<String> cSvcs = new ArrayList<>(), cCls = new ArrayList<>(), cFiles = new ArrayList<>();

        node.path("producers").forEach(p -> {
            pSvcs.add(p.path("service").asText(""));
            pCls.add(p.path("class").asText(""));
            pFiles.add(p.path("file").asText(""));
        });
        node.path("consumers").forEach(c -> {
            cSvcs.add(c.path("service").asText(""));
            cCls.add(c.path("class").asText(""));
            cFiles.add(c.path("file").asText(""));
        });

        return new QueueNode(name, broker,
                node.path("purpose").asText(""),
                node.path("isDLQ").asBoolean(false),
                node.path("isSagaTrigger").asBoolean(false),
                pSvcs, pCls, pFiles, cSvcs, cCls, cFiles);
    }

    private void indexQueueByService(QueueNode q) {
        q.producerServices().forEach(s -> {
            if (!s.isBlank()) serviceQueueMap.computeIfAbsent(s, k -> new ArrayList<>()).add(q);
        });
        q.consumerServices().forEach(s -> {
            if (!s.isBlank()) serviceQueueMap.computeIfAbsent(s, k -> new ArrayList<>()).add(q);
        });
    }

    /**
     * Returns code context for a given microservice and failure event type.
     * Used by the explainIncidentWithCode MCP tool to inject codebase context
     * into the LLM prompt so it can give file-and-class-level resolution advice.
     *
     * @param service       one of: gateway, validation-enrichment, fraud-scoring,
     *                      aml-compliance, routing-execution, settlement, audit
     * @param failureType   event type or cascade type from ES log:
     *                      CIRCUIT_BREAKER, SAGA_COMPENSATION, AML_SANCTIONS_HIT, etc.
     * @return formatted code context block (fits inside LLM prompt)
     */
    public String getCodeContext(String service, String failureType) {
        if (!loaded) return "(code graph not available)";

        StringBuilder sb = new StringBuilder();

        // 1. Service-specific classes
        List<CodeNode> serviceClasses = serviceIndex.getOrDefault(service, List.of());
        if (!serviceClasses.isEmpty()) {
            sb.append("RELEVANT SOURCE FILES in ").append(service).append(":\n");
            serviceClasses.stream()
                    .sorted(Comparator.comparingInt(n -> -n.methods().size())) // most methods first
                    .limit(6)
                    .forEach(node -> {
                        sb.append("  ").append(node.label())
                          .append(" — ").append(node.sourceFile()).append("\n");
                        if (!node.methods().isEmpty()) {
                            String methodList = node.methods().stream()
                                    .limit(4)
                                    .collect(Collectors.joining(", "));
                            sb.append("    methods: ").append(methodList).append("\n");
                        }
                    });
        }

        // 2. Failure-type-specific classes (cross-service)
        if (failureType != null && !failureType.isBlank()) {
            List<CodeNode> failureClasses = getFailureClasses(failureType);
            if (!failureClasses.isEmpty()) {
                sb.append("\nCLASSES RELATED TO ").append(failureType).append(":\n");
                failureClasses.stream().limit(5).forEach(node -> {
                    sb.append("  ").append(node.label())
                      .append(" [").append(node.module()).append("]")
                      .append(" — ").append(node.sourceFile()).append("\n");
                    if (!node.methods().isEmpty()) {
                        String methodList = node.methods().stream()
                                .limit(3)
                                .collect(Collectors.joining(", "));
                        sb.append("    methods: ").append(methodList).append("\n");
                    }
                });
            }
        }

        return sb.isEmpty() ? "(no code context found for service=" + service + ")" : sb.toString();
    }

    /**
     * Returns broker topology context for a service + cascade type.
     * Appended to the LLM prompt in traceBrokerCascade to give cross-service
     * message flow context so the LLM can explain the propagation path.
     */
    public String getBrokerContext(String service, String cascadeType) {
        if (!topologyLoaded) return "(broker topology not available)";

        StringBuilder sb = new StringBuilder();

        // Queues this service uses
        List<QueueNode> queues = serviceQueueMap.getOrDefault(service, List.of());
        if (!queues.isEmpty()) {
            sb.append("BROKER CHANNELS for ").append(service).append(":\n");
            queues.stream().distinct().forEach(q -> {
                boolean isProducer = q.producerServices().contains(service);
                boolean isConsumer = q.consumerServices().contains(service);
                String role = isProducer && isConsumer ? "PRODUCES+CONSUMES" :
                              isProducer ? "PRODUCES →" : "← CONSUMES";
                sb.append(String.format("  [%s] %s %s (%s)\n",
                        q.broker(), role, q.name(), q.purpose()));
                if (q.isDLQ()) sb.append("    ⚠ THIS IS A DEAD LETTER QUEUE\n");
                if (q.isSagaTrigger()) sb.append("    ⚡ THIS TRIGGERS SAGA COMPENSATION\n");
            });
        }

        // Cascade-type-specific context
        if (cascadeType != null) {
            String upper = cascadeType.toUpperCase();
            sb.append("\n");
            switch (upper) {
                case "CIRCUIT_BREAKER" -> {
                    sb.append("CIRCUIT BREAKER CONFIG:\n");
                    circuitBreakerDesc.forEach((name, desc) ->
                            sb.append("  CB[").append(name).append("]: ").append(desc).append("\n"));
                }
                case "SAGA_COMPENSATION" -> {
                    sb.append("SAGA COMPENSATION FLOW:\n");
                    if (!sagaFlowSummary.isBlank())
                        Arrays.stream(sagaFlowSummary.split("\n"))
                              .forEach(line -> sb.append("  ").append(line).append("\n"));
                }
                case "RETRY_STORM", "QUEUE_OVERFLOW" -> {
                    sb.append("DLQ / RETRY CONFIG:\n  ").append(dlqConfigSummary).append("\n");
                    // Show DLQ queues
                    queueIndex.values().stream().filter(QueueNode::isDLQ).forEach(q ->
                            sb.append("  DLQ: ").append(q.name())
                              .append(" [").append(q.broker()).append("] — ").append(q.purpose()).append("\n"));
                }
                case "DOWNSTREAM_STARVATION" -> {
                    sb.append("PIPELINE FLOW (starvation propagation path):\n");
                    pipelineFlow.forEach(step -> sb.append("  ").append(step).append("\n"));
                }
            }
        }

        return sb.isEmpty() ? "(no broker context for service=" + service + ")" : sb.toString();
    }

    /**
     * Returns the full payment pipeline as an ASCII flow diagram.
     * Used in traceBrokerCascade to show the complete broker hop chain.
     */
    public String getFullPipelineTopology() {
        if (!topologyLoaded) return "(topology not loaded)";

        StringBuilder sb = new StringBuilder();
        sb.append("CLEARFLOW PAYMENT PIPELINE — 3-BROKER TOPOLOGY\n");
        sb.append("─".repeat(60)).append("\n");
        sb.append("[Kafka]  gateway.KafkaEventPublisher\n");
        sb.append("  → clearflow.payment.initiated\n");
        sb.append("     ├─ fraud-scoring.FraudKafkaConsumer (parallel)\n");
        sb.append("     └─ audit.AuditEventConsumer (fan-out)\n\n");
        sb.append("[ActiveMQ/Camel]  Orchestration backbone:\n");
        sb.append("  gateway.ActiveMQPublisher\n");
        sb.append("  → CLEARFLOW.PAYMENT.INITIATED\n");
        sb.append("     └─ validation-enrichment.ValidationEnrichmentCamelRoute\n");
        sb.append("          → CLEARFLOW.PAYMENT.VALIDATED\n");
        sb.append("               └─ aml-compliance.AMLCamelRoute\n");
        sb.append("                    ├─ [HIT]  CLEARFLOW.PAYMENT.SANCTIONS.HIT (terminal)\n");
        sb.append("                    └─ [CLEAR] CLEARFLOW.PAYMENT.SANCTIONS.CLEAR\n");
        sb.append("                               └─ routing-execution.RoutingCamelRoute\n");
        sb.append("                                    → CLEARFLOW.PAYMENT.ROUTED\n");
        sb.append("                                         └─ settlement.SettlementCamelRoute\n");
        sb.append("                                              ├─ [OK]   clearflow.payment.settled\n");
        sb.append("                                              └─ [FAIL] CLEARFLOW.PAYMENT.SETTLEMENT.FAILED\n");
        sb.append("                                                          └─ SagaCompensationRoute ← SAGA\n\n");
        sb.append("[DLQ]  All Camel routes → CLEARFLOW.PAYMENT.DLQ (3 retries, exp backoff)\n");
        sb.append("[CB]   gateway: CB[KAFKA]→CB[ACTIVEMQ]→CB[SOLACE] (Resilience4j fallback chain)\n");

        return sb.toString();
    }

    /**
     * Returns a compact summary of which services have how many indexed classes.
     * Used by the MCP tool to report graph coverage.
     */
    public Map<String, Integer> getCoverage() {
        Map<String, Integer> coverage = new LinkedHashMap<>();
        serviceIndex.forEach((svc, cls) -> coverage.put(svc, cls.size()));
        return coverage;
    }

    public boolean isLoaded() { return loaded; }
    public boolean isTopologyLoaded() { return topologyLoaded; }
    public Map<String, QueueNode> getQueueIndex() { return Collections.unmodifiableMap(queueIndex); }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String deriveModule(String sourceFile) {
        // sourceFile is like "fraud-scoring/src/main/java/com/clearflow/..."
        // Extract the leading directory segment as the module name
        int slash = sourceFile.indexOf('/');
        return slash > 0 ? sourceFile.substring(0, slash) : "unknown";
    }

    private void indexForFailure(CodeNode node) {
        String combined = (node.label() + " " + String.join(" ", node.methods())).toLowerCase();
        for (Map.Entry<String, String[]> entry : FAILURE_KEYWORDS.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (combined.contains(keyword.toLowerCase())) {
                    failureIndex.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(node);
                    break;
                }
            }
        }
    }

    private List<CodeNode> getFailureClasses(String failureType) {
        String upper = failureType.toUpperCase();
        // Direct match first
        if (failureIndex.containsKey(upper)) return failureIndex.get(upper);

        // Fuzzy match — find the key whose keywords overlap most with failureType tokens
        String[] tokens = upper.split("[_\\s]+");
        return failureIndex.entrySet().stream()
                .filter(e -> Arrays.stream(tokens).anyMatch(t -> e.getKey().contains(t) || t.contains(e.getKey())))
                .flatMap(e -> e.getValue().stream())
                .distinct()
                .limit(5)
                .collect(Collectors.toList());
    }

    /**
     * Maps failure categories to class-name keywords.
     * These drive which graph nodes surface for a given failure type.
     */
    private static final Map<String, String[]> FAILURE_KEYWORDS = new LinkedHashMap<>() {{
        put("CIRCUIT_BREAKER",      new String[]{"CircuitBreaker","Resilience","Fallback","Retry"});
        put("RETRY_STORM",          new String[]{"Retry","RetryTemplate","RetryPolicy","Backoff"});
        put("SAGA_COMPENSATION",    new String[]{"Saga","Compensation","Rollback","Revert","Undo"});
        put("DOWNSTREAM_STARVATION",new String[]{"ConnectionPool","DataSource","Pool","Starvation","Backpressure"});
        put("AML_SANCTIONS_HIT",    new String[]{"AML","Screening","Sanction","SDN","Fuzzy","PEP"});
        put("FRAUD",                new String[]{"Fraud","Risk","Score","Scoring","Detector","FraudPattern"});
        put("SETTLEMENT",           new String[]{"Settlement","Settle","Nostro","RTGS","Rails"});
        put("ROUTING",              new String[]{"Routing","Router","Rail","RailSelector","Execution"});
        put("VALIDATION",           new String[]{"Validation","Validator","Enrichment","IBAN","BIC","Schema"});
        put("EMBARGO",              new String[]{"Embargo","Country","Sanction","Blocked","Compliance"});
        put("TIMEOUT",              new String[]{"Timeout","TimedOut","Async","Async","Deadline"});
        put("QUEUE_OVERFLOW",       new String[]{"Queue","DLQ","DeadLetter","Overflow","Backlog"});
    }};
}
