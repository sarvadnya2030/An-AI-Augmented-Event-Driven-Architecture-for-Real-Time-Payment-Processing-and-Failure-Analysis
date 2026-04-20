package com.clearflow.gateway.messaging;

import com.clearflow.common.domain.PaymentInitiatedEvent;
import com.clearflow.common.messaging.KafkaTopics;
import com.clearflow.common.resilience.CircuitBreakerNames;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
public class KafkaEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);
    private final KafkaTemplate<String, PaymentInitiatedEvent> kafkaTemplate;

    public KafkaEventPublisher(KafkaTemplate<String, PaymentInitiatedEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @CircuitBreaker(name = CircuitBreakerNames.KAFKA, fallbackMethod = "publishFallback")
    public void publish(PaymentInitiatedEvent event, String traceParent, String traceState) {
        Message<PaymentInitiatedEvent> message = MessageBuilder.withPayload(event)
                .setHeader(KafkaHeaders.TOPIC, KafkaTopics.PAYMENT_INITIATED)
                .setHeader(KafkaHeaders.KEY, event.paymentId())
                .setHeader("traceparent", traceParent)
                .setHeader("tracestate", traceState)
                .build();
        kafkaTemplate.send(message);
    }

    @SuppressWarnings("unused")
    void publishFallback(PaymentInitiatedEvent event, String traceParent, String traceState, Exception ex) {
        log.error("Kafka publish fallback triggered for paymentId={}", event.paymentId(), ex);
    }
}
