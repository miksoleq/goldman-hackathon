package com.goldman.alerting.engine.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.engine.ingest.kafka.KafkaExternalAdapter;
import com.goldman.alerting.engine.rules.RuleIndexManager;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class IngestionServiceTest {
    private final ArrayList<MessageEnvelope> events = new ArrayList<>();
    private final IngestionService service = new IngestionService(new ObjectMapper(),
            (event, alerts) -> events.add(event), mock(RuleIndexManager.class), 6);

    @Test void utf8LimitMatchesEncoderForBoundaryAndMalformedCharacters() {
        String[] inputs = {"", "abc", "\u017c", "\u20ac", "\ud83d\ude00", "\ud800", "\udc00", "a\ud800b\ud83d\ude00"};
        for (String input : inputs) {
            int length = input.getBytes(StandardCharsets.UTF_8).length;
            for (int limit = 0; limit <= length + 1; limit++) {
                assertEquals(length > limit, IngestionService.exceedsUtf8Limit(input, limit));
            }
        }
    }

    @Test void textIsVerbatimAndLimitedByUtf8Bytes() {
        var event = service.acceptText("worker", "http", " ż12 ");
        assertEquals("text", event.contentType());
        assertEquals(" ż12 ", event.payload().required("message").textValue());
        assertThrows(PayloadTooLargeException.class, () -> service.acceptText("worker", "http", " ż123 "));
        assertEquals(1, events.size());
    }

    @Test void kafkaKeepsTopicAndJsonContentTypeIncludingForJsonStrings() {
        new KafkaExternalAdapter(service).onMessage(new ConsumerRecord<>("orders", 0, 12, null, "\"hi\""));
        var event = events.getFirst();
        assertEquals("orders", event.source());
        assertEquals("kafka", event.adapter());
        assertEquals("json", event.contentType());
        assertEquals("hi", event.payload().textValue());
    }

    @Test void kafkaUsesTopicPartitionAndOffsetAsItsRetryKey() {
        var ingestion = mock(IngestionService.class);
        new KafkaExternalAdapter(ingestion).onMessage(new ConsumerRecord<>("orders", 2, 19, null, "{}"));
        org.mockito.Mockito.verify(ingestion).accept("orders", "kafka", "{}",
                new IngestPublisher.KafkaRecord("engine", "orders", 2, 19));
    }

    @Test void brokerIdempotencyKeyIsForwardedToPublisher() {
        var keys = new ArrayList<String>();
        IngestPublisher publisher = new IngestPublisher() {
            @Override
            public void publish(MessageEnvelope envelope, List<MatchedAlert> alerts) {
                Assertions.fail("Broker records should use the idempotent publisher path");
            }

            @Override
            public void publish(MessageEnvelope envelope, List<MatchedAlert> alerts, IngestPublisher.BrokerRecord record) {
                var kafkaRecord = (IngestPublisher.KafkaRecord) record;
                keys.add(kafkaRecord.consumerGroup() + ":" + kafkaRecord.topic() + ":"
                        + kafkaRecord.partition() + ":" + kafkaRecord.offset());
            }
        };
        var ingest = new IngestionService(new ObjectMapper(), publisher, mock(RuleIndexManager.class), 32);

        ingest.accept("orders", "kafka", "{}", new IngestPublisher.KafkaRecord("engine", "orders", 2, 19));

        assertEquals(List.of("engine:orders:2:19"), keys);
    }

    @Test void incompleteOrMultipleJsonValuesAreRejected() {
        for (String input : new String[]{"{", " ", "1 2", "{}!"}) {
            assertThrows(InvalidJsonException.class, () -> service.accept("orders", "http", input));
        }
        assertTrue(events.isEmpty());
    }
}
