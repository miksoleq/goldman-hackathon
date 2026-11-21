package com.goldman.alerting.engine.ingest.file;

import com.goldman.alerting.engine.ingest.IngestionService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** One configured reader tails a local UTF-8 log. Only complete lines advance the durable cursor. */
@Component
@ConditionalOnProperty(name = "ingest.file.enabled", havingValue = "true")
public class FileLogAdapter {
    private static final Logger log = LoggerFactory.getLogger(FileLogAdapter.class);
    private final Path path;
    private final int limit;
    private final IngestionService ingestion;
    private final StringRedisTemplate redis;
    private final String checkpointKey;
    private final ByteBuffer chunk = ByteBuffer.allocate(65536);
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private String identity;
    private long position;
    private boolean oversized;

    public FileLogAdapter(@Value("${ingest.file.path}") Path path,
                          @Value("${ingest.max-payload-bytes}") int limit,
                          IngestionService ingestion, StringRedisTemplate redis) {
        if (path.toString().isBlank()) throw new IllegalArgumentException("ingest.file.path is required");
        this.path = path.toAbsolutePath().normalize();
        this.limit = limit;
        this.ingestion = ingestion;
        this.redis = redis;
        this.checkpointKey = "ingest:file:cursor:" + this.path;
    }

    @Scheduled(fixedDelayString = "${ingest.file.poll-interval-ms:250}")
    public void poll() throws IOException {
        try {
            var attrs = Files.readAttributes(path, BasicFileAttributes.class);
            String fileId = attrs.fileKey() + "/" + attrs.creationTime();
            if (!fileId.equals(identity) || attrs.size() < position) {
                String saved = redis.opsForValue().get(checkpointKey);
                long offset = saved != null && saved.startsWith(fileId + "\n")
                        ? Long.parseLong(saved.substring(fileId.length() + 1)) : 0;
                position = offset <= attrs.size() && !fileId.equals(identity) ? offset : 0;
                resetLine();
                identity = fileId;
                checkpoint();
            }
            if (attrs.size() == position) return;
            try (var file = FileChannel.open(path)) {
                chunk.clear();
                file.read(chunk, position);
                chunk.flip();
                while (chunk.hasRemaining()) {
                    byte value = chunk.get();
                    position++;
                    if (value == '\n') {
                        publishLine();
                        checkpoint();
                        resetLine();
                    } else if (!oversized) {
                        if (line.size() < limit || (line.size() == limit && value == '\r')) line.write(value);
                        else oversized = true;
                    }
                }
            }
        } catch (NoSuchFileException missing) {
            // A writer can create the file after startup or replace it during rotation.
            identity = null;
        } catch (IOException | RuntimeException failure) {
            identity = null; // Re-read from the last completed Redis checkpoint on retry.
            throw failure;
        }
    }

    private void publishLine() {
        if (oversized) {
            log.warn("Skipping oversized log line ending at byte {} in {}", position, path);
            return;
        }
        byte[] bytes = line.toByteArray();
        int length = bytes.length;
        if (length > 0 && bytes[length - 1] == '\r') length--;
        try {
            String message = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, 0, length)).toString();
            ingestion.acceptText(path.toString(), "file", message);
        } catch (java.nio.charset.CharacterCodingException invalidUtf8) {
            log.warn("Skipping invalid UTF-8 log line ending at byte {} in {}", position, path);
        }
    }

    private void checkpoint() {
        redis.opsForValue().set(checkpointKey, identity + "\n" + position);
    }

    private void resetLine() {
        line.reset();
        oversized = false;
    }
}
