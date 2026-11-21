package com.goldman.alerting.control.notifications;

import static com.goldman.alerting.control.db.Tables.*;
import static com.goldman.alerting.control.notifications.DeliveryPolicy.Setting;
import static org.springframework.http.HttpStatus.*;

import com.goldman.alerting.control.db.enums.Severity;
import java.util.*;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class NotificationPreferences {
    record Entry(UUID user, UUID team, String channel, Setting setting) {}
    private final DSLContext db;

    private final ChannelCatalog catalog;

    NotificationPreferences(DSLContext db, ChannelCatalog catalog) {
        this.db = db;
        this.catalog = catalog;
    }

    @Transactional
    public void setContact(UUID user, String field, String address) {
        if (!Set.of("sms", "email").contains(field) || address == null || address.isBlank()
                || address.length() > 512 || address.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid contact");
        }
        try {
            if ("email".equals(field)) new jakarta.mail.internet.InternetAddress(address, true).validate();
            if ("sms".equals(field) && !address.matches("[+][1-9][0-9]{1,14}")) {
                throw new ResponseStatusException(BAD_REQUEST, "Expected an international phone number");
            }
        } catch (jakarta.mail.internet.AddressException invalid) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid email address");
        }
        var stored = db.select(USERS.CONTACTS).from(USERS).where(USERS.ID.eq(user)).forUpdate().fetchSingle(USERS.CONTACTS);
        var contacts = (com.fasterxml.jackson.databind.node.ObjectNode) catalog.decode(stored);
        contacts.put(field, address);
        db.update(USERS).set(USERS.CONTACTS, org.jooq.JSONB.valueOf(contacts.toString())).where(USERS.ID.eq(user)).execute();
    }

    private void requireMember(UUID user, UUID team, boolean edit) {
        var member = TEAM_MEMBERS.TEAM_ID.eq(team).and(TEAM_MEMBERS.USER_ID.eq(user));
        if (edit) member = member.and(TEAM_MEMBERS.ROLE.eq("lead"));
        if (!db.fetchExists(TEAM_MEMBERS, member)) throw new ResponseStatusException(FORBIDDEN);
    }

    // Older personal settings may belong to a particular team. Keep that scope for those rows.
    record Scope(UUID user, UUID team, String channel) {}
    record RouteSettings(Map<Scope, List<Setting>> byScope) {
        boolean accepts(UUID user, Set<UUID> teams, String channel, Severity severity) {
            var personal = byScope.get(new Scope(user, null, channel));
            var inherited = new ArrayList<Setting>();
            for (UUID team : teams) {
                var teamDefaults = byScope.getOrDefault(new Scope(null, team, channel), List.of());
                inherited.addAll(byScope.getOrDefault(new Scope(user, team, channel), teamDefaults));
            }
            return DeliveryPolicy.accepts(channel, severity, personal == null ? null : personal.getFirst(), inherited);
        }
    }

    RouteSettings forRoute(Set<UUID> users, List<UUID> teams) {
        var rows = entries(DELIVERY_PREFS.USER_ID.in(users).or(DELIVERY_PREFS.TEAM_ID.in(teams)));
        Map<Scope, List<Setting>> indexed = new HashMap<>();
        for (var row : rows) {
            indexed.computeIfAbsent(new Scope(row.user(), row.team(), row.channel()), ignored -> new ArrayList<>())
                    .add(row.setting());
        }
        return new RouteSettings(indexed);
    }

    private List<Entry> entries(Condition scope) {
        var p = DELIVERY_PREFS;
        return db.select(p.USER_ID, p.TEAM_ID, CHANNELS.NAME, p.ENABLED, p.MIN_SEVERITY)
                .from(p).join(CHANNELS).on(CHANNELS.ID.eq(p.CHANNEL_ID))
                .where(scope)
                .orderBy(p.ID)
                .fetch(r -> new Entry(r.value1(), r.value2(), r.value3(), new Setting(r.value4(), r.value5())));
    }

    private Condition scope(UUID user, UUID team) {
        return team == null ? DELIVERY_PREFS.USER_ID.eq(user).and(DELIVERY_PREFS.TEAM_ID.isNull())
                : DELIVERY_PREFS.TEAM_ID.eq(team).and(DELIVERY_PREFS.USER_ID.isNull());
    }

    Map<String, Setting> read(UUID user, UUID team) {
        if (team != null) requireMember(user, team, false);
        var settings = new LinkedHashMap<String, Setting>();
        catalog.ids().keySet().forEach(channel -> settings.put(channel, Setting.defaults(channel)));
        entries(scope(user, team).and(CHANNELS.NAME.in(settings.keySet()))).forEach(entry -> settings.put(entry.channel(), entry.setting()));
        return settings;
    }

    @Transactional
    public void replace(UUID user, UUID team, Map<String, Setting> settings) {
        var channels = catalog.ids();
        if (settings == null || settings.isEmpty() || !channels.keySet().containsAll(settings.keySet())
                || settings.values().stream().anyMatch(s -> s == null || s.enabled() == null || s.minSeverity() == null)) {
            throw new ResponseStatusException(BAD_REQUEST, "Supply enabled and minSeverity for configured channels");
        }
        if (team == null) {
            db.select(USERS.ID).from(USERS).where(USERS.ID.eq(user)).forUpdate().fetchSingle();
        } else {
            db.select(TEAMS.ID).from(TEAMS).where(TEAMS.ID.eq(team)).forUpdate().fetchOptional();
            requireMember(user, team, true);
        }
        for (var channel : settings.keySet()) {
            var p = DELIVERY_PREFS;
            var setting = settings.get(channel);
            var condition = scope(user, team).and(p.CHANNEL_ID.eq(channels.get(channel)));
            int changed = db.update(p).set(p.ENABLED, setting.enabled()).set(p.MIN_SEVERITY, setting.minSeverity())
                    .where(condition).execute();
            if (changed == 0) db.insertInto(p, p.USER_ID, p.TEAM_ID, p.CHANNEL_ID, p.ENABLED, p.MIN_SEVERITY)
                    .values(team == null ? user : null, team, channels.get(channel), setting.enabled(), setting.minSeverity()).execute();
        }
    }
}
