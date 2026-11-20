package com.goldman.alerting.control.alerts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
record LiveAlert(int v, String type, UUID alertId, UUID eventId, UUID ruleId,
                 List<UUID> teamIds, List<UUID> userIds, Severity severity,
                 String title, String body, int count, Instant occurredAt) {
    enum Severity { info, low, medium, high, critical }

    LiveAlert {
        if (v != 1 || !("alert.created".equals(type) || "alert.updated".equals(type))) {
            throw new IllegalArgumentException("Unsupported alert version or type");
        }
        if (alertId == null || eventId == null || ruleId == null || occurredAt == null
                || severity == null || title == null || title.isBlank() || count < 1) {
            throw new IllegalArgumentException("Incomplete alert");
        }
        if ("alert.created".equals(type) && count != 1) throw new IllegalArgumentException("Invalid initial count");
        if (teamIds == null || userIds == null) throw new IllegalArgumentException("Expected both recipient arrays");
        teamIds = List.copyOf(teamIds);
        userIds = List.copyOf(userIds);
        if (teamIds.isEmpty() && userIds.isEmpty()) throw new IllegalArgumentException("Missing recipients");
    }
}
