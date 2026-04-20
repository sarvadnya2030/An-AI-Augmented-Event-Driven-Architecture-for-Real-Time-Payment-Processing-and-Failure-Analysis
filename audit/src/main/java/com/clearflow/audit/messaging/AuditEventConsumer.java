package com.clearflow.audit.messaging;

import com.clearflow.audit.domain.AuditRecord;
import com.clearflow.audit.repository.AuditRepository;
import com.clearflow.audit.service.HashChainService;
import com.clearflow.common.messaging.KafkaTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class AuditEventConsumer {

    private final HashChainService hashChainService;
    private final AuditRepository auditRepository;

    public AuditEventConsumer(HashChainService hashChainService, AuditRepository auditRepository) {
        this.hashChainService = hashChainService;
        this.auditRepository = auditRepository;
    }

    @KafkaListener(
            topics = {
                    KafkaTopics.PAYMENT_INITIATED,
                    KafkaTopics.PAYMENT_VALIDATED,
                    KafkaTopics.PAYMENT_REJECTED,
                    KafkaTopics.FRAUD_EVALUATED,
                    KafkaTopics.PAYMENT_BLOCKED,
                    KafkaTopics.AML_SANCTIONS_CLEAR,
                    KafkaTopics.AML_SANCTIONS_HIT,
                    KafkaTopics.COMPLIANCE_ALERTS,
                    KafkaTopics.PAYMENT_ROUTED,
                    KafkaTopics.PAYMENT_FAILED,
                    KafkaTopics.PAYMENT_SETTLED,
                    KafkaTopics.ANALYTICS_SETTLEMENT,
                    KafkaTopics.MCP_ACCESS_LOG
            },
            groupId = "audit-service"
    )
    public void onEvent(ConsumerRecord<String, String> record) {
        String paymentId = record.key() != null ? record.key() : extractPaymentId(record.value());
        AuditRecord auditRecord = hashChainService.createRecord(paymentId, record.topic(), record.value());
        auditRepository.save(auditRecord);
    }

    private String extractPaymentId(String json) {
        if (json == null) return "UNKNOWN";
        int idx = json.indexOf("paymentId");
        if (idx < 0) return "UNKNOWN";
        int colon = json.indexOf(':', idx);
        int quote1 = json.indexOf('"', colon);
        int quote2 = json.indexOf('"', quote1 + 1);
        return quote1 >= 0 && quote2 > quote1 ? json.substring(quote1 + 1, quote2) : "UNKNOWN";
    }
}
