package com.goldman.alerting.control.recipients;

import com.goldman.alerting.control.auth.Accounts;
import com.goldman.alerting.control.auth.Accounts.UserRef;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Records;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static com.goldman.alerting.control.db.Tables.*;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api")
class TeamMemberships {
    private static final String LEAD = "lead";
    record Member(UUID id, String username, boolean teamLeader) {}
    record Team(UUID id, String name, List<Member> members) {}
    record Assignment(UUID userId) {}
    private final Accounts accounts;
    private final DSLContext db;

    TeamMemberships(DSLContext db, Accounts accounts) { this.db = db; this.accounts = accounts; }

    @GetMapping("/teams/directory")
    List<Team> directory(HttpServletRequest request) {
        var actor = accounts.require(request);
        var visible = actor.role() == Accounts.Role.TEAM_LEADER
                ? TEAMS.ID.in(db.select(TEAM_MEMBERS.TEAM_ID).from(TEAM_MEMBERS).where(leadAssignment(actor)))
                : org.jooq.impl.DSL.noCondition();
        Map<UUID, Team> teams = new LinkedHashMap<>();
        db.select(TEAMS.ID, TEAMS.NAME, USERS.ID, USERS.USERNAME, TEAM_MEMBERS.ROLE).from(TEAMS)
                .leftJoin(TEAM_MEMBERS).on(TEAM_MEMBERS.TEAM_ID.eq(TEAMS.ID))
                .leftJoin(USERS).on(USERS.ID.eq(TEAM_MEMBERS.USER_ID)).where(visible).orderBy(TEAMS.NAME, USERS.USERNAME)
                .forEach(row -> {
                    var team = teams.computeIfAbsent(row.value1(), id -> new Team(id, row.value2(), new ArrayList<>()));
                    if (row.value3() != null) team.members().add(new Member(row.value3(), row.value4(), LEAD.equals(row.value5())));
                });
        return List.copyOf(teams.values());
    }

    @GetMapping("/users/directory")
    List<UserRef> candidates(HttpServletRequest request) {
        accounts.require(request).requireLeader();
        return db.select(USERS.ID, USERS.USERNAME).from(USERS).orderBy(USERS.USERNAME).fetch(Records.mapping(UserRef::new));
    }

    @PostMapping(path = "/teams/{teamId}/members", consumes = "application/json")
    @Transactional
    public void assign(@PathVariable("teamId") UUID team, @RequestBody Assignment assignment, HttpServletRequest request) {
        requireManagedTeam(accounts.require(request), team);
        if (assignment.userId() == null) throw new ResponseStatusException(BAD_REQUEST, "userId required");
        requireUser(assignment.userId());
        db.insertInto(TEAM_MEMBERS, TEAM_MEMBERS.TEAM_ID, TEAM_MEMBERS.USER_ID).values(team, assignment.userId())
                .onConflictDoNothing().execute();
    }

    @DeleteMapping("/teams/{teamId}/members/{userId}")
    @Transactional
    public void remove(@PathVariable("teamId") UUID team, @PathVariable("userId") UUID user, HttpServletRequest request) {
        requireManagedTeam(accounts.require(request), team);
        requireUser(user);
        var membership = TEAM_MEMBERS.TEAM_ID.eq(team).and(TEAM_MEMBERS.USER_ID.eq(user));
        int removed = db.deleteFrom(TEAM_MEMBERS).where(membership).and(TEAM_MEMBERS.ROLE.ne(LEAD)).execute();
        if (removed == 0 && db.fetchExists(TEAM_MEMBERS, membership.and(TEAM_MEMBERS.ROLE.eq(LEAD)))) {
            throw new ResponseStatusException(FORBIDDEN, "Lead assignments are managed in the database");
        }
    }

    private static org.jooq.Condition leadAssignment(Accounts.Actor actor) {
        return TEAM_MEMBERS.USER_ID.eq(actor.id()).and(TEAM_MEMBERS.ROLE.eq(LEAD));
    }

    private void requireManagedTeam(Accounts.Actor actor, UUID team) {
        var assigned = db.select(TEAM_MEMBERS.TEAM_ID).from(TEAM_MEMBERS).where(leadAssignment(actor))
                .forShare().fetchOne(TEAM_MEMBERS.TEAM_ID);
        actor.requireManagedTeam(team, assigned);
    }

    private void requireUser(UUID user) {
        if (db.select(USERS.ID).from(USERS).where(USERS.ID.eq(user)).forKeyShare().fetchOne() == null) {
            throw new ResponseStatusException(NOT_FOUND, "Unknown user");
        }
    }
}
