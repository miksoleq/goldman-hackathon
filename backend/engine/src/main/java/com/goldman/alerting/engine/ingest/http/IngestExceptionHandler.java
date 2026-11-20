package com.goldman.alerting.engine.ingest.http;

import com.goldman.alerting.engine.ingest.InvalidJsonException;
import com.goldman.alerting.engine.ingest.PayloadTooLargeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class IngestExceptionHandler {

    @ExceptionHandler(InvalidJsonException.class)
    public ResponseEntity<IngestError> invalidJson(InvalidJsonException exception) {
        return ResponseEntity.badRequest().body(new IngestError("invalid_json", exception.getMessage()));
    }

    @ExceptionHandler(HttpIngestController.InvalidHttpIngestException.class)
    public ResponseEntity<IngestError> invalidRequest(HttpIngestController.InvalidHttpIngestException exception) {
        return ResponseEntity.badRequest().body(new IngestError("invalid_request", exception.getMessage()));
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    public ResponseEntity<IngestError> payloadTooLarge(PayloadTooLargeException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new IngestError("payload_too_large", exception.getMessage()));
    }

    public record IngestError(String code, String message) {
    }
}
