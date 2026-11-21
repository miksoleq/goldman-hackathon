package com.goldman.alerting.control.recipients;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class RecipientControllerTest {
    @Test
    void returnsDirectoryNamesForBothRecipientTypes() {
        RecipientDirectory directory = mock(RecipientDirectory.class);
        when(directory.teams()).thenReturn(List.of("demo-team"));
        when(directory.users()).thenReturn(List.of("demo-user"));
        RecipientController controller = new RecipientController(directory);
        assertEquals(List.of("demo-team"), controller.teams());
        assertEquals(List.of("demo-user"), controller.users());
    }
}
