package com.clearflow.settlement.camel;

import com.clearflow.common.messaging.KafkaTopics;
import com.clearflow.common.messaging.MQQueues;
import com.clearflow.settlement.processor.SettlementProcessor;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

@Component
public class SettlementCamelRoute extends RouteBuilder {

    private final SettlementProcessor settlementProcessor;

    public SettlementCamelRoute(SettlementProcessor settlementProcessor) {
        this.settlementProcessor = settlementProcessor;
    }

    @Override
    public void configure() {
        from("jms:queue:" + MQQueues.PAYMENT_ROUTED + "?concurrentConsumers=8")
                .routeId("payment-settlement-route")
                .process(settlementProcessor)
                .to("jms:queue:" + MQQueues.PAYMENT_SETTLED)
                .to("kafka:" + KafkaTopics.PAYMENT_SETTLED)
                .to("kafka:" + KafkaTopics.ANALYTICS_SETTLEMENT);
    }
}
