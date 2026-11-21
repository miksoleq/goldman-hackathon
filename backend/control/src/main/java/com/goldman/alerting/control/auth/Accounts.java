package com.goldman.alerting.control.auth;

import static com.goldman.alerting.control.db.Tables.*;
import static org.springframework.http.HttpStatus.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Records;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class Accounts {
    private static final String SESSION_USER = Accounts.class.getName() + ".user";
    public enum Role { TEAM_LEADER, MEMBER }
    public record UserRef(UUID id, String username) {
        public static UserRef owner(UUID id, String name) { return new UserRef(id, id == null ? "System" : name); }
    }
    public record TeamRef(UUID id, String name) {}
    public record Actor(UUID id, String username, Role role) {
        public void requireManagedTeam(UUID requested, UUID assigned) {
            requireLeader();
            if (assigned == null || !assigned.equals(requested)) throw new ResponseStatusException(FORBIDDEN);
        }
        public UserRef ref() { return new UserRef(id, username); }
        public void requireLeader() {
            if (role != Role.TEAM_LEADER) throw new ResponseStatusException(FORBIDDEN);
        }
    }
    public record Profile(UUID id, String username, Role role, List<TeamRef> teams) {}

    private final DSLContext db;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(11);
    private final String dummyHash = passwords.encode(UUID.randomUUID().toString());

    Accounts(DSLContext db) { this.db = db; }

    Profile login(HttpServletRequest request, String identifier, String password) {
        if (identifier == null || identifier.isBlank() || password == null || password.isEmpty()
                || password.getBytes(StandardCharsets.UTF_8).length > 72) throw new ResponseStatusException(UNAUTHORIZED);
        var matches = db.select(USERS.ID, USERS.USERNAME, USERS.ROLE, USERS.PASSWORD_HASH).from(USERS)
                .where(USERS.PASSWORD_HASH.isNotNull().and(USERS.USERNAME.eq(identifier.trim())
                        .or(USERS.EMAIL.equalIgnoreCase(identifier.trim())))).limit(2).fetch();
        var row = matches.size() == 1 ? matches.getFirst() : null;
        if (!passwords.matches(password, row == null ? dummyHash : row.get(USERS.PASSWORD_HASH)) || row == null) {
            throw new ResponseStatusException(UNAUTHORIZED);
        }
        logout(request);
        request.getSession(true).setAttribute(SESSION_USER, row.get(USERS.ID));
        return profile(new Actor(row.get(USERS.ID), row.get(USERS.USERNAME), Role.valueOf(row.get(USERS.ROLE))));
    }

    public Actor require(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null || !(session.getAttribute(SESSION_USER) instanceof UUID id)) throw new ResponseStatusException(UNAUTHORIZED);
        return db.select(USERS.ID, USERS.USERNAME, USERS.ROLE).from(USERS)
                .where(USERS.ID.eq(id).and(USERS.PASSWORD_HASH.isNotNull()))
                .fetchOptional(row -> new Actor(id, row.get(USERS.USERNAME), Role.valueOf(row.get(USERS.ROLE))))
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED));
    }

    Profile profile(Actor actor) {
        var teams = db.select(TEAMS.ID, TEAMS.NAME).from(TEAM_MEMBERS).join(TEAMS).on(TEAMS.ID.eq(TEAM_MEMBERS.TEAM_ID))
                .where(TEAM_MEMBERS.USER_ID.eq(actor.id())).orderBy(TEAMS.NAME).fetch(Records.mapping(TeamRef::new));
        return new Profile(actor.id(), actor.username(), actor.role(), teams);
    }

    void logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
    }
}
