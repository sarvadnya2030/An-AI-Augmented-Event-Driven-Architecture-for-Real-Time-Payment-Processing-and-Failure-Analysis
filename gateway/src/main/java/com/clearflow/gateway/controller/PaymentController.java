package com.clearflow.gateway.controller;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.clearflow.common.domain.PaymentInitiatedEvent;
import com.clearflow.common.security.MaskedIbanSerializer;
import com.clearflow.gateway.domain.PaymentRequest;
import com.clearflow.gateway.domain.PaymentResponse;
import com.clearflow.gateway.domain.PaymentStatus;
import com.clearflow.gateway.domain.PaymentStatusResponse;
import com.clearflow.gateway.messaging.ActiveMQPublisher;
import com.clearflow.gateway.messaging.KafkaEventPublisher;
import com.clearflow.gateway.messaging.SolacePublisher;
import com.clearflow.gateway.service.IdempotencyService;
import com.clearflow.gateway.service.RateLimitingFilter;
import com.clearflow.gateway.status.PaymentStatusService;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/payments")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Payment Ingestion")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final IdempotencyService idempotencyService;
    private final RateLimitingFilter rateLimitingFilter;
    private final ActiveMQPublisher activeMQPublisher;
    private final SolacePublisher solacePublisher;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final PaymentStatusService paymentStatusService;
    private final MeterRegistry meterRegistry;

    public PaymentController(IdempotencyService idempotencyService,
                             RateLimitingFilter rateLimitingFilter,
                             ActiveMQPublisher activeMQPublisher,
                             SolacePublisher solacePublisher,
                             KafkaEventPublisher kafkaEventPublisher,
                             PaymentStatusService paymentStatusService,
                             MeterRegistry meterRegistry) {
        this.idempotencyService = idempotencyService;
        this.rateLimitingFilter = rateLimitingFilter;
        this.activeMQPublisher = activeMQPublisher;
        this.solacePublisher = solacePublisher;
        this.kafkaEventPublisher = kafkaEventPublisher;
        this.paymentStatusService = paymentStatusService;
        this.meterRegistry = meterRegistry;
    }

    @PostMapping
    @Operation(summary = "Ingest ISO 20022 pacs.008 credit transfer request into ClearFlow hub")
    @ApiResponse(responseCode = "202", description = "Payment accepted for orchestration")
    @ApiResponse(responseCode = "400", description = "Validation failed for payload fields")
    @ApiResponse(responseCode = "409", description = "Duplicate payment detected by idempotency signature")
    @ApiResponse(responseCode = "429", description = "Client exceeds per-second rate limit")
    @ApiResponse(responseCode = "401", description = "JWT missing, invalid, or expired")
    public Mono<ResponseEntity<PaymentResponse>> submitPayment(@Valid @RequestBody PaymentRequest request,
                                                               @AuthenticationPrincipal Jwt jwt,
                                                               @RequestHeader(value = "X-Correlation-Id", required = false) String incomingCorrelationId) {
        String clientId = jwt != null && jwt.getSubject() != null ? jwt.getSubject() : "anonymous";
        String paymentId = UUID.randomUUID().toString();
        String correlationId = incomingCorrelationId != null ? incomingCorrelationId : UUID.randomUUID().toString();

        MDC.put("clientId", clientId);
        MDC.put("paymentId", paymentId);
        MDC.put("correlationId", correlationId);
        MDC.put("debtorCountry", request.debtor().country());
        MDC.put("creditorCountry", request.creditor().country());
        MDC.put("amount", request.amount().toPlainString());
        MDC.put("currency", request.currency());

        return idempotencyService.checkAndStore(request, paymentId, correlationId)
                .flatMap(idempotencyResult -> {
                    if (idempotencyResult.duplicate()) {
                        return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).body(idempotencyResult.cachedResponse()));
                    }

                    return rateLimitingFilter.checkLimit(clientId)
                            .flatMap(rate -> {
                                if (!rate.allowed()) {
                                    return Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                                            .header("X-RateLimit-Remaining", String.valueOf(rate.remaining()))
                                            .body(new PaymentResponse(
                                                    paymentId,
                                                    correlationId,
                                                    PaymentStatus.REJECTED,
                                                    Instant.now(),
                                                    "N/A",
                                                    "Rate limit exceeded",
                                                    Map.of("self", "/api/v1/payments/" + paymentId)
                                            )));
                                }

                                PaymentInitiatedEvent event = buildEvent(paymentId, correlationId, request);

                                PaymentResponse response = new PaymentResponse(
                                        paymentId,
                                        correlationId,
                                        PaymentStatus.ACCEPTED,
                                        Instant.now(),
                                        "PT2H",
                                        "Payment accepted and queued for processing",
                                        Map.of(
                                                "self", "/api/v1/payments/" + paymentId,
                                                "status", "/api/v1/payments/" + paymentId + "/status",
                                                "audit", "/api/v1/audit/" + paymentId
                                        )
                                );

                                Mono.fromRunnable(() -> {
                                    activeMQPublisher.publish(event, clientId);
                                    solacePublisher.publish(event);
                                    kafkaEventPublisher.publish(event, "00-" + paymentId.replace("-", "") + "-0000000000000000-01", "");
                                    paymentStatusService.updateStatus(paymentId, PaymentStatus.INITIATED,
                                            "gateway", "Payment accepted and queued").subscribe();
                                    paymentStatusService.storeUetrMapping(request.uetr(), paymentId).subscribe();
                                    log.info("PAYMENT_SUBMITTED paymentId={} debtorCountry={} creditorCountry={} amount={} currency={} channel={}",
                                            paymentId, request.debtor().country(), request.creditor().country(),
                                            request.amount(), request.currency(), request.channel());
                                    Counter.builder("clearflow_payments_total")
                                            .tag("service", "gateway")
                                            .tag("status", "INITIATED")
                                            .tag("currency", request.currency())
                                            .description("Total payments submitted to ClearFlow")
                                            .register(meterRegistry)
                                            .increment();
                                }).subscribeOn(Schedulers.boundedElastic()).subscribe();

                                return idempotencyService.cacheResponse(response)
                                        .thenReturn(ResponseEntity.accepted()
                                                .header("X-RateLimit-Remaining", String.valueOf(rate.remaining()))
                                                .body(response));
                            });
                })
                .doFinally(signal -> {
                    MDC.remove("clientId");
                    MDC.remove("paymentId");
                    MDC.remove("correlationId");
                    MDC.remove("debtorCountry");
                    MDC.remove("creditorCountry");
                    MDC.remove("amount");
                    MDC.remove("currency");
                });
    }

    @GetMapping("/{paymentId}/status")
    @Operation(summary = "Retrieve current orchestration state for a payment")
    public Mono<ResponseEntity<PaymentStatusResponse>> status(@PathVariable String paymentId) {
        return paymentStatusService.getStatus(paymentId).map(ResponseEntity::ok);
    }

    private PaymentInitiatedEvent buildEvent(String paymentId, String correlationId, PaymentRequest request) {
        return new PaymentInitiatedEvent(
                paymentId,
                correlationId,
                request.instructionId(),
                request.endToEndId(),
                request.uetr(),
                MaskedIbanSerializer.mask(request.debtor().iban()),
                MaskedIbanSerializer.mask(request.creditor().iban()),
                request.amount(),
                request.currency(),
                request.debtor().country(),
                request.creditor().country(),
                request.channel().name(),
                Instant.now(),
                "gateway"
        );
    }
}
