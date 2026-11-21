package com.goldman.alerting.control.alerts;

import static com.goldman.alerting.control.db.Tables.LIVE_ALERT_INBOX;

import com.goldman.alerting.contracts.LiveAlert;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AlertInbox {
    private final DSLContext db;
    private final ApplicationEventPublisher events;

    AlertInbox(DSLContext db, ApplicationEventPublisher events) {
        this.db = db;
        this.events = events;
    }

    @Transactional
    public void receive(LiveAlert alert, String payload) {
        int inserted = db.insertInto(LIVE_ALERT_INBOX)
                .set(LIVE_ALERT_INBOX.ALERT_ID, alert.alertId())
                .set(LIVE_ALERT_INBOX.PAYLOAD, JSONB.valueOf(payload))
                .onConflict(LIVE_ALERT_INBOX.ALERT_ID).doNothing().execute();
        if (inserted == 1) events.publishEvent(alert);
    }
}
