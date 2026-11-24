package com.aurevia.labflow.outage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.lab.accession.AccessionState;
import com.aurevia.lab.accession.LabAccession;
import com.aurevia.labflow.outage.OutageModel.Disposition;
import com.aurevia.labflow.outage.OutageModel.EvidenceAttachment;
import com.aurevia.labflow.results.ObservationInput;

/** Pure partner-outage fallback rules: reason, evidence, owner, matching and wire forms. */
class OutageDecisionTest {

    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";

    private static EvidenceAttachment attachment() {
        return new EvidenceAttachment("partner/report-1",
                "original-report.pdf",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "application/pdf");
    }

    private static ObservationInput observation(String code) {
        return new ObservationInput(code, "numeric", "present", "5.5", null, "mmol/L",
                null, null, "photometry", "analyser-1", null, null, null, null);
    }

    private static LabAccession accession() {
        return new LabAccession("33333333-3333-3333-3333-333333333333", 1, "ACC-1", "LAB-A",
                PATIENT, "ORD/1", AccessionState.COLLECTED, "PLASMA", "SITE-A", "Sam Collector",
                "2026-11-21T09:00:00Z", null, "acceptable", null, null, null);
    }

    @Test
    void reasonIsRequiredAndBounded() {
        assertEquals("A documented outage entry", OutageDecision.reason("  A documented outage entry  "));
        assertEquals("REASON_REQUIRED", assertThrows(ApiException.class,
                () -> OutageDecision.reason(null)).code());
        assertEquals("REASON_REQUIRED", assertThrows(ApiException.class,
                () -> OutageDecision.reason("   ")).code());
        assertEquals(422, assertThrows(ApiException.class,
                () -> OutageDecision.reason("x".repeat(201))).status());
    }

    @Test
    void ownerIsRequiredForAnUnmatchedResultAndBounded() {
        assertEquals("reconciliation-owner", OutageDecision.owner(" reconciliation-owner "));
        assertEquals("OWNER_REQUIRED", assertThrows(ApiException.class,
                () -> OutageDecision.owner(null)).code());
        assertEquals("OWNER_REQUIRED", assertThrows(ApiException.class,
                () -> OutageDecision.owner(" ")).code());
        assertEquals(422, assertThrows(ApiException.class,
                () -> OutageDecision.owner("o".repeat(201))).status());
    }

    @Test
    void attachmentIsRequiredAndEveryFieldIsBounded() {
        EvidenceAttachment validated = OutageDecision.attachment(attachment());
        assertEquals("partner/report-1", validated.objectKey());
        assertEquals("original-report.pdf", validated.fileName());

        assertEquals("ATTACHMENT_REQUIRED", assertThrows(ApiException.class,
                () -> OutageDecision.attachment(null)).code());
        assertEquals("INVALID_OBJECT_KEY", assertThrows(ApiException.class,
                () -> OutageDecision.attachment(new EvidenceAttachment(null, "f.pdf",
                        "a".repeat(64), "application/pdf"))).code());
        assertEquals("INVALID_FILE_NAME", assertThrows(ApiException.class,
                () -> OutageDecision.attachment(new EvidenceAttachment("key", null,
                        "a".repeat(64), "application/pdf"))).code());
        assertEquals("INVALID_CHECKSUM", assertThrows(ApiException.class,
                () -> OutageDecision.attachment(new EvidenceAttachment("key", "f.pdf",
                        null, "application/pdf"))).code());
        assertEquals("INVALID_CONTENT_TYPE", assertThrows(ApiException.class,
                () -> OutageDecision.attachment(new EvidenceAttachment("key", "f.pdf",
                        "a".repeat(64), null))).code());
        assertEquals("INVALID_FILE_NAME", assertThrows(ApiException.class,
                () -> OutageDecision.attachment(new EvidenceAttachment("key", "../f.pdf",
                        "a".repeat(64), "application/pdf"))).code());
        assertEquals("INVALID_CHECKSUM", assertThrows(ApiException.class,
                () -> OutageDecision.attachment(new EvidenceAttachment("key", "f.pdf",
                        "not-a-checksum", "application/pdf"))).code());
    }

    @Test
    void observationsAreRequiredAndSummarised() {
        assertEquals("GLU,K", OutageDecision.observationSummary(
                List.of(observation("GLU"), observation("K"))));
        assertEquals("NO_OBSERVATIONS", assertThrows(ApiException.class,
                () -> OutageDecision.observationSummary(null)).code());
        assertEquals("NO_OBSERVATIONS", assertThrows(ApiException.class,
                () -> OutageDecision.observationSummary(List.of())).code());
    }

    @Test
    void matchingRequiresPatientAccessionAndIssuerToAgree() {
        assertTrue(OutageDecision.matches(accession(), PATIENT, "ACC-1", "LAB-A"));
        assertFalse(OutageDecision.matches(accession(), "55555555-5555-5555-5555-555555555555",
                "ACC-1", "LAB-A"));
        assertFalse(OutageDecision.matches(accession(), PATIENT, "ACC-9", "LAB-A"));
        assertFalse(OutageDecision.matches(accession(), PATIENT, "ACC-1", "LAB-B"));
    }

    @Test
    void dispositionUsesTheLowerCaseWireForm() {
        assertEquals("linked", Disposition.LINKED.wire());
        assertEquals("unmatched", Disposition.UNMATCHED.wire());
    }
}
