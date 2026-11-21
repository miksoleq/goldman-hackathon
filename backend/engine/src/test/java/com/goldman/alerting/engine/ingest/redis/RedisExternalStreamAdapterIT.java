package com.goldman.alerting.engine.ingest.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import com.goldman.alerting.engine.EngineApplication;
import com.goldman.alerting.engine.ingest.IngestionService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest(classes = EngineApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"ingest.redis.enabled=false", "rules.redis.enabled=false"})
class RedisExternalStreamAdapterIT {
    @Autowired StringRedisTemplate redis;

    @Test
    void acknowledgesOnlyCompletedRecordsInRedis() {
        String stream = "it:external:" + UUID.randomUUID();
        var ingestion = mock(IngestionService.class);
        doThrow(new IllegalStateException("unavailable")).when(ingestion).accept("failed", "redis", "{}");
        var adapter = new RedisExternalStreamAdapter(redis, ingestion, stream, "group", "consumer", 200);
        try {
            adapter.run(null);
            var operations = redis.<String, String>opsForStream();
            operations.add(stream, Map.of("source", "ok", "payload", "{}"));
            operations.add(stream, Map.of("source", "invalid"));
            operations.add(stream, Map.of("source", "failed", "payload", "{}"));
            adapter.poll();
            assertEquals(1L, operations.pending(stream, "group").getTotalPendingMessages());
            verify(ingestion).accept("ok", "redis", "{}");
            verify(ingestion).accept("failed", "redis", "{}");
        } finally {
            redis.delete(List.of(stream));
        }
    }
}
