package com.aurevia.it.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.lab.result.TriggerEvent;
import com.aurevia.lab.result.LabTypes.TriggerType;

/** XCH-04 and XCH-05, ARC-01 and ARC-05: immutable trigger facts with a stable identity. */
class TriggerEventTest {

    private static final Instant AT = Instant.parse("2026-11-19T00:00:00Z");

    private static TriggerEvent event(String org, TriggerType type, int version, Duration due) {
        return event(org, type, version, due, null);
    }

    private static TriggerEvent event(String org, TriggerType type, int version, Duration due,
                                      String findingCode) {
        return new TriggerEvent(org, type, "report-a", version, "patient-a", "specimen-a", "order-a",
                "synthetic-owner", "synthetic-coverage-queue", AT, due, "synthetic-escalation",
                "digest-1", AT.plusSeconds(1), findingCode);
    }

    @Test
    void eventIdentityIncludesTenantAggregateVersionTypeAndFinding() {
        TriggerEvent first = event("org-a", TriggerType.RESULT_ISSUED, 1, null);
        TriggerEvent replay = event("org-a", TriggerType.RESULT_ISSUED, 1, null);
        assertEquals(first.dedupKey(), replay.dedupKey());
        assertEquals(first.eventId(), replay.eventId());
        assertEquals(first, replay);

        assertNotEquals(first.dedupKey(), event("org-b", TriggerType.RESULT_ISSUED, 1, null).dedupKey());
        assertNotEquals(first.dedupKey(), event("org-a", TriggerType.CRITICAL_RESULT, 1, null).dedupKey());
        assertNotEquals(first.dedupKey(), event("org-a", TriggerType.RESULT_ISSUED, 2, null).dedupKey());
        assertEquals("result_issued|org-a|report-a|1|revision", first.dedupKey());

        TriggerEvent criticalGlucose = event("org-a", TriggerType.CRITICAL_RESULT, 1, null, "GLU");
        TriggerEvent criticalPotassium = event("org-a", TriggerType.CRITICAL_RESULT, 1, null, "K");
        assertNotEquals(criticalGlucose.dedupKey(), criticalPotassium.dedupKey());
        assertNotEquals(criticalGlucose.eventId(), criticalPotassium.eventId());
        assertEquals("critical_result|org-a|report-a|1|GLU", criticalGlucose.dedupKey());
        assertEquals("critical_result", TriggerType.CRITICAL_RESULT.wire());
        assertEquals("result_corrected", TriggerType.RESULT_CORRECTED.wire());
        assertEquals("result_withdrawn", TriggerType.RESULT_WITHDRAWN.wire());
    }

    @Test
    void dueTimeIsDerivedFromTheEffectiveTimeAndTheApprovedPolicyWindow() {
        TriggerEvent critical = event("org-a", TriggerType.CRITICAL_RESULT, 1, Duration.ofMinutes(15));
        assertEquals(AT.plus(Duration.ofMinutes(15)), critical.dueAt());
        assertNull(event("org-a", TriggerType.RESULT_ISSUED, 1, null).dueAt());
        assertEquals("synthetic-owner", critical.owner());
        assertEquals("synthetic-coverage-queue", critical.queue());
        assertEquals("synthetic-escalation", critical.escalation());
        assertEquals("digest-1", critical.payloadDigest());
        assertEquals(AT.plusSeconds(1), critical.occurredAt());
        assertTrue(critical.toString().contains("report-a"));
    }

    @Test
    void invalidEventsAreRejected() {
        assertThrows(ApiException.class, () -> new TriggerEvent(null, null, null, 0,
                null, null, null, null, null, null, null, null, null, null, null));
        assertThrows(ApiException.class, () -> new TriggerEvent(" ", TriggerType.RESULT_ISSUED,
                " ", 0, " ", " ", " ", " ", " ", AT, null, " ", " ", AT, null));
        assertThrows(ApiException.class, () -> new TriggerEvent(null, TriggerType.RESULT_ISSUED,
                "report-a", 1, "patient-a", "specimen-a", "order-a", "owner", "queue", AT, null,
                "escalation", "digest", AT, null));
        assertThrows(ApiException.class, () -> new TriggerEvent("org-a", null,
                "report-a", 1, "patient-a", "specimen-a", "order-a", "owner", "queue", AT, null,
                "escalation", "digest", AT, null));
        assertThrows(ApiException.class, () -> new TriggerEvent("org-a", TriggerType.RESULT_ISSUED,
                "report-a", 0, "patient-a", "specimen-a", "order-a", "owner", "queue", AT, null,
                "escalation", "digest", AT, null));
        assertThrows(ApiException.class, () -> new TriggerEvent("org-a", TriggerType.RESULT_ISSUED,
                null, 1, "patient-a", "specimen-a", "order-a", "owner", "queue", AT, null,
                "escalation", "digest", AT, null));
        assertThrows(ApiException.class, () -> new TriggerEvent("org-a", TriggerType.RESULT_ISSUED,
                " ", 1, "patient-a", "specimen-a", "order-a", "owner", "queue", AT, null,
                "escalation", "digest", AT, null));
        assertThrows(ApiException.class, () -> new TriggerEvent(" ", TriggerType.RESULT_ISSUED,
                "report-a", 1, "patient-a", "specimen-a", "order-a", "owner", "queue", AT, null,
                "escalation", "digest", AT, null));
        assertThrows(ApiException.class, () -> new TriggerEvent("org-a", TriggerType.RESULT_ISSUED,
                "report-a", 1, null, "specimen-a", "order-a", "owner", "queue", AT, null,
                "escalation", "digest", AT, null));
        assertThrows(ApiException.class, () -> new TriggerEvent("org-a", TriggerType.RESULT_ISSUED,
                "report-a", 1, " ", "specimen-a", "order-a", "owner", "queue", AT, null,
                "escalation", "digest", AT, null));
        assertThrows(ApiException.class, () -> new TriggerEvent("org-a", TriggerType.RESULT_ISSUED,
                "report-a", 1, "patient-a", "specimen-a", "order-a", "owner", "queue", null, null,
                "escalation", "digest", AT, null));
    }
}
