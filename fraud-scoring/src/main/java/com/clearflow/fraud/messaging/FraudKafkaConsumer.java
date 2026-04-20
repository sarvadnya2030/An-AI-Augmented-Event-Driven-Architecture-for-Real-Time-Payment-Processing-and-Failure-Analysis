package com.clearflow.fraud.messaging;

import com.clearflow.common.domain.FraudEvaluatedEvent;
import com.clearflow.common.domain.PaymentInitiatedEvent;
import com.clearflow.common.messaging.KafkaTopics;
import com.clearflow.fraud.domain.FraudRequest;
import com.clearflow.fraud.domain.FraudResponse;
import com.clearflow.fraud.domain.RiskBand;
import com.clearflow.fraud.service.FraudScoringService;
import com.clearflow.fraud.service.VelocityCheckService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

@Component
public class FraudKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(FraudKafkaConsumer.class);

    private final FraudScoringService fraudScoringService;
    private final VelocityCheckService velocityCheckService;
    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public FraudKafkaConsumer(FraudScoringService fraudScoringService,
                              VelocityCheckService velocityCheckService,
                              StringRedisTemplate redisTemplate,
                              KafkaTemplate<String, Object> kafkaTemplate,
                              ObjectMapper objectMapper) {
        this.fraudScoringService = fraudScoringService;
        this.velocityCheckService = velocityCheckService;
        this.redisTemplate = redisTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_INITIATED, groupId = "fraud-scoring")
    public void onPaymentInitiated(PaymentInitiatedEvent event) {
        FraudRequest request = new FraudRequest(
                event.paymentId(),
                event.correlationId(),
                event.debtorIban(),
                event.creditorIban(),
                event.amount(),
                event.currency(),
                event.debtorCountry(),
                event.creditorCountry(),
                event.channel(),
                event.initiatedAt()
        );

        velocityCheckService.addPayment(event.debtorIban(), event.paymentId(), System.currentTimeMillis());
        FraudResponse response = fraudScoringService.score(request);

        try {
            redisTemplate.opsForValue().set("fraud:score:" + event.paymentId(), objectMapper.writeValueAsString(response), Duration.ofMinutes(10));
        } catch (JsonProcessingException e) {
            log.warn("Unable to cache fraud response paymentId={}", event.paymentId(), e);
        }

        FraudEvaluatedEvent evaluatedEvent = new FraudEvaluatedEvent(
                response.paymentId(),
                event.correlationId(),
                response.fraudScore(),
                response.riskBand().name(),
                response.featureImportance(),
                response.modelVersion(),
                response.fallbackUsed(),
                response.scoredAt(),
                response.processingTimeMs()
        );
        kafkaTemplate.send(KafkaTopics.FRAUD_EVALUATED, event.paymentId(), evaluatedEvent);

        if (response.riskBand() == RiskBand.CRITICAL) {
            kafkaTemplate.send(KafkaTopics.PAYMENT_BLOCKED, event.paymentId(), evaluatedEvent);
        }
    }
}
