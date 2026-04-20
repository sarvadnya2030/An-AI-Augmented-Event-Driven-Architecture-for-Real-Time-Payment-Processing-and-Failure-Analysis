package com.clearflow.common.domain;

import com.clearflow.common.security.MaskedIbanSerializer;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

public record PaymentInitiatedEvent(
        String paymentId,
        String correlationId,
        String instructionId,
        String endToEndId,
        String uetr,
        String debtorIban,
        String creditorIban,
        BigDecimal amount,
        String currency,
        String debtorCountry,
        String creditorCountry,
        String channel,
        Instant initiatedAt,
        String sourceService
) implements Serializable {

    public PaymentInitiatedEvent withMaskedIbans() {
        return new PaymentInitiatedEvent(
                paymentId,
                correlationId,
                instructionId,
                endToEndId,
                uetr,
                MaskedIbanSerializer.mask(debtorIban),
                MaskedIbanSerializer.mask(creditorIban),
                amount,
                currency,
                debtorCountry,
                creditorCountry,
                channel,
                initiatedAt,
                sourceService
        );
    }
}
