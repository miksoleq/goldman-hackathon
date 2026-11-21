package com.goldman.alerting.control.recipients;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class RecipientController {
    private final RecipientDirectory directory;

    RecipientController(RecipientDirectory directory) {
        this.directory = directory;
    }

    @GetMapping("/teams")
    List<String> teams() {
        return directory.teams();
    }

    @GetMapping("/users")
    List<String> users() {
        return directory.users();
    }
}
