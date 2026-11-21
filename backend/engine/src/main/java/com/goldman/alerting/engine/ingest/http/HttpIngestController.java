package com.goldman.alerting.engine.ingest.http;

import com.goldman.alerting.engine.ingest.IngestionService;
import com.goldman.alerting.engine.ingest.MessageEnvelope;
import com.goldman.alerting.engine.ingest.PayloadTooLargeException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ingest")
public class HttpIngestController {

    private final IngestionService ingestionService;
    private final int maxBodyBytes;

    public HttpIngestController(
            IngestionService ingestionService,
            @Value("${ingest.max-payload-bytes:1048576}") int maxBodyBytes
    ) {
        this.ingestionService = ingestionService;
        if (maxBodyBytes < 1) {
            throw new IllegalArgumentException("ingest.max-payload-bytes must be positive");
        }
        this.maxBodyBytes = maxBodyBytes;
    }

    @PostMapping(path = {"", "/{source}"}, consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE})
    public ResponseEntity<IngestAccepted> accept(HttpServletRequest request, @PathVariable(name = "source", required = false) String source) {
        byte[] requestBody;
        try {
            int readLimit = maxBodyBytes == Integer.MAX_VALUE ? maxBodyBytes : maxBodyBytes + 1;
            requestBody = request.getInputStream().readNBytes(readLimit);
        } catch (IOException exception) {
            throw new InvalidHttpIngestException("Could not read request body");
        }
        if (requestBody.length == 0) {
            throw new InvalidHttpIngestException("Request body must not be empty");
        }
        if (requestBody.length > maxBodyBytes) {
            throw new PayloadTooLargeException(maxBodyBytes);
        }

        String origin = source == null ? "http" : source;
        String body = new String(requestBody, StandardCharsets.UTF_8);
        MediaType mediaType = MediaType.parseMediaType(request.getContentType());
        MessageEnvelope envelope = MediaType.TEXT_PLAIN.isCompatibleWith(mediaType)
                ? ingestionService.acceptText(origin, "http", body)
                : ingestionService.accept(origin, "http", body);
        return ResponseEntity.accepted().body(new IngestAccepted(envelope.eventId()));
    }

    public record IngestAccepted(String eventId) {
    }

    public static class InvalidHttpIngestException extends RuntimeException {
        public InvalidHttpIngestException(String message) {
            super(message);
        }
    }

}
