package com.goldman.alerting.engine.ingest;

import com.goldman.alerting.contracts.LiveAlert;
import java.util.List;

public interface IngestPublisher {

    record MatchedAlert(LiveAlert alert, String fingerprint, int windowSeconds) {}

    sealed interface BrokerRecord permits RedisStreamRecord, KafkaRecord {}

    record RedisStreamRecord(String stream, String consumerGroup, String recordId) implements BrokerRecord {}

    record KafkaRecord(String consumerGroup, String topic, int partition, long offset) implements BrokerRecord {}

    void publish(MessageEnvelope envelope, List<MatchedAlert> alerts);

    default void publish(MessageEnvelope envelope, List<MatchedAlert> alerts, BrokerRecord brokerRecord) {
        publish(envelope, alerts);
    }
}
