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
import java.util.UUID;
import java.util.List;

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
        String source = readDocument(request);
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
            return creation.createBook(runbookName(root), parser.parseBook(spec), owner.username());
        }
        return creation.create(parser.parse(root), source, owner);
    }

    private String readDocument(HttpServletRequest request) throws IOException {
        int limit = 1024 * 1024;
        byte[] bytes = request.getInputStream().readNBytes(limit + 1);
        if (bytes.length > limit) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @GetMapping("/action-types")
    List<ActionCatalog.Option> actionTypes(HttpServletRequest request) {
        accounts.require(request);
        return catalog.options();
    }

    @GetMapping("/runbooks/{id}")
    ResponseEntity<?> book(@PathVariable("id") UUID id, HttpServletRequest request) {
        accounts.require(request);
        return response(creation.readBook(id));
    }

    @PutMapping(path="/runbooks/{id}", consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> update(@PathVariable("id") UUID id,
                             @RequestHeader(value="If-Match", required=false) String expected,
                             HttpServletRequest request) throws IOException {
        String actor = accounts.require(request).username();
        if (expected == null) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED);
        String source = readDocument(request);
        var root = parser.read(source);
        if (!"Runbook".equals(root.path("kind").textValue())) throw new IllegalArgumentException("Expected kind Runbook");
        var name = runbookName(root);
        return response(creation.updateBook(id, expected, name, parser.parseBook(root.get("spec")), actor));
    }

    private static String runbookName(ObjectNode root) {
        var name = root.path("name").textValue();
        if (root.size() != 3 || name == null || name.isBlank() || java.util.Set.of("auto", "none").contains(name)
                || !root.path("spec").isObject() || root.path("spec").has("name")) {
            throw new IllegalArgumentException("Expected runbook name and spec");
        }
        return name;
    }

    private static ResponseEntity<?> response(RuleCreation.BookEdition edition) {
        return ResponseEntity.ok().eTag(edition.etag()).body(edition.book());
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
