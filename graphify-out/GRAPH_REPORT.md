# Graph Report - .  (2026-04-20)

## Corpus Check
- 191 files · ~80,825 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1037 nodes · 1354 edges · 78 communities detected
- Extraction: 79% EXTRACTED · 21% INFERRED · 0% AMBIGUOUS · INFERRED: 282 edges (avg confidence: 0.5)
- Token cost: 0 input · 0 output

## God Nodes (most connected - your core abstractions)
1. `ScreeningRecord` - 33 edges
2. `ValidationRecord` - 25 edges
3. `ElasticsearchLogFetcher` - 24 edges
4. `PaymentEnrichment` - 23 edges
5. `SettlementRecord` - 23 edges
6. `AuditRecord` - 21 edges
7. `LedgerEntry` - 21 edges
8. `ClearFlowMcpTools` - 20 edges
9. `_build_payload()` - 17 edges
10. `SimulatorConfig` - 17 edges

## Surprising Connections (you probably didn't know these)
- None detected - all connections are within the same source files.

## Communities

### Community 0 - "gateway module"
Cohesion: 0.04
Nodes (15): ActiveMQPublisher, CircuitBreakerNames, IdempotencyService, KafkaEventPublisher, MaskedIbanSerializer, PaymentController, PaymentControllerTest, PaymentStatusService (+7 more)

### Community 1 - "aml-compliance module"
Cohesion: 0.04
Nodes (6): AMLScreeningProcessor, FuzzyMatchTest, FuzzyScreeningEngine, ScreeningRecord, ScreeningRecordRepository, SDNLoader

### Community 2 - "mcp-readonly-gateway module"
Cohesion: 0.04
Nodes (10): LLMClient, McpMetricsService, McpRateLimiter, State, MCPTool, McpToolsConfig, PromptTemplates, RootCauseAnalysisService (+2 more)

### Community 3 - "audit module"
Cohesion: 0.04
Nodes (7): AuditController, AuditEventConsumer, AuditRecord, AuditRecordKey, AuditRepository, HashChainIntegrityTest, HashChainService

### Community 4 - "fraud-scoring module"
Cohesion: 0.05
Nodes (9): CountryRiskMatrix, FeatureEngineeringService, FraudKafkaConsumer, FraudScoringController, FraudScoringService, FraudScoringServiceTest, HeuristicScoringService, LightGBMStubClient (+1 more)

### Community 5 - "validation-enrichment module"
Cohesion: 0.04
Nodes (8): BICValidationProcessor, CurrencyValidationProcessor, EmbargoPreCheckProcessor, EnrichmentProcessor, IBANValidationProcessor, PaymentEnrichment, PaymentEnrichmentRepository, ValidationEnrichmentCamelRoute

### Community 6 - "mcp-readonly-gateway module"
Cohesion: 0.06
Nodes (5): ComplianceTool, ElasticsearchLogFetcher, FraudScoreTool, PaymentTimelineReconstructor, PaymentTimelineTool

### Community 7 - "routing-execution module"
Cohesion: 0.05
Nodes (10): AccessLogService, AMLCamelRoute, KafkaTopics, LiquidityReservationProcessor, LiquidityReservationService, MQQueues, PaymentStatusKafkaConsumer, RoutingCamelRoute (+2 more)

### Community 8 - "routing-execution module"
Cohesion: 0.07
Nodes (4): PaymentRailRule, RailRules, RailSelectionEngine, RailSelectionProcessor

### Community 9 - "settlement module"
Cohesion: 0.08
Nodes (7): ClickHouseAnalyticsService, DoubleEntryAccountingTest, LedgerRepository, SettlementController, SettlementProcessor, SettlementRepository, SettlementService

### Community 10 - "validation-enrichment module"
Cohesion: 0.07
Nodes (2): ValidationRecord, ValidationRecordRepository

### Community 11 - "generate_paysim_iso.py cluster"
Cohesion: 0.16
Nodes (23): _balances_fraud(), _balances_normal(), _bic(), _build_payload(), _city(), _company(), _gen_account_takeover(), _gen_embargoed_transit() (+15 more)

### Community 12 - "settlement module"
Cohesion: 0.09
Nodes (1): SettlementRecord

### Community 13 - "extract_queue_topology.py cluster"
Cohesion: 0.19
Nodes (20): _build_service_index(), build_topology(), collect_activemq_consumers(), collect_activemq_producers(), collect_kafka_consumers(), collect_kafka_producers(), derive_class(), derive_service() (+12 more)

### Community 14 - "settlement module"
Cohesion: 0.1
Nodes (1): LedgerEntry

