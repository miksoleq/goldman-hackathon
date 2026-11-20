package com.goldman.alerting.control.alerts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "alerts.kafka.enabled", havingValue = "true")
class LiveAlertListener {
    private static final Logger log = LoggerFactory.getLogger(LiveAlertListener.class);
    private final ObjectReader reader;
    private final Counter received;
    private final Counter rejected;

    LiveAlertListener(ObjectMapper mapper, MeterRegistry metrics) {
        reader = mapper.readerFor(LiveAlert.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        received = metrics.counter("alerts.live.records", "result", "received");
        rejected = metrics.counter("alerts.live.records", "result", "rejected");
    }

    @KafkaListener(topics = "${alerts.kafka.topic}", groupId = "${alerts.kafka.group}")
    void receive(ConsumerRecord<String, String> record) {
        try {
            LiveAlert alert = reader.readValue(record.value());
            if (alert == null || !alert.alertId().toString().equals(record.key())) {
                throw new IllegalArgumentException("Expected alertId as the Kafka record key");
            }
            received.increment();
            log.info("Received {} alertId={} ruleId={} count={}", alert.type(), alert.alertId(), alert.ruleId(), alert.count());
        } catch (JsonProcessingException | IllegalArgumentException e) {
            rejected.increment();
            log.warn("Invalid live alert at {} partition={} offset={}", record.topic(), record.partition(), record.offset());
        }
    }
}
