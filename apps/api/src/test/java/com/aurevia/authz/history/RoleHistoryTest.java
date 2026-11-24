package com.aurevia.authz.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The immutable IAM-02 history projection cannot be altered by a caller. */
class RoleHistoryTest {

    private static RoleHistory.RoleChange change(String id, String kind) {
        return new RoleHistory.RoleChange(id, "employee-a", "reception", kind, "admin-a",
                "2026-11-21 10:00:00+00", "2026-11-21 09:00:00+00", null);
    }

    @Test
    void freezesTheSuppliedChangeList() {
        List<RoleHistory.RoleChange> source = new ArrayList<>();
        source.add(change("c1", "GRANT"));
        RoleHistory history = new RoleHistory("employee-a", source);
        source.add(change("c2", "REVOKE"));
        assertEquals(List.of(change("c1", "GRANT")), history.changes());
    }

    @Test
    void returnedListCannotBeMutated() {
        RoleHistory history = new RoleHistory("employee-a", List.of(change("c1", "GRANT")));
        assertThrows(UnsupportedOperationException.class,
                () -> history.changes().add(change("c2", "REVOKE")));
    }
}
