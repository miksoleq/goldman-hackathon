package com.goldman.alerting.control.actions;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActionCatalogTest {
    @Test void automaticExecutionRespectsExplicitCatalogGates() {
        assertTrue(new ActionCatalog.Definition("http", "Read", null, false, true).automatic());
        assertFalse(new ActionCatalog.Definition("http", "Approve", null, true, true).automatic());
        assertFalse(new ActionCatalog.Definition("http", "Manual", null, false, false).automatic());
    }
}
