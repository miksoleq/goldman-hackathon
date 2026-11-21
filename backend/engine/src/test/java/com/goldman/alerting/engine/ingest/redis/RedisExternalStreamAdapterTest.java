package com.goldman.alerting.engine.ingest.redis;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.goldman.alerting.engine.ingest.IngestionService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.*;

class RedisExternalStreamAdapterTest {
    @Test
    void acknowledgesSuccessfulAndInvalidRecordsTogetherButLeavesFailuresPending() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        IngestionService ingestion = mock(IngestionService.class);
        @SuppressWarnings("unchecked") StreamOperations<String, String, String> streams = mock(StreamOperations.class);
        when(redis.<String, String>opsForStream()).thenReturn(streams);
        var ok = StreamRecords.newRecord().ofMap(Map.of("payload", "{}", "source", "ok")).withStreamKey("input").withId(RecordId.of("1-0"));
        var invalid = StreamRecords.newRecord().ofMap(Map.of("source", "bad")).withStreamKey("input").withId(RecordId.of("2-0"));
        var failed = StreamRecords.newRecord().ofMap(Map.of("payload", "{}", "source", "failed")).withStreamKey("input").withId(RecordId.of("3-0"));
        when(streams.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class))).thenReturn(List.of(ok, invalid, failed));
        doThrow(new IllegalStateException("unavailable")).when(ingestion).accept("failed", "redis", "{}");
        var adapter = new RedisExternalStreamAdapter(redis, ingestion, "input", "group", "consumer", 200);
        adapter.run(null);
        adapter.poll();
        verify(streams).acknowledge("input", "group", RecordId.of("1-0"), RecordId.of("2-0"));
        verify(streams, times(1)).acknowledge(eq("input"), eq("group"), any(RecordId[].class));
    }

    @Test
    void rejectsNonPositiveBatchSize() {
        assertThrows(IllegalArgumentException.class, () -> new RedisExternalStreamAdapter(
                mock(StringRedisTemplate.class), mock(IngestionService.class), "input", "group", "consumer", 0));
    }
}
