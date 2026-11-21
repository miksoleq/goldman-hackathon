package com.goldman.alerting.engine.ingest.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import com.goldman.alerting.engine.EngineApplication;
import com.goldman.alerting.engine.ingest.IngestionService;
import com.goldman.alerting.engine.rules.RuleIndexManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;

@SpringBootTest(classes = EngineApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"ingest.redis.enabled=false", "rules.redis.enabled=false"})
class RedisExternalStreamAdapterIT {
    @Autowired StringRedisTemplate redis;

    @Test
    void acknowledgesOnlyCompletedRecordsInRedis() {
        String stream = "it:external:" + UUID.randomUUID();
        var ingestion = mock(IngestionService.class);
        when(ingestion.accept(anyString(), eq("redis-stream"), anyString(),
                any(com.goldman.alerting.engine.ingest.IngestPublisher.RedisStreamRecord.class))).thenAnswer(invocation -> {
            if ("failed".equals(invocation.getArgument(0))) {
                throw new IllegalStateException("unavailable");
            }
            return null;
        });
        var adapter = new RedisExternalStreamAdapter(redis, ingestion, stream, "group", "consumer", 2, 10);
        try {
            adapter.run(null);
            var operations = redis.<String, String>opsForStream();
            operations.add(stream, Map.of("source", "ok", "payload", "{}"));
            operations.add(stream, Map.of("source", "invalid"));
            operations.add(stream, Map.of("source", "failed", "payload", "{}"));
            adapter.poll();
            assertEquals(1L, operations.pending(stream, "group").getTotalPendingMessages());
            verify(ingestion).accept(eq("ok"), eq("redis-stream"), eq("{}"),
                    any(com.goldman.alerting.engine.ingest.IngestPublisher.RedisStreamRecord.class));
            verify(ingestion).accept(eq("failed"), eq("redis-stream"), eq("{}"),
                    any(com.goldman.alerting.engine.ingest.IngestPublisher.RedisStreamRecord.class));
        } finally {
            redis.delete(List.of(stream));
        }
    }

    @Test
    void reclaimsPendingWorkAfterReplicaFailureWithoutPublishingItTwice() {
        String prefix = "it:reclaim:" + UUID.randomUUID();
        String input = prefix + ":external";
        String output = prefix + ":events";
        String alerts = prefix + ":alerts";
        var operations = redis.<String, String>opsForStream();
        var ingestion = new IngestionService(new com.fasterxml.jackson.databind.ObjectMapper(),
                new RedisStreamPublisher(redis, new com.fasterxml.jackson.databind.ObjectMapper(), output, alerts, 3600),
                new RuleIndexManager(List.of()), 4096);
        var crashed = new RedisExternalStreamAdapter(redis, ingestion, input, "engine", "crashed", 10, 1,
                Duration.ofMillis(1));
        try {
            crashed.run(null);
            var inputId = operations.add(input, Map.of("source", "ok", "payload", "{}"));
            var claimedBeforeCrash = operations.read(Consumer.from("engine", "crashed"),
                    StreamReadOptions.empty().count(1), StreamOffset.create(input, ReadOffset.lastConsumed()));
            assertEquals(1, claimedBeforeCrash.size());

            var replacement = new RedisExternalStreamAdapter(redis, ingestion, input, "engine", "replacement", 10, 1,
                    Duration.ofMillis(1));
            replacement.run(null);
            Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> operations
                    .pending(input, "engine", Range.unbounded(), 1).get(0)
                    .getElapsedTimeSinceLastDelivery().toMillis() >= 1);
            replacement.reclaimPending();

            Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() ->
                    assertEquals(0L, operations.pending(input, "engine").getTotalPendingMessages()));
            assertEquals(1L, operations.size(output));

            // Redis acknowledges the source in the same Lua operation as publishing; a late replay is ignored.
            ingestion.accept("ok", "redis-stream", "{}",
                    new com.goldman.alerting.engine.ingest.IngestPublisher.RedisStreamRecord(
                            input, "engine", inputId.getValue()));
            assertEquals(1L, operations.size(output));
        } finally {
            var ownedKeys = redis.keys(prefix + ":*");
            if (ownedKeys != null && !ownedKeys.isEmpty()) redis.delete(ownedKeys);
        }
    }
}
