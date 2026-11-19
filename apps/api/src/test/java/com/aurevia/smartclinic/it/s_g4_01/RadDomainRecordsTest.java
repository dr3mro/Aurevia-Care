package com.aurevia.smartclinic.it.s_g4_01;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.g4.s_g4_01.ImagingOrder;
import com.aurevia.smartclinic.g4.s_g4_01.Interpretation;
import com.aurevia.smartclinic.g4.s_g4_01.RadEvent;
import com.aurevia.smartclinic.g4.s_g4_01.RadTypes.InterpretationKind;
import com.aurevia.smartclinic.g4.s_g4_01.RadTypes.Modality;
import com.aurevia.smartclinic.g4.s_g4_01.RadTypes.OrderState;
import com.aurevia.smartclinic.g4.s_g4_01.RadTypes.RadEventType;
import com.aurevia.smartclinic.g4.s_g4_01.RadTypes.Urgency;

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
        assertNotEquals(first.checksum(), interpretation("org-a", "order-a", 1, null,
                InterpretationKind.ADDENDUM, "signer-a").checksum());
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
