package com.goldman.alerting.control.notifications;

import static com.goldman.alerting.control.db.Tables.*;
import static com.goldman.alerting.control.notifications.DeliveryPolicy.*;
import com.goldman.alerting.contracts.LiveAlert;
import com.goldman.alerting.control.db.enums.Severity;
import com.fasterxml.jackson.databind.JsonNode;
import com.goldman.alerting.control.db.tables.records.UsersRecord;
import org.jooq.JSONB;
import java.time.OffsetDateTime;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Records;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class NotificationRouting {
    record FeedChanged(UUID userId) {}
    private final DSLContext db;
    private final NotificationPreferences preferences;
    private final ApplicationEventPublisher events;
    private final ChannelCatalog catalog;
    private final ChannelTransports transports;
    private final String linkBase;
    private final int throttleLimit;
    private final int windowSeconds;

    NotificationRouting(DSLContext db, NotificationPreferences preferences, ApplicationEventPublisher events,
                        ChannelCatalog catalog, ChannelTransports transports,
                        @Value("${notifications.link-base:http://localhost:8082}") String linkBase,
                        @Value("${notifications.throttle-limit:10}") int throttleLimit,
                        @Value("${notifications.throttle-window-seconds:300}") int windowSeconds) {
        this.db = db;
        this.preferences = preferences;
        this.events = events;
        this.catalog = catalog;
        this.transports = transports;
        this.linkBase = linkBase.replaceFirst("/+$", "");
        this.throttleLimit = throttleLimit;
        this.windowSeconds = windowSeconds;
    }

    public void route(LiveAlert alert) {
        var memberships = db.select(TEAM_MEMBERS.USER_ID, TEAM_MEMBERS.TEAM_ID).from(TEAM_MEMBERS)
                .where(TEAM_MEMBERS.TEAM_ID.in(alert.teamIds())).fetch(Records.mapping(Membership::new));
        var recipients = recipients(alert.userIds(), memberships);
        // Serialize quota decisions for the same recipient and lock overlapping routes in a stable order.
        var users = db.selectFrom(USERS).where(USERS.ID.in(recipients.keySet())).orderBy(USERS.ID).forUpdate().fetch();
        var settings = preferences.forRoute(recipients.keySet(), alert.teamIds());
        var enabled = catalog.enabled();
        var severity = Severity.valueOf(alert.severity());
        String link = linkBase + "/api/alerts/" + alert.alertId();
        for (var user : users) {
            var teams = recipients.get(user.getId());
            JsonNode contacts = catalog.decode(user.getContacts());
            for (var channel : enabled) {
                if (!settings.accepts(user.getId(), teams, channel.name(), severity)) continue;
                boolean ui = "ui".equals(channel.name());
                String address = ui ? user.getUsername() : contact(user, contacts, channel.config().path("recipientField").asText());
                UUID id = UUID.randomUUID();
                var context = new ChannelTransport.Alert(id, alert, address, link);
                var prepared = ui ? new ChannelTransport.Prepared(context.fullText())
                        : transports.get(channel.type()).prepare(channel.config(), context);
                var d = NOTIFICATION_DELIVERIES;
                db.insertInto(d, d.ID, d.ALERT_ID, d.EVENT_ID, d.USER_ID, d.CHANNEL_TYPE, d.TRANSPORT, d.TITLE,
                                d.SEVERITY, d.RECIPIENT, d.MESSAGE, d.TRANSPORT_OPTIONS, d.STATUS)
                        .values(id, alert.alertId(), alert.eventId(), user.getId(), channel.name(), channel.type(),
                                alert.title(), severity, address, prepared.message(), JSONB.valueOf(prepared.options().toString()),
                                status(user.getId(), channel.name(), address)).execute();
                if (ui) events.publishEvent(new FeedChanged(user.getId()));
            }
        }
    }

    private static String contact(UsersRecord user, JsonNode contacts, String field) {
        if (contacts.has(field)) return contacts.get(field).textValue();
        return switch (field) {
            case "email" -> user.getEmail();
            case "sms" -> user.getPhone();
            case "username" -> user.getUsername();
            default -> null;
        };
    }

    private String status(UUID user, String channel, String address) {
        if ("ui".equals(channel)) return "sent";
        if (address == null || address.isBlank()) return "skipped";
        var d = NOTIFICATION_DELIVERIES;
        int count = db.fetchCount(d, d.USER_ID.eq(user).and(d.CHANNEL_TYPE.eq(channel))
                .and(d.CREATED_AT.ge(OffsetDateTime.now().minusSeconds(windowSeconds)))
                .and(d.STATUS.notIn("throttled", "skipped")));
        return count >= throttleLimit ? "throttled" : "pending";
    }
}
