package com.goldman.alerting.engine.ingest.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.engine.ingest.IngestPublisher;
import com.goldman.alerting.engine.ingest.MessageEnvelope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.ArrayList;
import java.util.HexFormat;
import com.goldman.alerting.contracts.LiveAlert;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisStreamPublisher implements IngestPublisher {

    private static final DefaultRedisScript<Long> PUBLISH = new DefaultRedisScript<>("""
            local mode = ARGV[2]
            local firstDedupKey = 3
            local firstAlertArgument = 3
            if mode == 'redis-stream' then
                local pending = redis.call('XPENDING', KEYS[3], ARGV[3], ARGV[4], ARGV[4], 1)
                if #pending == 0 then
                    return -1
                end
                firstDedupKey = 4
                firstAlertArgument = 5
            elseif mode == 'kafka' then
                local previousOffset = redis.call('GET', KEYS[3])
                if previousOffset and tonumber(previousOffset) >= tonumber(ARGV[5]) then
                    return -1
                end
                firstDedupKey = 4
                firstAlertArgument = 7
            end
            redis.call('XADD', KEYS[1], '*', 'event', ARGV[1])
            for i = firstDedupKey, #KEYS do
                local arg = firstAlertArgument + (i - firstDedupKey) * 3
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
            if mode == 'redis-stream' then
                redis.call('XACK', KEYS[3], ARGV[3], ARGV[4])
            elseif mode == 'kafka' then
                redis.call('SET', KEYS[3], ARGV[5], 'EX', ARGV[6])
            end
            return #KEYS - firstDedupKey + 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String streamName;
    private final String alertStream;
    private final int kafkaOffsetTtlSeconds;

    public RedisStreamPublisher(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${ingest.redis.internal-stream:events:ingest}") String streamName,
            @Value("${alerts.stream:" + LiveAlert.STREAM + "}") String alertStream
    ) {
        this(redisTemplate, objectMapper, streamName, alertStream, 604800);
    }

    @Autowired
    public RedisStreamPublisher(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${ingest.redis.internal-stream:events:ingest}") String streamName,
            @Value("${alerts.stream:" + LiveAlert.STREAM + "}") String alertStream,
            @Value("${ingest.kafka.offset-ttl-seconds:604800}") int kafkaOffsetTtlSeconds
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.streamName = streamName;
        this.alertStream = alertStream;
        if (kafkaOffsetTtlSeconds < 1) {
            throw new IllegalArgumentException("ingest.kafka.offset-ttl-seconds must be positive");
        }
        this.kafkaOffsetTtlSeconds = kafkaOffsetTtlSeconds;
    }

    @Override
    public void publish(MessageEnvelope envelope, List<MatchedAlert> alerts) {
        executePublish(envelope, alerts, null);
    }

    @Override
    public void publish(MessageEnvelope envelope, List<MatchedAlert> alerts, BrokerRecord brokerRecord) {
        executePublish(envelope, alerts, brokerRecord);
    }

    private void executePublish(MessageEnvelope envelope, List<MatchedAlert> alerts, BrokerRecord brokerRecord) {
        try {
            var keys = new ArrayList<>(List.of(streamName, alertStream));
            var args = new ArrayList<String>();
            args.add(objectMapper.writeValueAsString(envelope));
            if (brokerRecord == null) {
                args.add("none");
            } else if (brokerRecord instanceof RedisStreamRecord redisRecord) {
                requireNonBlank(redisRecord.stream(), "stream");
                requireNonBlank(redisRecord.consumerGroup(), "consumerGroup");
                requireNonBlank(redisRecord.recordId(), "recordId");
                keys.add(redisRecord.stream());
                args.add("redis-stream");
                args.add(redisRecord.consumerGroup());
                args.add(redisRecord.recordId());
            } else if (brokerRecord instanceof KafkaRecord kafkaRecord) {
                requireNonBlank(kafkaRecord.consumerGroup(), "consumerGroup");
                requireNonBlank(kafkaRecord.topic(), "topic");
                if (kafkaRecord.partition() < 0 || kafkaRecord.offset() < 0) {
                    throw new IllegalArgumentException("Kafka partition and offset must not be negative");
                }
                keys.add(streamName + ":kafka-offset:" + sha256(kafkaRecord.consumerGroup() + ":"
                        + kafkaRecord.topic() + ":" + kafkaRecord.partition()));
                args.add("kafka");
                args.add(kafkaRecord.topic());
                args.add(Integer.toString(kafkaRecord.partition()));
                args.add(Long.toString(kafkaRecord.offset()));
                args.add(Integer.toString(kafkaOffsetTtlSeconds));
            } else {
                throw new IllegalArgumentException("Unsupported broker record type");
            }
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

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
