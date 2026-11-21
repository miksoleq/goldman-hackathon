package com.goldman.alerting.engine.ingest.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.engine.ingest.IngestPublisher;
import com.goldman.alerting.engine.ingest.MessageEnvelope;
import java.util.List;
import java.util.ArrayList;
import com.goldman.alerting.schema.LiveAlert;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisStreamPublisher implements IngestPublisher {

    private static final DefaultRedisScript<Long> PUBLISH = new DefaultRedisScript<>("""
            redis.call('XADD', KEYS[1], '*', 'event', ARGV[1])
            for i = 2, #ARGV do
                redis.call('PUBLISH', KEYS[2], ARGV[i])
            end
            return #ARGV - 1
            """, Long.class);

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
    public void publish(MessageEnvelope envelope, List<LiveAlert> alerts) {
        try {
            var args = new ArrayList<String>();
            args.add(objectMapper.writeValueAsString(envelope));
            for (LiveAlert alert : alerts) args.add(objectMapper.writeValueAsString(alert));
            redisTemplate.execute(PUBLISH, List.of(streamName, LiveAlert.CHANNEL), args.toArray());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize ingested event", exception);
        }
    }
}
