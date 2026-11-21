package com.goldman.alerting.engine.ingest.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.engine.ingest.IngestPublisher;
import com.goldman.alerting.engine.ingest.MessageEnvelope;
import java.util.List;
import java.util.ArrayList;
import com.goldman.alerting.contracts.LiveAlert;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisStreamPublisher implements IngestPublisher {

    private static final DefaultRedisScript<Long> PUBLISH = new DefaultRedisScript<>("""
            redis.call('XADD', KEYS[1], '*', 'event', ARGV[1])
            for i = 3, #KEYS do
                local arg = 2 + (i - 3) * 3
                local id = redis.call('HGET', KEYS[i], 'alertId')
                local payload = ARGV[arg]
                if id then
                    local count = redis.call('HINCRBY', KEYS[i], 'count', 1)
                    payload = cjson.encode({v=1, type='alert.counted', alertId=id, count=count})
                else
                    redis.call('HSET', KEYS[i], 'alertId', ARGV[arg + 1], 'count', 1)
                    redis.call('EXPIRE', KEYS[i], ARGV[arg + 2])
                end
                redis.call('XADD', KEYS[2], '*', 'alert', payload)
            end
            return #KEYS - 2
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String streamName;
    private final String alertStream;

    public RedisStreamPublisher(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${ingest.redis.internal-stream:events:ingest}") String streamName,
            @Value("${alerts.stream:" + LiveAlert.STREAM + "}") String alertStream
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.streamName = streamName;
        this.alertStream = alertStream;
    }

    @Override
    public void publish(MessageEnvelope envelope, List<MatchedAlert> alerts) {
        try {
            var keys = new ArrayList<>(List.of(streamName, alertStream));
            var args = new ArrayList<String>();
            args.add(objectMapper.writeValueAsString(envelope));
            for (var match : alerts) {
                keys.add(alertStream + ":dedup:" + match.fingerprint());
                args.add(objectMapper.writeValueAsString(match.alert()));
                args.add(match.alert().alertId().toString());
                args.add(Integer.toString(match.windowSeconds()));
            }
            redisTemplate.execute(PUBLISH, keys, args.toArray());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize ingested event", exception);
        }
    }
}
