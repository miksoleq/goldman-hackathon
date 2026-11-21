package com.goldman.alerting.control.auth;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
class AuthController {
    record Credentials(@JsonAlias("email") String username, String password) {}
    private final Accounts accounts;
    AuthController(Accounts accounts) { this.accounts = accounts; }

    @PostMapping(path = "/login", consumes = "application/json")
    Accounts.Profile login(@RequestBody Credentials credentials, HttpServletRequest request) {
        return accounts.login(request, credentials.username(), credentials.password());
    }

    @GetMapping("/me")
    Accounts.Profile me(HttpServletRequest request) { return accounts.profile(accounts.require(request)); }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
        accounts.logout(request);
        var expired = new jakarta.servlet.http.Cookie("ALERT_SESSION", "");
        expired.setHttpOnly(true);
        expired.setSecure(request.isSecure());
        expired.setPath("/");
        expired.setMaxAge(0);
        response.addCookie(expired);
    }
}
