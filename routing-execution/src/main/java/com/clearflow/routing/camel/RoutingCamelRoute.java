package com.clearflow.routing.camel;

import com.clearflow.common.messaging.KafkaTopics;
import com.clearflow.common.messaging.MQQueues;
import com.clearflow.routing.processor.LiquidityReservationProcessor;
import com.clearflow.routing.processor.RailSelectionProcessor;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

@Component
public class RoutingCamelRoute extends RouteBuilder {

    private final RailSelectionProcessor railSelectionProcessor;
    private final LiquidityReservationProcessor liquidityReservationProcessor;

    public RoutingCamelRoute(RailSelectionProcessor railSelectionProcessor,
                             LiquidityReservationProcessor liquidityReservationProcessor) {
        this.railSelectionProcessor = railSelectionProcessor;
        this.liquidityReservationProcessor = liquidityReservationProcessor;
    }

    @Override
    public void configure() {
        from("jms:queue:" + MQQueues.PAYMENT_SANCTIONS_CLEAR + "?concurrentConsumers=8")
                .routeId("payment-routing-route")
                .process(railSelectionProcessor)
                .process(liquidityReservationProcessor)
                .choice()
                    .when(header("liquidity.reserved").isEqualTo(true))
                        .to("jms:queue:" + MQQueues.PAYMENT_ROUTED)
                        .to("kafka:" + KafkaTopics.PAYMENT_ROUTED)
                    .otherwise()
                        .to("jms:queue:" + MQQueues.PAYMENT_INSUFFICIENT_LIQUIDITY)
                        .to("kafka:" + KafkaTopics.PAYMENT_FAILED)
                .end();
    }
}
