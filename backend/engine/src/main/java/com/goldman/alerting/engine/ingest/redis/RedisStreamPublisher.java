package com.goldman.alerting.engine.ingest.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.engine.ingest.IngestPublisher;
import com.goldman.alerting.engine.ingest.MessageEnvelope;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisStreamPublisher implements IngestPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String streamName;

    public RedisStreamPublisher(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${ingest.redis.internal-stream:events:ingest}") String streamName
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.streamName = streamName;
    }

    @Override
    public void publish(MessageEnvelope envelope) {
        try {
            String json = objectMapper.writeValueAsString(envelope);
            redisTemplate.opsForStream().add(streamName, Map.of("event", json));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize ingested event", exception);
        }
    }
}
