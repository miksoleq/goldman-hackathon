package com.goldman.alerting.control.notifications;

import com.goldman.alerting.control.db.enums.Severity;
import java.util.*;

final class DeliveryPolicy {
    record Setting(Boolean enabled, Severity minSeverity) {
        boolean accepts(Severity severity) { return enabled && severity.compareTo(minSeverity) >= 0; }
        static Setting defaults(String channel) { return new Setting("ui".equals(channel), Severity.info); }
    }
    record Membership(UUID user, UUID team) {}

    static Map<UUID, Set<UUID>> recipients(List<UUID> direct, List<Membership> memberships) {
        Map<UUID, Set<UUID>> recipients = new HashMap<>();
        for (UUID user : direct) recipients.put(user, new HashSet<>());
        for (var member : memberships) {
            recipients.computeIfAbsent(member.user(), ignored -> new HashSet<>()).add(member.team());
        }
        return recipients;
    }

    static boolean accepts(String channel, Severity severity, Setting personal, List<Setting> inherited) {
        if (personal != null) return personal.accepts(severity);
        if (inherited.isEmpty()) return Setting.defaults(channel).accepts(severity);
        return inherited.stream().anyMatch(setting -> setting.accepts(severity));
    }
}
