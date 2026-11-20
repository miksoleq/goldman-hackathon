package com.goldman.alerting.engine.ingest;

public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException(int limit) {
        super("Message exceeds the " + limit + " byte limit");
    }
}
