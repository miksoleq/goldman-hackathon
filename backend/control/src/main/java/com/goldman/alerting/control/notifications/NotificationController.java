package com.goldman.alerting.control.notifications;

import jakarta.servlet.http.HttpServletRequest;
import com.goldman.alerting.control.auth.Accounts;
import java.util.*;
import com.goldman.alerting.control.notifications.DeliveryPolicy.Setting;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
class NotificationController {
    private final NotificationQueries queries;
    private final NotificationPreferences preferences;
    private final NotificationStream stream;

    private final Accounts accounts;

    NotificationController(Accounts accounts, NotificationQueries queries, NotificationPreferences preferences, NotificationStream stream) {
        this.accounts = accounts;
        this.queries = queries;
        this.preferences = preferences;
        this.stream = stream;
    }

    @GetMapping("/me/notifications")
    NotificationQueries.Page feed(HttpServletRequest caller, @RequestParam(name="eventId", required=false) String event,
                                   @RequestParam(name="cursor", required=false) String cursor) {
        return queries.feed(accounts.require(caller).id(), event, cursor);
    }

    @GetMapping(value="/me/notifications/stream", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> subscribe(HttpServletRequest caller) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering", "no")
                .body(stream.subscribe(accounts.require(caller).id()));
    }

    @GetMapping("/me/notification-deliveries")
    List<NotificationQueries.Delivery> deliveries(HttpServletRequest caller, @RequestParam(name="alertId", required=false) UUID alert) {
        return queries.deliveries(accounts.require(caller).id(), alert);
    }

    record Contact(String address) {}

    @PutMapping(value="/me/notification-contacts/{field}", consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void contact(HttpServletRequest caller, @PathVariable("field") String field, @RequestBody Contact contact) {
        preferences.setContact(accounts.require(caller).id(), field, contact.address());
    }

    @GetMapping({"/me/notification-preferences", "/teams/{teamId}/notification-preferences"})
    Map<String, Setting> preferences(HttpServletRequest caller, @PathVariable(name="teamId", required=false) UUID team) {
        return preferences.read(accounts.require(caller).id(), team);
    }

    @PutMapping(value={"/me/notification-preferences", "/teams/{teamId}/notification-preferences"},
            consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void replace(HttpServletRequest caller, @PathVariable(name="teamId", required=false) UUID team,
                 @RequestBody Map<String, Setting> settings) {
        preferences.replace(accounts.require(caller).id(), team, settings);
    }
}
