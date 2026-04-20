package com.clearflow.routing.processor;

import com.clearflow.routing.service.LiquidityReservationService;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class LiquidityReservationProcessor implements Processor {

    private final LiquidityReservationService liquidityReservationService;

    public LiquidityReservationProcessor(LiquidityReservationService liquidityReservationService) {
        this.liquidityReservationService = liquidityReservationService;
    }

    @Override
    public void process(Exchange exchange) {
        String paymentId = exchange.getIn().getHeader("paymentId", String.class);
        String currency = exchange.getIn().getHeader("currency", String.class);
        String rail = exchange.getIn().getHeader("rail.selected", String.class);
        BigDecimal amount = exchange.getIn().getHeader("amount", BigDecimal.class);

        try {
            var result = liquidityReservationService.reserve(currency, amount, paymentId, rail);
            exchange.getIn().setHeader("liquidity.reserved", true);
            exchange.getIn().setHeader("nostro.accountId", result.accountId());
            exchange.getIn().setHeader("reservation.id", result.reservationId());
        } catch (Exception ex) {
            exchange.getIn().setHeader("liquidity.reserved", false);
            exchange.getIn().setHeader("liquidity.error", ex.getMessage());
        }
    }
}
