package com.clearflow.settlement.messaging;

import com.clearflow.common.idempotency.StageIdempotencyGuard;
import com.clearflow.common.messaging.DlqPublisher;
import com.clearflow.common.messaging.KafkaTopics;
import com.clearflow.settlement.domain.SettlementRecord;
import com.clearflow.settlement.repository.SettlementRepository;
import com.clearflow.settlement.service.SettlementService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import org.slf4j.MDC;

import java.util.Map;

@Component
public class SettlementKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(SettlementKafkaConsumer.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final String STAGE = "settlement";

    private final SettlementService settlementService;
    private final SettlementRepository settlementRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final StageIdempotencyGuard idempotencyGuard;
    private final Counter settledCounter;
    private final Counter duplicateCounter;
    private final DlqPublisher dlqPublisher;

    public SettlementKafkaConsumer(SettlementService settlementService,
                                    SettlementRepository settlementRepository,
                                    @Qualifier("settlementKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
                                    ObjectMapper objectMapper,
                                    StageIdempotencyGuard idempotencyGuard,
                                    MeterRegistry meterRegistry) {
        this.settlementService    = settlementService;
        this.settlementRepository = settlementRepository;
        this.kafkaTemplate        = kafkaTemplate;
        this.objectMapper         = objectMapper;
        this.idempotencyGuard     = idempotencyGuard;
        this.settledCounter = Counter.builder("clearflow_settlements_total")
                .description("Payments successfully settled")
                .register(meterRegistry);
        this.duplicateCounter = Counter.builder("clearflow_settlement_duplicates_total")
                .description("Settlement duplicate skips (idempotency)")
                .register(meterRegistry);
        this.dlqPublisher = new DlqPublisher(kafkaTemplate, meterRegistry);
    }

    @KafkaListener(
            topics = KafkaTopics.PAYMENT_ROUTED,
            groupId = "settlement-service",
            containerFactory = "settlementKafkaListenerContainerFactory"
    )
    public void onPaymentRouted(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String paymentId = record.key() != null ? record.key() : "UNKNOWN";
        MDC.put("paymentId", paymentId);
        try {
            // Redis-level guard (fast path) before DB check
            if (idempotencyGuard.alreadyProcessed(STAGE, paymentId)) {
                duplicateCounter.increment();
                ack.acknowledge();
                return;
            }
            // DB-level guard before SERIALIZABLE transaction
            if (settlementRepository.existsByPaymentId(paymentId)) {
                log.info("Settlement already exists paymentId={} — skipping duplicate", paymentId);
                duplicateCounter.increment();
                ack.acknowledge();
                return;
            }

            Map<String, Object> event = objectMapper.readValue(record.value(), MAP_TYPE);
            String correlationId = (String) event.getOrDefault("correlationId", "");
            if (!correlationId.isEmpty()) MDC.put("correlationId", correlationId);
            SettlementRecord result = settlementService.settlePayment(event);
            String resultJson = objectMapper.writeValueAsString(result);

            kafkaTemplate.send(KafkaTopics.PAYMENT_SETTLED, paymentId, resultJson);
            kafkaTemplate.send(KafkaTopics.ANALYTICS_SETTLEMENT, paymentId, resultJson);

            settledCounter.increment();
            ack.acknowledge();

        } catch (Exception ex) {
            log.error("Settlement processing failed paymentId={}: {}", paymentId, ex.getMessage(), ex);
            dlqPublisher.publish(KafkaTopics.PAYMENT_ROUTED, paymentId, record.value(), 1, ex);
            ack.acknowledge();
        } finally {
            MDC.remove("paymentId");
            MDC.remove("correlationId");
        }
    }
}
