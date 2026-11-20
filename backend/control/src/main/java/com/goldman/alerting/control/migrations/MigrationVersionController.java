package com.goldman.alerting.control.migrations;

import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/migrations")
public class MigrationVersionController {

    private final Flyway flyway;

    public MigrationVersionController(Flyway flyway) {
        this.flyway = flyway;
    }

    @GetMapping
    public MigrationStatus getStatus() {
        MigrationInfoService info = flyway.info();
        MigrationInfo current = info.current();
        return new MigrationStatus(
                current == null || current.getVersion() == null ? null : current.getVersion().toString(),
                map(info.applied()),
                map(info.pending())
        );
    }

    private List<MigrationEntry> map(MigrationInfo[] migrations) {
        return Arrays.stream(migrations)
                .map(migration -> new MigrationEntry(
                        migration.getVersion() == null ? null : migration.getVersion().toString(),
                        migration.getDescription(),
                        migration.getState().name()
                ))
                .toList();
    }

    public record MigrationStatus(
            String currentVersion,
            List<MigrationEntry> applied,
            List<MigrationEntry> pending
    ) {
    }

    public record MigrationEntry(String version, String description, String state) {
    }
}
