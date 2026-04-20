package com.clearflow.validation.camel;

import com.clearflow.common.domain.EnrichedPaymentEvent;
import com.clearflow.common.domain.PaymentInitiatedEvent;
import com.clearflow.common.messaging.KafkaTopics;
import com.clearflow.common.messaging.MQQueues;
import com.clearflow.validation.processor.BICValidationProcessor;
import com.clearflow.validation.processor.CurrencyValidationProcessor;
import com.clearflow.validation.processor.EmbargoPreCheckProcessor;
import com.clearflow.validation.processor.EnrichmentProcessor;
import com.clearflow.validation.processor.IBANValidationProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jackson.JacksonDataFormat;
import org.springframework.stereotype.Component;

@Component
public class ValidationEnrichmentCamelRoute extends RouteBuilder {

    private final IBANValidationProcessor ibanValidationProcessor;
    private final BICValidationProcessor bicValidationProcessor;
    private final CurrencyValidationProcessor currencyValidationProcessor;
    private final EmbargoPreCheckProcessor embargoPreCheckProcessor;
    private final EnrichmentProcessor enrichmentProcessor;

    public ValidationEnrichmentCamelRoute(IBANValidationProcessor ibanValidationProcessor,
                                          BICValidationProcessor bicValidationProcessor,
                                          CurrencyValidationProcessor currencyValidationProcessor,
                                          EmbargoPreCheckProcessor embargoPreCheckProcessor,
                                          EnrichmentProcessor enrichmentProcessor) {
        this.ibanValidationProcessor = ibanValidationProcessor;
        this.bicValidationProcessor = bicValidationProcessor;
        this.currencyValidationProcessor = currencyValidationProcessor;
        this.embargoPreCheckProcessor = embargoPreCheckProcessor;
        this.enrichmentProcessor = enrichmentProcessor;
    }

    private static JacksonDataFormat buildJsonFormat() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        JacksonDataFormat fmt = new JacksonDataFormat(mapper, EnrichedPaymentEvent.class);
        fmt.setUseDefaultObjectMapper(false);
        return fmt;
    }

    @Override
    public void configure() {
        onException(Exception.class)
                .log(LoggingLevel.ERROR, "payment-validation-enrichment",
                        "PIPELINE_EXCEPTION paymentId=${header.paymentId} ex=${exception.message}")
                .maximumRedeliveries(0)
                .to("jms:queue:" + MQQueues.PAYMENT_DLQ)
                .handled(true);

        errorHandler(deadLetterChannel("jms:queue:" + MQQueues.PAYMENT_DLQ)
                .maximumRedeliveries(3)
                .redeliveryDelay(1000)
                .useExponentialBackOff()
                .logRetryAttempted(true)
                .logExhausted(true)
                .logStackTrace(true));

        from("jms:queue:" + MQQueues.PAYMENT_INITIATED + "?concurrentConsumers=10")
                .routeId("payment-validation-enrichment")
                .process(exchange -> {
                    Object body = exchange.getIn().getBody();
                    if (body instanceof PaymentInitiatedEvent event) {
                        exchange.getIn().setHeader("paymentId", event.paymentId());
                        exchange.getIn().setHeader("correlationId", event.correlationId());
                        exchange.getIn().setHeader("debtorIban", event.debtorIban());
                        exchange.getIn().setHeader("creditorIban", event.creditorIban());
                        exchange.getIn().setHeader("debtorCurrency", event.currency());
                        exchange.getIn().setHeader("creditorCurrency", event.currency());
                        exchange.getIn().setHeader("currency", event.currency());
                        exchange.getIn().setHeader("amount", event.amount().toPlainString());
                        exchange.getIn().setHeader("debtorCountry", event.debtorCountry());
                        exchange.getIn().setHeader("creditorCountry", event.creditorCountry());
                        exchange.getIn().setHeader("channel", event.channel());
                        exchange.getIn().setHeader("debtorName", "");
                        exchange.getIn().setHeader("creditorName", "");
                        exchange.getIn().setHeader("debtorBic", null);
                        exchange.getIn().setHeader("creditorBic", null);
                    }
                })
                .log("Processing payment ${header.paymentId}")
                .process(ibanValidationProcessor)
                .log(LoggingLevel.DEBUG, "payment-validation-enrichment", "IBAN done status=${header.validation.status}")
                .process(bicValidationProcessor)
                .log(LoggingLevel.DEBUG, "payment-validation-enrichment", "BIC done")
                .process(currencyValidationProcessor)
                .log(LoggingLevel.INFO, "payment-validation-enrichment", "CURRENCY done status=${header.validation.status} ccy=${header.debtorCurrency}")
                .process(embargoPreCheckProcessor)
                .log(LoggingLevel.DEBUG, "payment-validation-enrichment", "EMBARGO done")
                .process(enrichmentProcessor)
                .log(LoggingLevel.INFO, "payment-validation-enrichment", "ENRICH done status=${header.validation.status}")
                .choice()
                    .when(header("validation.status").isEqualTo("VALID"))
                        .marshal(buildJsonFormat())
                        .to("jms:queue:" + MQQueues.PAYMENT_VALIDATED)
                        .to("kafka:" + KafkaTopics.PAYMENT_VALIDATED)
                        .log("Payment ${header.paymentId} validated and enriched")
                    .otherwise()
                        .to("jms:queue:" + MQQueues.PAYMENT_REJECTED)
                        .to("kafka:" + KafkaTopics.PAYMENT_REJECTED)
                        .log("Payment ${header.paymentId} rejected: ${header.rejection.reason}")
                .end();
    }
}