### Community 15 - "mcp-readonly-gateway module"
Cohesion: 0.15
Nodes (1): ClearFlowMcpTools

### Community 16 - "gateway module"
Cohesion: 0.11
Nodes (1): SimulatorConfig

### Community 17 - "mcp-readonly-gateway module"
Cohesion: 0.17
Nodes (1): CodeGraphService

### Community 18 - "mcp-readonly-gateway module"
Cohesion: 0.25
Nodes (1): DemoScenarioSeeder

### Community 19 - "mcp-readonly-gateway module"
Cohesion: 0.29
Nodes (1): RootCauseClassifierTest

### Community 20 - "mcp-readonly-gateway module"
Cohesion: 0.35
Nodes (1): RootCauseClassifier

### Community 21 - "pipeline_ingest.py cluster"
Cohesion: 0.23
Nodes (12): alert_level(), cascade_events(), es_bulk_index(), es_index_for(), generate_transaction(), main(), _normal_amount(), pipeline_events() (+4 more)

### Community 22 - "mcp-readonly-gateway module"
Cohesion: 0.26
Nodes (1): MCPController

### Community 23 - "routing-execution module"
Cohesion: 0.27
Nodes (1): RailSelectionTest

### Community 24 - "compliance_reporter.py cluster"
Cohesion: 0.33
Nodes (11): es_count(), es_search(), generate_ctr(), generate_lcr(), generate_ofac_summary(), generate_sar(), main(), _ofac_program() (+3 more)

### Community 25 - "gateway module"
Cohesion: 0.29
Nodes (1): FraudPatternInjector

### Community 26 - "mcp-readonly-gateway module"
Cohesion: 0.27
Nodes (1): PaymentTimelineReconstructorTest

### Community 27 - "gateway module"
Cohesion: 0.33
Nodes (1): TransactionPatternLibrary

### Community 28 - "live_payment_sender.py cluster"
Cohesion: 0.64
Nodes (7): batch_mode(), demo_mode(), health_check(), main(), print_health(), random_payment(), send()

### Community 29 - "gateway module"
Cohesion: 0.25
Nodes (1): PaymentArchTest

### Community 30 - "gateway module"
Cohesion: 0.39
Nodes (1): AMLPatternInjector

### Community 31 - "gateway module"
Cohesion: 0.46
Nodes (1): DemoDataLoader

### Community 32 - "gateway module"
Cohesion: 0.36
Nodes (1): AgentRegistry

### Community 33 - "common module"
Cohesion: 0.29
Nodes (2): GlobalExceptionHandler, ProblemDetailBuilder

### Community 34 - "gateway module"
Cohesion: 0.48
Nodes (1): UETRTrackerController

### Community 35 - "gateway module"
Cohesion: 0.43
Nodes (1): IbanGeneratorUtil

### Community 36 - "fraud-scoring module"
Cohesion: 0.33
Nodes (1): FraudKafkaConfig

### Community 37 - "synthetic-load-generator cluster"
Cohesion: 0.6
Nodes (5): generate_iban(), generate_jwt(), generate_payment(), main(), send_payment()

### Community 38 - "frontend cluster"
Cohesion: 0.33
Nodes (0): 

### Community 39 - "load-tests cluster"
Cohesion: 0.47
Nodes (3): buildPayload(), randomAmount(), randomParty()

### Community 40 - "mcp-readonly-gateway module"
Cohesion: 0.4
Nodes (1): FallbackLLMClient

### Community 41 - "mcp-readonly-gateway module"
Cohesion: 0.4
Nodes (1): OpenRouterLLMClient

### Community 42 - "mcp-readonly-gateway module"
Cohesion: 0.4
Nodes (1): OllamaLLMClient

### Community 43 - "mcp-readonly-gateway module"
Cohesion: 0.5
Nodes (1): MetricsTool

### Community 44 - "load-test cluster"
Cohesion: 0.67
Nodes (2): make_payment(), send_one()

### Community 45 - "gateway module"
Cohesion: 0.67
Nodes (1): SecurityConfig

### Community 46 - "gateway module"
Cohesion: 0.67
Nodes (1): GatewayKafkaConsumerConfig

### Community 47 - "validation-enrichment module"
Cohesion: 0.5
Nodes (1): EmbargoDataLoader

### Community 48 - "common module"
Cohesion: 0.67
Nodes (1): CorrelationIdFilter

### Community 49 - "common module"
Cohesion: 0.67
Nodes (1): MetricsConstants

### Community 50 - "common module"
Cohesion: 0.67
Nodes (1): DuplicatePaymentException

### Community 51 - "common module"
Cohesion: 0.67
Nodes (1): PaymentException

