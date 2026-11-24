package com.aurevia.it.radiology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.radiology.ImagingOrder;
import com.aurevia.radiology.Interpretation;
import com.aurevia.radiology.RadEvent;
import com.aurevia.radiology.RadTypes.InterpretationKind;
import com.aurevia.radiology.RadTypes.Modality;
import com.aurevia.radiology.RadTypes.OrderState;
import com.aurevia.radiology.RadTypes.RadEventType;
import com.aurevia.radiology.RadTypes.Urgency;

/** Immutable RAD-01 records: ownership invariants, links, identities and checksums. */
class RadDomainRecordsTest {

    private static final Instant AT = Instant.parse("2026-11-19T10:00:00Z");

    private static ImagingOrder order(String org, String orderId, int version) {
        return new ImagingOrder(org, orderId, "patient-a", "enc-1", "CT-CHEST", Urgency.ROUTINE,
                "clinical question", "source", "radiology", "owner", "queue", "referrer",
                OrderState.INTAKE, version, null, null, null, null, null, null, null, null);
    }

    private static Interpretation interpretation(String org, String orderId, int version,
                                                  Integer supersedes, InterpretationKind kind, String signer) {
        return new Interpretation(org, orderId, version, supersedes, kind, "patient-a", "study-uid",
                "technique", "comparison", "findings", "impression", "follow-up", signer,
                "attestation", AT, "tpl-1", kind == InterpretationKind.ADDENDUM ? "late result" : null);
    }

    @Test
    void imagingOrderRejectsInvalidOwnershipAndVersion() {
        assertEquals("org-a", order("org-a", "order-a", 1).organizationId());
        assertEquals(422, assertThrows(ApiException.class, () -> order(" ", "order-a", 1)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> order(null, "order-a", 1)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> order("org-a", " ", 1)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> order("org-a", null, 1)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> order("org-a", "order-a", 0)).status());
    }

    @Test
    void interpretationRejectsInvalidOwnershipVersionKindAndSigner() {
        Interpretation initial = interpretation("org-a", "order-a", 1, null, InterpretationKind.INITIAL,
                "signer-a");
        assertEquals("org-a", initial.organizationId());
        assertFalse(initial.isAddendum());
        assertTrue(interpretation("org-a", "order-a", 2, 1, InterpretationKind.ADDENDUM, "signer-a")
                .isAddendum());

        assertEquals(422, assertThrows(ApiException.class,
                () -> interpretation(" ", "order-a", 1, null, InterpretationKind.INITIAL, "s")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> interpretation("org-a", " ", 1, null, InterpretationKind.INITIAL, "s")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> interpretation("org-a", "order-a", 0, null, InterpretationKind.INITIAL, "s")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> interpretation("org-a", "order-a", 1, 1, InterpretationKind.INITIAL, "s")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> interpretation("org-a", "order-a", 1, 2, InterpretationKind.INITIAL, "s")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> interpretation("org-a", "order-a", 1, null, null, "s")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> interpretation("org-a", "order-a", 1, null, InterpretationKind.INITIAL, " ")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> interpretation("org-a", "order-a", 1, null, InterpretationKind.INITIAL, null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> interpretation(null, "order-a", 1, null, InterpretationKind.INITIAL, "s")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> interpretation("org-a", null, 1, null, InterpretationKind.INITIAL, "s")).status());
    }

    @Test
    void checksumIsStableContentDerivedAndSensitiveToClinicalContent() {
        Interpretation first = interpretation("org-a", "order-a", 1, null, InterpretationKind.INITIAL,
                "signer-a");
        assertEquals(first.checksum(), first.checksum());
        assertEquals(first.checksum(), interpretation("org-a", "order-a", 1, null,
                InterpretationKind.INITIAL, "signer-a").checksum());
        assertTrue(first.checksum().matches("[0-9a-f]+"));
        assertNotEquals(first.checksum(), new Interpretation("org-a", "order-a", 1, null,
                InterpretationKind.INITIAL, "patient-a", "study-uid", "technique", "comparison",
                "CHANGED", "impression", "follow-up", "signer-a", "attestation", AT, "tpl-1", null)
                .checksum());
        assertNotEquals(first.checksum(), new Interpretation("org-a", "order-a", 1, null,
                InterpretationKind.INITIAL, "patient-a", "study-uid", "technique", "comparison",
                "findings", "impression", "follow-up", "signer-a", "CHANGED_ATTESTATION", AT, "tpl-1",
                null).checksum());
        assertNotEquals(first.checksum(), interpretation("org-a", "order-a", 1, null,
                InterpretationKind.ADDENDUM, "signer-a").checksum());
    }

    @Test
    void checksumIsAStableFixedLengthSha256DigestOfCanonicalContent() throws Exception {
        Interpretation revision = interpretation("org-a", "order-a", 1, null,
                InterpretationKind.INITIAL, "signer-a");

        // Known answer: SHA-256 over the documented length-prefixed canonical fields.
        // The canonical form is independent of the production helper.
        String canonical = "5:org-a7:order-a1:14:null7:initial9:patient-a9:study-uid"
                + "9:technique10:comparison8:findings10:impression9:follow-up"
                + "8:signer-a11:attestation5:tpl-10:";
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));

