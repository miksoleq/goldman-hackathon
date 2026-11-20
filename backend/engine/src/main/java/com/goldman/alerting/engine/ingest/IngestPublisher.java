package com.goldman.alerting.engine.ingest;

public interface IngestPublisher {

    void publish(MessageEnvelope envelope);
}
