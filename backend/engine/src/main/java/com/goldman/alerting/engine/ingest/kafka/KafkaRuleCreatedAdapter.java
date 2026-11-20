package com.goldman.alerting.engine.ingest.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.engine.rules.RuleCreatedEvent;
import com.goldman.alerting.engine.rules.RuleIndexManager;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "rules.kafka", name = "enabled", havingValue = "true")
public class KafkaRuleCreatedAdapter {

    private static final Logger logger = LoggerFactory.getLogger(KafkaRuleCreatedAdapter.class);

    private final ObjectMapper objectMapper;
    private final RuleIndexManager ruleIndexManager;

    public KafkaRuleCreatedAdapter(ObjectMapper objectMapper, RuleIndexManager ruleIndexManager) {
        this.objectMapper = objectMapper;
        this.ruleIndexManager = ruleIndexManager;
    }

    @KafkaListener(
            topics = "${rules.kafka.topic:rules.created.v1}",
            groupId = "${rules.kafka.consumer-group:engine-rules}"
    )
    public void onMessage(ConsumerRecord<String, String> record) {
        try {
            RuleCreatedEvent event = RuleCreatedEvent.fromJson(record.value(), objectMapper);
            if (event.enabled()) {
                ruleIndexManager.accept(event.toRuleDefinition());
            }
        } catch (RuntimeException exception) {
            logger.warn("Dropping invalid rule event at {}-{}: {}",
                    record.topic(), record.partition(), exception.getMessage());
        }
    }
}
