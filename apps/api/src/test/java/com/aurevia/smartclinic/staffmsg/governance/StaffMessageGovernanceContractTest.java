package com.aurevia.smartclinic.staffmsg.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.ExportProvenance;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.Outcome;
import com.aurevia.smartclinic.staffmsg.governance.StaffMessageGovernanceContract.ConversationExport;
import com.aurevia.smartclinic.staffmsg.governance.StaffMessageGovernanceContract.StaffAction;

import tools.jackson.databind.ObjectMapper;

/**
 * Wire-format and value invariants of the staff-message governance contract.
 *
 * <p>Every identifier and timestamp here is synthetic. No audit fact or export
 * record may carry a message body or attachment bytes (OPS-02, ARC-06).</p>
 */
class StaffMessageGovernanceContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Every action serializes to its stable lowercase wire key and parses back. */
    @Test
    void staffActionsRoundTripThroughTheirWireKeys() throws Exception {
        for (StaffAction action : StaffAction.values()) {
            String wire = mapper.writeValueAsString(action);
            assertEquals("\"" + expectedWire(action) + "\"", wire, action.name());
            assertEquals(action, StaffAction.from(expectedWire(action)), action.name());
        }
    }

    /** An unknown wire key is rejected, never silently coerced to a default. */
    @Test
    void unknownActionWireKeyIsRejected() {
        assertEquals("unknown staff action: guess",
                assertThrows(IllegalArgumentException.class, () -> StaffAction.from("guess")).getMessage());
        assertEquals("unknown staff action: null",
                assertThrows(IllegalArgumentException.class, () -> StaffAction.from(null)).getMessage());
    }

    /** Audit facts and outcomes never expose a body, checksum or clinical field. */
    @Test
    void auditFactsCarryOnlyNonSensitiveFields() {
        var event = new com.aurevia.smartclinic.staffmsg.governance.StaffMessageGovernanceContract.AuditEvent(
                "e-1", "org-1", "nurse-a", "STAFFMSG_GOVERNANCE", StaffAction.OPEN,
                "conv-1", "unit-1", "corr-1", Outcome.SUCCESS,
                Instant.parse("2026-11-23T10:00:00Z"));
        assertEquals(StaffAction.OPEN, event.action());
        assertEquals(Outcome.SUCCESS, event.outcome());
        assertEquals("conv-1", event.resourceId());
        assertEquals(10, event.getClass().getRecordComponents().length,
                "an audit fact has exactly ten non-sensitive components");
        for (var component : event.getClass().getRecordComponents()) {
            assertFalse("body".equals(component.getName()));
            assertFalse("checksum".equals(component.getName()));
        }
    }

    /** An export freezes every attachment checksum in the conversation. */
    @Test
    void conversationExportFreezesChecksumsAndProvenance() {
        ExportProvenance provenance = new ExportProvenance("conv-1", "rev-2", "record_access");
        ConversationExport export = new ConversationExport("x-1", "org-1", "conv-1", "auditor",
                Instant.parse("2026-11-23T10:00:00Z"), Instant.parse("2026-11-23T11:00:00Z"),
                List.of("sha-1", "sha-2"), provenance);
        assertEquals(List.of("sha-1", "sha-2"), export.checksums());
        assertEquals(provenance, export.provenance());
        assertThrows(UnsupportedOperationException.class, () -> export.checksums().add("sha-3"));
        assertTrue(export.checksums().contains("sha-2"));
    }

    private static String expectedWire(StaffAction action) {
        return switch (action) {
            case OPEN -> "open";
            case SEND -> "send";
            case FORWARD -> "forward";
            case ATTACH -> "attach";
            case DOWNLOAD -> "download";
            case FOLDER_MOVE -> "folder_move";
            case DELETE -> "delete";
            case HOLD_PLACE -> "hold_place";
            case HOLD_RELEASE -> "hold_release";
            case RETENTION_APPLY -> "retention_apply";
            case EXPORT -> "export";
            case AUDIT_READ -> "audit_read";
        };
    }
}