        assertEquals(64, revision.checksum().length());
        assertEquals(expected, revision.checksum());
    }

    @Test
    void radEventRejectsInvalidFactsAndHasStableIdentity() {
        RadEvent event = new RadEvent("org-a", RadEventType.ORDER_INTAKED, "order-a", 1,
                "patient-a", null, null, null, null, AT);
        assertEquals(422, assertThrows(ApiException.class,
                () -> new RadEvent(" ", RadEventType.ORDER_INTAKED, "order-a", 1, "patient-a", null,
                        null, null, null, AT)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new RadEvent("org-a", null, "order-a", 1, "patient-a", null,
                        null, null, null, AT)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new RadEvent("org-a", RadEventType.ORDER_INTAKED, " ", 1, "patient-a", null,
                        null, null, null, AT)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new RadEvent("org-a", RadEventType.ORDER_INTAKED, "order-a", 0, "patient-a", null,
                        null, null, null, AT)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new RadEvent("org-a", RadEventType.ORDER_INTAKED, "order-a", 1, " ", null,
                        null, null, null, AT)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new RadEvent("org-a", RadEventType.ORDER_INTAKED, "order-a", 1, "patient-a", null,
                        null, null, null, null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new RadEvent(null, RadEventType.ORDER_INTAKED, "order-a", 1, "patient-a", null,
                        null, null, null, AT)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new RadEvent("org-a", RadEventType.ORDER_INTAKED, null, 1, "patient-a", null,
                        null, null, null, AT)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new RadEvent("org-a", RadEventType.ORDER_INTAKED, "order-a", 1, null, null,
                        null, null, null, AT)).status());

        assertEquals(event.dedupKey(), new RadEvent("org-a", RadEventType.ORDER_INTAKED, "order-a", 1,
                "different-patient", null, null, null, null, AT.plusSeconds(9)).dedupKey());
        assertEquals(event.eventId(), new RadEvent("org-a", RadEventType.ORDER_INTAKED, "order-a", 1,
                "patient-a", null, null, null, null, AT).eventId());
        assertNotEquals(event.eventId(), new RadEvent("org-a", RadEventType.ORDER_INTAKED, "order-a", 2,
                "patient-a", null, null, null, null, AT).eventId());
        assertNotEquals(event.eventId(), new RadEvent("org-b", RadEventType.ORDER_INTAKED, "order-a", 1,
                "patient-a", null, null, null, null, AT).eventId());
    }

    @Test
    void imagingOrderCarriesApprovedModalityAndStudyReferenceWithoutImageBytes() {
        ImagingOrder associated = new ImagingOrder("org-a", "order-a", "patient-a", "enc-1",
                "CT-CHEST", Urgency.STAT, "question", "source", "radiology", "owner", "queue",
                "referrer", OrderState.STUDY_ASSOCIATED, 4, AT, Modality.CT, "ACC-1", "issuer",
                "1.2.840", "pacs-workstation", "patient-a", "ACC-1");
        assertEquals(Modality.CT, associated.modality());
        assertEquals("1.2.840", associated.studyInstanceUid());
        assertEquals("pacs-workstation", associated.workstationRef());
        assertEquals("patient-a", associated.verifiedPatientId());
        assertEquals(OrderState.STUDY_ASSOCIATED, associated.state());
    }
}
