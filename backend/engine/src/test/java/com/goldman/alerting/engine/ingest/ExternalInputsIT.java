package com.goldman.alerting.engine.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.engine.ingest.file.FileLogAdapter;
import com.goldman.alerting.engine.ingest.redis.RedisExternalStreamAdapter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"ingest.max-payload-bytes=12", "ingest.redis.enabled=true"})
class ExternalInputsIT {
    private static final String EXTERNAL = "it:external:" + UUID.randomUUID();
    private static final String EVENTS = EXTERNAL + ":events";

    @DynamicPropertySource
    static void streams(DynamicPropertyRegistry properties) {
        properties.add("ingest.redis.external-stream", () -> EXTERNAL);
        properties.add("ingest.redis.internal-stream", () -> EVENTS);
    }

    @Autowired StringRedisTemplate redis;
    @Autowired IngestionService ingestion;
    @Autowired ObjectMapper mapper;

    private List<MessageEnvelope> envelopes(String adapter) {
        var records = redis.<String, String>opsForStream().range(EVENTS, Range.unbounded());
        return records.stream().map(record -> {
            try { return mapper.readValue(record.getValue().get("event"), MessageEnvelope.class); }
            catch (Exception e) { throw new AssertionError(e); }
        }).filter(event -> event.adapter().equals(adapter)).toList();
    }

    @Test void restartingRedisInputReusesTheExistingConsumerGroup() {
        var restarted = new RedisExternalStreamAdapter(redis, ingestion, EXTERNAL, "engine", "replacement", 100);
        assertDoesNotThrow(() -> restarted.run(null));
        assertEquals(1, redis.opsForStream().groups(EXTERNAL).size());
    }

    @Test void redisUsesStreamNameOrExplicitProducerSource() {
        var streams = redis.<String, String>opsForStream();
        streams.add(EXTERNAL, Map.of("payload", "null"));
        streams.add(EXTERNAL, Map.of("source", "orders", "payload", "[1]"));
        Awaitility.await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            var events = envelopes("redis-stream");
            assertEquals(List.of(EXTERNAL, "orders"), events.stream().map(MessageEnvelope::source).toList());
            assertEquals("json", events.getFirst().contentType());
            assertTrue(events.getFirst().payload().isNull());
            assertEquals(1, events.getLast().payload().get(0).intValue());
        });
    }

    @Test void fileResumesOnlyCompleteLinesAndSkipsOversizedRecords(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("worker.log");
        Files.writeString(log, "x".repeat(13) + "\nOOM 1\r\nOOM ");
        new FileLogAdapter(log, 12, ingestion, redis).poll();
        Files.writeString(log, "2\n", StandardOpenOption.APPEND);
        var restarted = new FileLogAdapter(log, 12, ingestion, redis);
        restarted.poll();
        restarted.poll();
        var events = envelopes("file");
        assertEquals(List.of("OOM 1", "OOM 2"), events.stream().map(e -> e.payload().required("message").asText()).toList());
        for (var event : events) {
            assertEquals(log.toString(), event.source());
            assertEquals("text", event.contentType());
        }
    }
}
