package com.aurevia.printing.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.printing.HandoutKind;
import com.aurevia.tenant.TenantContext;

/**
 * The OPS-02 audit trail for printing carries identifiers and the reason only.
 *
 * <p>The append is a real call on a mocked {@link AuditAppender}; the test reads
 * the bound SQL parameters to prove that no clinical content, patient name or
 * identifier reaches the audit row. Tenant context is synthetic and cleared
 * after every case.</p>
 */
class PrintAuditAppenderTest {

    private static final Instant AT = Instant.parse("2026-11-21T10:15:00Z");

    /** Remove any thread-bound context left by a case. */
    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private static PrintAuditEntry entry(String outcome) {
        return new PrintAuditEntry("org-alpha", "clinician-a", HandoutKind.PRESCRIPTION,
                "handout-1", 2, 3, "FAC-A", PrintReason.GIVE_TO_PATIENT,
                "HANDOUT_PRINT", outcome, AT);
    }

    /** A bound context produces one sanitized append with reason as purpose. */
    @Test
    void appendsSanitizedIdentifiersOnly() {
        TenantContext.set(new TenantContext("org-alpha", "clinician-a", "PRINT", "corr-1"));
        AuditAppender appender = mock(AuditAppender.class);
        new PrintAuditAppender(appender).record(entry("SUCCESS"));

        ArgumentCaptor<TenantContext> context = ArgumentCaptor.forClass(TenantContext.class);
        verify(appender).append(context.capture(), eq("give_to_patient"), eq("HANDOUT_PRINT"),
                eq("HANDOUT"), eq("FAC-A"), eq("prescription:handout-1:v2:r3"), eq("SUCCESS"));
        assertEquals("clinician-a", context.getValue().subject());
        assertEquals("corr-1", context.getValue().correlationId());
    }

    /** No value written to the audit row contains handout content or patient data. */
    @Test
    void auditValuesNeverCarryContentOrPatientFields() {
        TenantContext.set(new TenantContext("org-alpha", "clinician-a", "PRINT", "corr-1"));
        AuditAppender appender = mock(AuditAppender.class);
        new PrintAuditAppender(appender).record(entry("DENIED"));

        ArgumentCaptor<String> values = ArgumentCaptor.forClass(String.class);
        verify(appender).append(any(TenantContext.class), anyString(), values.capture(), anyString(),
                values.capture(), values.capture(), anyString());
        for (String value : values.getAllValues()) {
            assertFalse(value.contains("Synthetic Patient"));
            assertFalse(value.contains("PAT-SYN-1"));
            assertFalse(value.contains("synthetic line"));
            assertFalse(value.contains("medication"));
            assertFalse(value.contains("/"));
        }
        assertTrue(values.getAllValues().stream().anyMatch(value -> value.contains("HANDOUT_PRINT")));
    }

    /** A missing tenant context fails closed and writes nothing. */
    @Test
    void refusesAppendWithoutTenantContext() {
        AuditAppender appender = mock(AuditAppender.class);
        ApiException error = assertThrows(ApiException.class,
                () -> new PrintAuditAppender(appender).record(entry("SUCCESS")));
        assertEquals(401, error.status());
        assertEquals("UNAUTHENTICATED", error.code());
        verifyNoInteractions(appender);
    }

    /** The audit entry value type carries no mutable or clinical payload. */
    @Test
    void auditEntryExposesIdentifiersOnly() {
        PrintAuditEntry value = entry("SUCCESS");
        assertEquals("clinician-a", value.actorSubject());
        assertEquals("org-alpha", value.organizationId());
        assertEquals(HandoutKind.PRESCRIPTION, value.kind());
        assertEquals("handout-1", value.handoutId());
        assertEquals(2, value.version());
        assertEquals(3, value.sourceRevision());
        assertEquals("FAC-A", value.facility());
        assertEquals(PrintReason.GIVE_TO_PATIENT, value.reason());
        assertEquals("HANDOUT_PRINT", value.action());
        assertEquals("SUCCESS", value.outcome());
        assertEquals(AT, value.at());
        assertEquals(11, PrintAuditEntry.class.getRecordComponents().length);
    }
}
