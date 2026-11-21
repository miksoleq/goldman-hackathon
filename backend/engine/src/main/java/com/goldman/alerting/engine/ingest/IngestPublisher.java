package com.goldman.alerting.engine.ingest;

import com.goldman.alerting.contracts.LiveAlert;
import java.util.List;

public interface IngestPublisher {

    record MatchedAlert(LiveAlert alert, String fingerprint, int windowSeconds) {}

    void publish(MessageEnvelope envelope, List<MatchedAlert> alerts);
}
