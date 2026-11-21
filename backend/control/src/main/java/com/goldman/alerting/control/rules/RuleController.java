package com.goldman.alerting.control.rules;

import com.goldman.alerting.control.actions.ActionCatalog;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import com.goldman.alerting.control.auth.Accounts;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api")
class RuleController {
    private final RuleDocument parser;
    private final RuleCreation creation;

    private final Accounts accounts;
    private final ActionCatalog catalog;

    RuleController(RuleDocument parser, RuleCreation creation, Accounts accounts, ActionCatalog catalog) {
        this.catalog = catalog;
        this.accounts = accounts;
        this.parser = parser;
        this.creation = creation;
    }

    @PostMapping(path={"/rules", "/apply"}, consumes={"application/yaml","application/x-yaml","text/yaml","application/json"})
    @ResponseStatus(HttpStatus.CREATED)
    Object create(HttpServletRequest request) throws IOException {
        var owner = accounts.require(request).ref();
        int limit = 1024 * 1024;
        byte[] bytes = request.getInputStream().readNBytes(limit + 1);
        if (bytes.length > limit) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        String source = new String(bytes, StandardCharsets.UTF_8);
        var root = parser.read(source);
        String kind = root.path("kind").asText("");
        if (request.getServletPath().equals("/api/apply") && (kind.equals("ActionType") || kind.equals("Runbook"))) {
            var name = root.path("name");
            if (root.size() != 3 || !name.isTextual() || name.asText().isBlank() || !(root.get("spec") instanceof ObjectNode spec)) {
                throw new IllegalArgumentException("Expected kind, name and spec");
            }
            if (kind.equals("ActionType")) {
                catalog.registerPython(name.asText(), spec, owner.username());
                return Map.of("kind", kind, "name", name.asText());
            }
            if (java.util.Set.of("auto", "none").contains(name.asText()) || spec.has("name")) {
                throw new IllegalArgumentException("Invalid runbook name");
            }
            return creation.createBook(name.asText(), parser.parseBook(spec), owner.username());
        }
        return creation.create(parser.parse(root), source, owner);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String,String> invalid(IllegalArgumentException e) { return Map.of("error","invalid_rule","message",e.getMessage()); }

    @ExceptionHandler(RuleCreation.RunbookConflict.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String,String> duplicateBook() { return Map.of("error","runbook_name_exists"); }

    @ExceptionHandler(RuleCreation.NameConflict.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String,String> duplicate() { return Map.of("error","rule_name_exists"); }
}
