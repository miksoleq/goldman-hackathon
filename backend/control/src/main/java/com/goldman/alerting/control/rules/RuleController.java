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
        String source = source(request);
        return creation.create(parser.parse(source), source, actor(request));
    }

    @PutMapping(path="/{id}", consumes={"application/yaml","application/x-yaml","text/yaml","application/json"})
    RuleCreation.Created update(@PathVariable("id") java.util.UUID id,
                                @RequestHeader("If-Match") String expected, HttpServletRequest request) throws IOException {
        if (!expected.matches("\"[1-9][0-9]*\"")) throw new IllegalArgumentException("Expected a quoted rule version in If-Match");
        int version = Integer.parseInt(expected.substring(1, expected.length() - 1));
        String source = source(request);
        return creation.update(id, version, parser.parse(source), source, actor(request));
    }

    private static String actor(HttpServletRequest request) {
        return request.getUserPrincipal() == null ? "system" : request.getUserPrincipal().getName();
    }

    private static String source(HttpServletRequest request) throws IOException {
        int limit = 1024 * 1024;
        byte[] bytes = request.getInputStream().readNBytes(limit + 1);
        if (bytes.length > limit) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String,String> invalid(IllegalArgumentException e) { return Map.of("error","invalid_rule","message",e.getMessage()); }

    @ExceptionHandler(RuleCreation.VersionConflict.class)
    @ResponseStatus(HttpStatus.PRECONDITION_FAILED)
    Map<String,String> stale() { return Map.of("message", "Another edit was saved. Reopen the latest version before saving."); }

    @ExceptionHandler(RuleCreation.UnknownRule.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    Map<String,String> unknown() { return Map.of("message", "Rule no longer exists."); }

    @ExceptionHandler(RuleCreation.RunbookConflict.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String,String> duplicateBook() { return Map.of("error","runbook_name_exists"); }

    @ExceptionHandler(RuleCreation.NameConflict.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String,String> duplicate() { return Map.of("error","rule_name_exists"); }
}
