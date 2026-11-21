package com.goldman.alerting.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LiveAlert(int v, String type, UUID alertId, String eventId, UUID ruleId,
                        List<UUID> teamIds, List<UUID> userIds, String severity,
                        String title, String body, int count, Instant occurredAt, UUID runbookId, Runbook runbook,
                        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                        JsonNode event) {
    public LiveAlert(int v, String type, UUID alertId, String eventId, UUID ruleId, List<UUID> teamIds,
                     List<UUID> userIds, String severity, String title, String body, int count, Instant occurredAt,
                     UUID runbookId, Runbook runbook) {
        this(v, type, alertId, eventId, ruleId, teamIds, userIds, severity, title, body, count, occurredAt,
                runbookId, runbook, null);
    }

    public static final String STREAM = "alerts.live";
    public static final UUID UNASSIGNED_RUNBOOK = new UUID(0, 0);
    public LiveAlert {
        teamIds = List.copyOf(teamIds);
        userIds = List.copyOf(userIds);
    }
}
