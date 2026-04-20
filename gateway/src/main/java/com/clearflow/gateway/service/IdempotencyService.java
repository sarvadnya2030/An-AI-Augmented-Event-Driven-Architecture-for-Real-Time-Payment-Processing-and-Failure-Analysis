package com.clearflow.gateway.service;

import com.clearflow.gateway.domain.IdempotencyResult;
import com.clearflow.gateway.domain.PaymentRequest;
import com.clearflow.gateway.domain.PaymentResponse;
import com.clearflow.gateway.domain.PaymentStatus;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Service
public class IdempotencyService {

    private final ReactiveRedisTemplate<String, PaymentResponse> responseRedis;
    private final ReactiveRedisTemplate<String, String> stringRedis;

    public IdempotencyService(@Qualifier("paymentResponseRedisTemplate") ReactiveRedisTemplate<String, PaymentResponse> responseRedis,
                              ReactiveRedisTemplate<String, String> stringRedis) {
        this.responseRedis = responseRedis;
        this.stringRedis = stringRedis;
    }

    public Mono<IdempotencyResult> checkAndStore(PaymentRequest request, String paymentId, String correlationId) {
        String signature = request.instructionId() + "|" + request.amount() + "|" + request.debtor().iban();
        String key = "idempotency:" + DigestUtils.sha256Hex(signature);
        return stringRedis.opsForValue().setIfAbsent(key, paymentId, Duration.ofHours(24))
                .flatMap(inserted -> {
                    if (Boolean.TRUE.equals(inserted)) {
                        return Mono.just(IdempotencyResult.accepted());
                    }
                    return stringRedis.opsForValue().get(key)
                            .flatMap(existingPaymentId -> responseRedis.opsForValue().get("payment:response:" + existingPaymentId))
                            .switchIfEmpty(Mono.fromSupplier(() -> new PaymentResponse(
                                    paymentId,
                                    correlationId,
                                    PaymentStatus.DUPLICATE,
                                    Instant.now(),
                                    "UNKNOWN",
                                    "Duplicate request detected by idempotency key",
                                    Map.of("status", "/api/v1/payments/" + paymentId + "/status")
                            )))
                            .map(IdempotencyResult::duplicate);
                });
    }

    public Mono<Boolean> cacheResponse(PaymentResponse response) {
        return responseRedis.opsForValue().set("payment:response:" + response.paymentId(), response, Duration.ofHours(24));
    }
}
