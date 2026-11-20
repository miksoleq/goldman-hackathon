package com.goldman.alerting.engine.ingest;

public class InvalidJsonException extends RuntimeException {

    public InvalidJsonException() {
        super("Message must contain valid JSON");
    }
}
