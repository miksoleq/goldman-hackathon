package com.goldman.alerting.control.rules;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/rules")
class RuleController {
    private final RuleDocument parser;
    private final RuleCreation creation;

    RuleController(RuleDocument parser, RuleCreation creation) {
        this.parser = parser;
        this.creation = creation;
    }

    @PostMapping(consumes={"application/yaml","application/x-yaml","text/yaml","application/json"})
    @ResponseStatus(HttpStatus.CREATED)
    RuleCreation.Created create(HttpServletRequest request) throws IOException {
        int limit = 1024 * 1024;
        byte[] bytes = request.getInputStream().readNBytes(limit + 1);
        if (bytes.length > limit) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        String source = new String(bytes, StandardCharsets.UTF_8);
        String actor = request.getUserPrincipal() == null ? "system" : request.getUserPrincipal().getName();
        return creation.create(parser.parse(source), source, actor);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String,String> invalid(IllegalArgumentException e) { return Map.of("error","invalid_rule","message",e.getMessage()); }

    @ExceptionHandler(RuleCreation.NameConflict.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String,String> duplicate() { return Map.of("error","rule_name_exists"); }
}
