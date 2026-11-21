package com.goldman.alerting.control.auth;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class AccountsTest {
    @Test void onlyGlobalTeamLeadersCanManageMemberships() {
        var id = UUID.randomUUID();
        new Accounts.Actor(id, "leader", Accounts.Role.TEAM_LEADER).requireLeader();
        var member = new Accounts.Actor(id, "member", Accounts.Role.MEMBER);
        assertEquals(403, assertThrows(ResponseStatusException.class, member::requireLeader).getStatusCode().value());
        assertEquals(new Accounts.UserRef(id, "member"), member.ref());
    }

    @Test void managementRequiresBothLeaderRoleAndTheAssignedTeam() {
        var team = UUID.randomUUID();
        var leader = new Accounts.Actor(UUID.randomUUID(), "leader", Accounts.Role.TEAM_LEADER);
        assertDoesNotThrow(() -> leader.requireManagedTeam(team, team));
        assertThrows(ResponseStatusException.class, () -> leader.requireManagedTeam(team, UUID.randomUUID()));
        assertThrows(ResponseStatusException.class, () -> leader.requireManagedTeam(team, null));
        var member = new Accounts.Actor(UUID.randomUUID(), "member", Accounts.Role.MEMBER);
        assertThrows(ResponseStatusException.class, () -> member.requireManagedTeam(team, team));
    }

    @Test void legacyOwnersAreSystem() {
        assertEquals(new Accounts.UserRef(null, "System"), Accounts.UserRef.owner(null, null));
    }
}
