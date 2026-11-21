package com.goldman.alerting.engine.ingest;

public interface IngestPublisher {

    void publish(MessageEnvelope envelope, java.util.List<com.goldman.alerting.schema.LiveAlert> alerts);
}
