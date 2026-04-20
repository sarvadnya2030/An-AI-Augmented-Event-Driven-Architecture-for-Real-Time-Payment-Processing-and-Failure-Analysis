package com.clearflow.routing.processor;

import com.clearflow.routing.domain.PaymentRail;
import com.clearflow.routing.domain.RoutingContext;
import com.clearflow.routing.service.RailSelectionEngine;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

@Component
public class RailSelectionProcessor implements Processor {

    private final RailSelectionEngine railSelectionEngine;

    public RailSelectionProcessor(RailSelectionEngine railSelectionEngine) {
        this.railSelectionEngine = railSelectionEngine;
    }

    @Override
    public void process(Exchange exchange) {
        RoutingContext ctx = new RoutingContext(
                exchange.getIn().getHeader("amount", BigDecimal.class),
                exchange.getIn().getHeader("currency", String.class),
                exchange.getIn().getHeader("debtorCountry", String.class),
                exchange.getIn().getHeader("creditorCountry", String.class),
                exchange.getIn().getHeader("debtorBic", String.class),
                exchange.getIn().getHeader("creditorBic", String.class),
                exchange.getIn().getHeader("channel", String.class),
                true,
                Map.of()
        );
        PaymentRail rail = railSelectionEngine.selectRail(ctx);
        exchange.getIn().setHeader("rail.selected", rail.name());
        exchange.getIn().setHeader("rail.expectedSettlementTime", rail.getExpectedSettlementTime());
    }
}