### Community 52 - "gateway module"
Cohesion: 0.67
Nodes (1): GatewayApplication

### Community 53 - "gateway module"
Cohesion: 0.67
Nodes (1): IbanValidator

### Community 54 - "gateway module"
Cohesion: 0.67
Nodes (1): DevSecurityConfig

### Community 55 - "fraud-scoring module"
Cohesion: 0.67
Nodes (1): FraudScoringApplication

### Community 56 - "validation-enrichment module"
Cohesion: 0.67
Nodes (1): ValidationEnrichmentApplication

### Community 57 - "settlement module"
Cohesion: 0.67
Nodes (1): CamelKafkaConfig

### Community 58 - "config-server module"
Cohesion: 0.67
Nodes (1): ConfigServerApplication

### Community 59 - "mcp-readonly-gateway module"
Cohesion: 0.67
Nodes (1): McpReadonlyGatewayApplication

### Community 60 - "mcp-readonly-gateway module"
Cohesion: 0.67
Nodes (1): LLMConfig

### Community 61 - "mcp-readonly-gateway module"
Cohesion: 0.67
Nodes (1): MCPSecurityConfig

### Community 62 - "audit module"
Cohesion: 0.67
Nodes (1): AuditApplication

### Community 63 - "audit module"
Cohesion: 0.67
Nodes (1): CassandraConfig

### Community 64 - "routing-execution module"
Cohesion: 0.67
Nodes (1): RoutingExecutionApplication

### Community 65 - "routing-execution module"
Cohesion: 0.67
Nodes (1): InsufficientLiquidityException

### Community 66 - "frontend cluster"
Cohesion: 0.67
Nodes (0): 

### Community 67 - "frontend cluster"
Cohesion: 0.67
Nodes (0): 

### Community 68 - "frontend cluster"
Cohesion: 0.67
Nodes (0): 

### Community 69 - "aml-compliance module"
Cohesion: 0.67
Nodes (1): AmlComplianceApplication

### Community 70 - "settlement module"
Cohesion: 0.67
Nodes (1): SettlementApplication

### Community 71 - "frontend cluster"
Cohesion: 1.0
Nodes (0): 

### Community 72 - "frontend cluster"
Cohesion: 1.0
Nodes (0): 

### Community 73 - "gateway module"
Cohesion: 1.0
Nodes (0): 

### Community 74 - "gateway module"
Cohesion: 1.0
Nodes (0): 

### Community 75 - "gateway module"
Cohesion: 1.0
Nodes (0): 

### Community 76 - "frontend cluster"
Cohesion: 1.0
Nodes (0): 

### Community 77 - "frontend cluster"
Cohesion: 1.0
Nodes (0): 

## Knowledge Gaps
- **17 isolated node(s):** `Build a single transaction dict with all pipeline metadata.`, `Generate 5-7 pipeline log events for one transaction (one per service).`, `Generate additional cascading failure log events that show multi-service failure`, `Bulk-index a batch of events into Elasticsearch.`, `Produce ≥100 000 PaySim-style ISO 20022 payloads.` (+12 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **Thin community `frontend cluster`** (2 nodes): `NavBar.jsx`, `NavBar()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `frontend cluster`** (2 nodes): `mcpApi.js`, `wait()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `gateway module`** (1 nodes): `Iban.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `gateway module`** (1 nodes): `PaymentChannel.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `gateway module`** (1 nodes): `UETRTrackingResponse.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `frontend cluster`** (1 nodes): `vite.config.js`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `frontend cluster`** (1 nodes): `main.jsx`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **What connects `Build a single transaction dict with all pipeline metadata.`, `Generate 5-7 pipeline log events for one transaction (one per service).`, `Generate additional cascading failure log events that show multi-service failure` to the rest of the system?**
  _17 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `gateway module` be split into smaller, more focused modules?**
  _Cohesion score 0.04 - nodes in this community are weakly interconnected._
- **Should `aml-compliance module` be split into smaller, more focused modules?**
  _Cohesion score 0.04 - nodes in this community are weakly interconnected._
- **Should `mcp-readonly-gateway module` be split into smaller, more focused modules?**
  _Cohesion score 0.04 - nodes in this community are weakly interconnected._
- **Should `audit module` be split into smaller, more focused modules?**
  _Cohesion score 0.04 - nodes in this community are weakly interconnected._
- **Should `fraud-scoring module` be split into smaller, more focused modules?**
  _Cohesion score 0.05 - nodes in this community are weakly interconnected._
- **Should `validation-enrichment module` be split into smaller, more focused modules?**
  _Cohesion score 0.04 - nodes in this community are weakly interconnected._