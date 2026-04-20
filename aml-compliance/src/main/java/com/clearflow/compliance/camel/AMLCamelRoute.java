package com.clearflow.compliance.camel;

import com.clearflow.common.messaging.KafkaTopics;
import com.clearflow.common.messaging.MQQueues;
import com.clearflow.compliance.processor.AMLScreeningProcessor;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

@Component
public class AMLCamelRoute extends RouteBuilder {

    private final AMLScreeningProcessor amlScreeningProcessor;

    public AMLCamelRoute(AMLScreeningProcessor amlScreeningProcessor) {
        this.amlScreeningProcessor = amlScreeningProcessor;
    }

    @Override
    public void configure() {
        from("jms:queue:" + MQQueues.PAYMENT_VALIDATED + "?concurrentConsumers=8")
                .routeId("aml-screening-route")
                .process(amlScreeningProcessor)
                .choice()
                    .when(header("aml.result").isEqualTo("HIT"))
                        .to("jms:queue:" + MQQueues.PAYMENT_SANCTIONS_HIT)
                        .to("kafka:" + KafkaTopics.AML_SANCTIONS_HIT)
                        .to("kafka:" + KafkaTopics.COMPLIANCE_ALERTS)
                        .log("COMPLIANCE ALERT: Payment ${header.paymentId} sanctions HIT")
                    .otherwise()
                        .to("jms:queue:" + MQQueues.PAYMENT_SANCTIONS_CLEAR)
                        .to("kafka:" + KafkaTopics.AML_SANCTIONS_CLEAR)
                        .log("Payment ${header.paymentId} sanctions clear")
                .end();
    }
}
