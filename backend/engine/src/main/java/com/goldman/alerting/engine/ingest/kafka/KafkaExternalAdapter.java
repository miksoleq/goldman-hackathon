package com.goldman.alerting.engine.ingest.kafka;

import com.goldman.alerting.engine.ingest.IngestionService;
import com.goldman.alerting.engine.ingest.InvalidJsonException;
import com.goldman.alerting.engine.ingest.PayloadTooLargeException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "ingest.kafka", name = "enabled", havingValue = "true")
public class KafkaExternalAdapter {

    private static final Logger logger = LoggerFactory.getLogger(KafkaExternalAdapter.class);

    private final IngestionService ingestionService;

    public KafkaExternalAdapter(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @KafkaListener(topics = "${ingest.kafka.topic:ingest.external}")
    public void onMessage(ConsumerRecord<String, String> record) {
        try {
            ingestionService.accept(record.topic(), "kafka", record.value());
        } catch (InvalidJsonException | PayloadTooLargeException exception) {
            logger.warn("Dropping invalid Kafka ingest record at {}-{}", record.partition(), record.offset());
        }
    }
}
