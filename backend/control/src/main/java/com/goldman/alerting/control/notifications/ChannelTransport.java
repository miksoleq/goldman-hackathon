package com.goldman.alerting.control.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.goldman.alerting.contracts.LiveAlert;
import java.util.UUID;

interface ChannelTransport {
    record Alert(UUID notificationId, LiveAlert event, String recipient, String link) {
        String fullText() {
            String instructions = event.runbook() == null ? "" : event.runbook().content() + "\n\n";
            return event.title() + "\n\n" + event.body() + "\n\n" + instructions + link;
        }
    }
    record Prepared(String message, JsonNode options) {
        Prepared(String message) { this(message, JsonNodeFactory.instance.objectNode()); }
    }

    String type();
    void validate(JsonNode config);
    Prepared prepare(JsonNode config, Alert alert);
    DeliveryQueue.Outcome send(DeliveryQueue.Job delivery);
}
