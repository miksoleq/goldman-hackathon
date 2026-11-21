package com.goldman.alerting.engine.ingest.redis;

import com.goldman.alerting.engine.ingest.IngestionService;
import com.goldman.alerting.engine.ingest.IngestPublisher;
import com.goldman.alerting.engine.ingest.InvalidJsonException;
import com.goldman.alerting.engine.ingest.PayloadTooLargeException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
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
    private final int maxBatchesPerPoll;
    private final Duration reclaimIdle;
    private String pendingRecoveryCursor = "0-0";
    private volatile boolean ready;

    @Autowired
    public RedisExternalStreamAdapter(
            StringRedisTemplate redisTemplate,
            IngestionService ingestionService,
            @Value("${ingest.redis.external-stream:ingest:external}") String externalStream,
            @Value("${ingest.redis.consumer-group:engine}") String consumerGroup,
            @Value("${ingest.redis.consumer-name:${random.uuid}}") String consumerName,
            @Value("${ingest.redis.batch-size:100}") int batchSize,
            @Value("${ingest.redis.max-batches-per-poll:10}") int maxBatchesPerPoll,
            @Value("${ingest.redis.reclaim-idle-ms:30000}") long reclaimIdleMs
    ) {
        this(redisTemplate, ingestionService, externalStream, consumerGroup, consumerName,
                batchSize, maxBatchesPerPoll, Duration.ofMillis(reclaimIdleMs));
    }

    public RedisExternalStreamAdapter(
            StringRedisTemplate redisTemplate,
            IngestionService ingestionService,
            String externalStream,
            String consumerGroup,
            String consumerName,
            int batchSize
    ) {
        this(redisTemplate, ingestionService, externalStream, consumerGroup, consumerName,
                batchSize, 10, Duration.ofSeconds(30));
    }

    public RedisExternalStreamAdapter(
            StringRedisTemplate redisTemplate,
            IngestionService ingestionService,
            String externalStream,
            String consumerGroup,
            String consumerName,
            int batchSize,
            int maxBatchesPerPoll
    ) {
        this(redisTemplate, ingestionService, externalStream, consumerGroup, consumerName,
                batchSize, maxBatchesPerPoll, Duration.ofSeconds(30));
    }

    RedisExternalStreamAdapter(
            StringRedisTemplate redisTemplate,
            IngestionService ingestionService,
            String externalStream,
            String consumerGroup,
            String consumerName,
            int batchSize,
            int maxBatchesPerPoll,
            Duration reclaimIdle
    ) {
        this.redisTemplate = redisTemplate;
        this.ingestionService = ingestionService;
        this.externalStream = externalStream;
        this.consumerGroup = consumerGroup;
        this.consumerName = consumerName;
        if (batchSize < 1) {
            throw new IllegalArgumentException("ingest.redis.batch-size must be positive");
        }
        this.batchSize = batchSize;
        if (maxBatchesPerPoll < 1) {
            throw new IllegalArgumentException("ingest.redis.max-batches-per-poll must be positive");
        }
        this.maxBatchesPerPoll = maxBatchesPerPoll;
        if (reclaimIdle.isNegative() || reclaimIdle.isZero()) {
            throw new IllegalArgumentException("ingest.redis.reclaim-idle-ms must be positive");
        }
        this.reclaimIdle = reclaimIdle;
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
        for (int batch = 0; batch < maxBatchesPerPoll; batch++) {
            if (processBatch(streams) < batchSize) {
                return;
            }
        }
    }

    @Scheduled(fixedDelayString = "${ingest.redis.reclaim-interval-ms:5000}")
    public synchronized void reclaimPending() {
        if (!ready) {
            return;
        }
        StreamOperations<String, String, String> streams = redisTemplate.opsForStream();
        var range = Range.rightUnbounded(Range.Bound.exclusive(pendingRecoveryCursor));
        var pending = streams.pending(externalStream, consumerGroup, range, batchSize);
        if (pending.isEmpty()) {
            pendingRecoveryCursor = "0-0";
            return;
        }

        var messages = new ArrayList<PendingMessage>(pending.size());
        pending.forEach(messages::add);
        pendingRecoveryCursor = messages.size() == batchSize
                ? messages.getLast().getIdAsString()
                : "0-0";

        RecordId[] staleIds = messages.stream()
                .filter(message -> isStale(message, reclaimIdle))
                .map(PendingMessage::getId)
                .toArray(RecordId[]::new);
        if (staleIds.length == 0) {
            return;
        }

        List<MapRecord<String, String, String>> claimed = streams.claim(
                externalStream, consumerGroup, consumerName, reclaimIdle, staleIds);
        processRecords(streams, claimed);
    }

    static boolean isStale(PendingMessage message, Duration minimumIdle) {
        return message.getElapsedTimeSinceLastDelivery().compareTo(minimumIdle) >= 0;
    }

    private int processBatch(StreamOperations<String, String, String> streams) {
        List<MapRecord<String, String, String>> records = streams.read(
                Consumer.from(consumerGroup, consumerName),
                StreamReadOptions.empty().count(batchSize),
                StreamOffset.create(externalStream, ReadOffset.lastConsumed())
        );
        if (records == null || records.isEmpty()) {
            return 0;
        }

        processRecords(streams, records);
        return records.size();
    }

    private void processRecords(
            StreamOperations<String, String, String> streams,
            List<MapRecord<String, String, String>> records
    ) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<RecordId> acknowledged = new ArrayList<>(records.size());
        for (MapRecord<String, String, String> record : records) {
            if (processRecord(record)) {
                acknowledged.add(record.getId());
            }
        }
        if (!acknowledged.isEmpty()) {
            streams.acknowledge(externalStream, consumerGroup, acknowledged.toArray(RecordId[]::new));
        }
    }

    private boolean processRecord(MapRecord<String, String, String> record) {
        Map<String, String> fields = record.getValue();
        String rawPayload = fields.get("payload");
        try {
            if (rawPayload == null) {
                throw new IllegalArgumentException("Missing payload field");
            }
            String source = stringField(fields.get("source"), externalStream);
            ingestionService.accept(source, "redis-stream", rawPayload,
                    new IngestPublisher.RedisStreamRecord(externalStream, consumerGroup, record.getId().getValue()));
            return true;
        } catch (RuntimeException exception) {
            if (rawPayload == null || exception instanceof InvalidJsonException
                    || exception instanceof PayloadTooLargeException) {
                logger.warn("Dropping invalid Redis ingest record {}", record.getId());
                return true;
            }
            logger.error("Could not forward Redis ingest record {}", record.getId(), exception);
            return false;
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
