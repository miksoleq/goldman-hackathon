package com.goldman.alerting.contracts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LiveAlert(int v, String type, UUID alertId, UUID eventId, UUID ruleId,
                        List<UUID> teamIds, List<UUID> userIds, String severity,
                        String title, String body, int count, Instant occurredAt, UUID runbookId) {
    public static final String STREAM = "alerts.live";
    public static final UUID UNASSIGNED_RUNBOOK = new UUID(0, 0);
    public LiveAlert {
        teamIds = List.copyOf(teamIds);
        userIds = List.copyOf(userIds);
    }
}
