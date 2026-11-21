package com.goldman.alerting.control.notifications;

import static com.goldman.alerting.control.db.enums.Severity.*;
import static com.goldman.alerting.control.notifications.DeliveryPolicy.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DeliveryPolicyTest {
    @Test void acceptsAtThresholdAndHonorsPersonalVeto() {
        var team = List.of(new Setting(true, high));
        assertFalse(accepts("sms", medium, null, team));
        assertTrue(accepts("sms", high, null, team));
        assertFalse(accepts("sms", critical, new Setting(false, info), team));
        assertTrue(accepts("sms", low, new Setting(true, low), team));
    }

    @Test void defaultsToUiAndAnyExplicitTeamCanEnableAChannel() {
        assertTrue(accepts("ui", info, null, List.of()));
        assertFalse(accepts("email", critical, null, List.of()));
        assertTrue(accepts("email", medium, null, List.of(new Setting(false, info), new Setting(true, medium))));
    }

    @Test void mergesDirectAndMultipleTeamRoutes() {
        var user = UUID.randomUUID();
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        assertEquals(Map.of(user, Set.of(first, second)), recipients(List.of(user),
                List.of(new Membership(user, first), new Membership(user, second))));
    }
}
