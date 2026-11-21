package com.goldman.alerting.engine.ingest.redis;

import com.goldman.alerting.engine.ingest.IngestionService;
import com.goldman.alerting.engine.ingest.InvalidJsonException;
import com.goldman.alerting.engine.ingest.PayloadTooLargeException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "ingest.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisExternalStreamAdapter implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(RedisExternalStreamAdapter.class);

    private final StringRedisTemplate redisTemplate;
    private final IngestionService ingestionService;
    private final String externalStream;
    private final String consumerGroup;
    private final String consumerName;
    private final int batchSize;
    private volatile boolean ready;

    public RedisExternalStreamAdapter(
            StringRedisTemplate redisTemplate,
            IngestionService ingestionService,
            @Value("${ingest.redis.external-stream:ingest:external}") String externalStream,
            @Value("${ingest.redis.consumer-group:engine}") String consumerGroup,
            @Value("${ingest.redis.consumer-name:${random.uuid}}") String consumerName,
            @Value("${ingest.redis.batch-size:100}") int batchSize
    ) {
        this.redisTemplate = redisTemplate;
        this.ingestionService = ingestionService;
        this.externalStream = externalStream;
        this.consumerGroup = consumerGroup;
        this.consumerName = consumerName;
        this.batchSize = batchSize;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            redisTemplate.execute((RedisCallback<Object>) connection -> connection.execute(
                    "XGROUP",
                    bytes("CREATE"),
                    bytes(externalStream),
                    bytes(consumerGroup),
                    bytes("0-0"),
                    bytes("MKSTREAM")
            ));
        } catch (RuntimeException exception) {
            var cause = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(exception);
            if (!String.valueOf(cause.getMessage()).startsWith("BUSYGROUP")) {
                throw exception;
            }
        }
        ready = true;
    }

    @Scheduled(fixedDelayString = "${ingest.redis.poll-interval-ms:250}")
    public void poll() {
        if (!ready) {
            return;
        }
        StreamOperations<String, String, String> streams = redisTemplate.opsForStream();
        List<MapRecord<String, String, String>> records = streams.read(
                Consumer.from(consumerGroup, consumerName),
                StreamReadOptions.empty().count(batchSize),
                StreamOffset.create(externalStream, ReadOffset.lastConsumed())
        );
        if (records == null || records.isEmpty()) {
            return;
        }

        for (MapRecord<String, String, String> record : records) {
            processRecord(streams, record);
        }
    }

    private void processRecord(
            StreamOperations<String, String, String> streams,
            MapRecord<String, String, String> record
    ) {
        Map<String, String> fields = record.getValue();
        String rawPayload = fields.get("payload");
        try {
            if (rawPayload == null) {
                throw new IllegalArgumentException("Missing payload field");
            }
            String source = stringField(fields.get("source"), externalStream);
            ingestionService.accept(source, "redis-stream", rawPayload);
            streams.acknowledge(externalStream, consumerGroup, record.getId());
        } catch (RuntimeException exception) {
            if (rawPayload == null || exception instanceof InvalidJsonException
                    || exception instanceof PayloadTooLargeException) {
                logger.warn("Dropping invalid Redis ingest record {}", record.getId());
                streams.acknowledge(externalStream, consumerGroup, record.getId());
                return;
            }
            logger.error("Could not forward Redis ingest record {}", record.getId(), exception);
        }
    }

    private String stringField(Object value, String defaultValue) {
        String text = value == null ? defaultValue : value.toString();
        return text.isBlank() ? defaultValue : text;
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
