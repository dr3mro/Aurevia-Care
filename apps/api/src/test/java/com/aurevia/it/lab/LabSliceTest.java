package com.aurevia.it.lab;

import com.aurevia.api.ApiException;
import com.aurevia.lab.accession.AccessionEvent;
import com.aurevia.lab.accession.AccessionState;
import com.aurevia.lab.accession.AccessionView;
import com.aurevia.lab.accession.AccessionCommands.AccessionRequest;
import com.aurevia.lab.accession.AccessionCommands.CollectRequest;
import com.aurevia.lab.accession.AccessionCommands.ReceiveRequest;
import com.aurevia.lab.accession.AccessionCommands.RecollectRequest;
import com.aurevia.lab.accession.AccessionCommands.RejectRequest;
import com.aurevia.lab.accession.LabAccession;
import com.aurevia.lab.accession.LabAccessionController;
import com.aurevia.lab.accession.LabAccessionService;
import com.aurevia.lab.accession.LabActionPolicy;
import com.aurevia.lab.accession.LabInputs;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S-G2-08 LAB-01: the accession state machine admits only the labelled transitions. */
class AccessionStateTest {

    private static final Set<String> ALLOWED = Set.of(
            "ACCESSIONED>COLLECTED",
            "COLLECTED>RECEIVED",
            "COLLECTED>REJECTED",
            "RECEIVED>REJECTED",
            "REJECTED>RECOLLECTED");

    @Test
    void everyStatePairMatchesTheAllowedMatrix() {
        for (AccessionState from : AccessionState.values()) {
            for (AccessionState to : AccessionState.values()) {
                boolean expected = ALLOWED.contains(from.name() + ">" + to.name());
                assertEquals(expected, from.canTransitionTo(to), from + " -> " + to);
            }
        }
        assertFalse(AccessionState.RECOLLECTED.canTransitionTo(AccessionState.ACCESSIONED));
        assertTrue(AccessionState.ACCESSIONED.canTransitionTo(AccessionState.COLLECTED));
    }

    @Test
    void wireNamesAreTheStableDatabaseVocabulary() {
        assertEquals("accessioned", AccessionState.ACCESSIONED.wire());
        assertEquals("collected", AccessionState.COLLECTED.wire());
        assertEquals("received", AccessionState.RECEIVED.wire());
        assertEquals("rejected", AccessionState.REJECTED.wire());
        assertEquals("recollected", AccessionState.RECOLLECTED.wire());
    }
}

/** S-G2-08 LAB-01: lab actions are bounded to collector and verifier duties. */
class LabActionPolicyTest {

    private final LabActionPolicy policy = new LabActionPolicy();

    @Test
    void collectorAccessionsAndCollects() {
        assertTrue(policy.allows(LabActionPolicy.ACCESSION_CREATE, "lab_collector"));
        assertTrue(policy.allows(LabActionPolicy.SPECIMEN_COLLECT, "lab_collector"));
        assertTrue(policy.allows(LabActionPolicy.ACCESSION_READ, "lab_collector"));
        assertFalse(policy.allows(LabActionPolicy.SPECIMEN_RECEIVE, "lab_collector"));
        assertFalse(policy.allows(LabActionPolicy.SPECIMEN_REJECT, "lab_collector"));
    }

    @Test
    void verifierReceivesRejectsAndRecollects() {
        assertTrue(policy.allows(LabActionPolicy.SPECIMEN_RECEIVE, "lab_verifier"));
        assertTrue(policy.allows(LabActionPolicy.SPECIMEN_REJECT, "lab_verifier"));
        assertTrue(policy.allows(LabActionPolicy.SPECIMEN_RECOLLECT, "lab_verifier"));
        assertTrue(policy.allows(LabActionPolicy.ACCESSION_READ, "lab_verifier"));
        assertFalse(policy.allows(LabActionPolicy.SPECIMEN_COLLECT, "lab_verifier"));
    }

    @Test
    void otherRolesCannotPerformLabActions() {
        for (String role : new String[] {"tenant_admin", "privacy_auditor", "reception",
                "clinician", "unknown_role", null}) {
            assertFalse(policy.allows(LabActionPolicy.ACCESSION_CREATE, role), role);
            assertFalse(policy.allows(LabActionPolicy.ACCESSION_READ, role), role);
        }
    }

    @Test
    void unknownActionsAreDenied() {
        assertFalse(policy.allows("LAB_DELETE", "lab_collector"));
        assertFalse(policy.allows(null, "lab_verifier"));
    }
}

/** S-G2-08 LAB-01: every trust-boundary field is bounded before a command opens. */
class LabInputsTest {

    @Test
    void accessionNumberAndIssuerAreBounded() {
        assertEquals("ACC-1", LabInputs.accessionNo("ACC-1"));
        assertEquals("LAB-A", LabInputs.issuer("LAB-A"));
        assertEquals(422, assertThrows(ApiException.class, () -> LabInputs.accessionNo(null)).status());
        assertEquals("INVALID_ACCESSION",
                assertThrows(ApiException.class, () -> LabInputs.accessionNo(" ")).code());
        assertEquals(422, assertThrows(ApiException.class, () -> LabInputs.issuer("")).status());
        assertEquals("INVALID_ISSUER",
                assertThrows(ApiException.class, () -> LabInputs.issuer("a".repeat(33))).code());
    }

    @Test
    void identifiersRejectMalformedValues() {
        assertEquals("ORD/1", LabInputs.sourceOrderRef("ORD/1"));
        assertEquals("PLASMA", LabInputs.specimenType("PLASMA"));
        assertEquals("SITE-A", LabInputs.siteCode("SITE-A"));
        assertEquals("Sam Collector", LabInputs.collector("Sam Collector"));
        assertEquals(422, assertThrows(ApiException.class, () -> LabInputs.sourceOrderRef(null)).status());
        assertEquals("INVALID_ORDER_REF",
                assertThrows(ApiException.class, () -> LabInputs.sourceOrderRef("!")).code());
        assertEquals("INVALID_SPECIMEN_TYPE",
                assertThrows(ApiException.class, () -> LabInputs.specimenType("")).code());
        assertEquals("INVALID_SITE",
                assertThrows(ApiException.class, () -> LabInputs.siteCode("a".repeat(33))).code());
        assertEquals("INVALID_COLLECTOR",
                assertThrows(ApiException.class, () -> LabInputs.collector("@")).code());
    }

    @Test
    void qualityReasonAndNotificationAreBounded() {
        assertEquals("acceptable", LabInputs.quality("acceptable"));
        assertEquals("hemolysed sample", LabInputs.reason("hemolysed sample"));
        assertEquals("lab-a@example.test", LabInputs.notification("lab-a@example.test"));
        assertEquals("INVALID_QUALITY",
                assertThrows(ApiException.class, () -> LabInputs.quality("!")).code());
        assertEquals("INVALID_REASON",
                assertThrows(ApiException.class, () -> LabInputs.reason(" ")).code());
        assertEquals("INVALID_NOTIFICATION",
                assertThrows(ApiException.class, () -> LabInputs.notification(null)).code());
    }

    @Test
    void idAndVersionAreValidated() {
        assertEquals("11111111-1111-1111-1111-111111111111",
                LabInputs.id("11111111-1111-1111-1111-111111111111"));
        assertEquals("22222222-2222-2222-2222-222222222222",
                LabInputs.patientId("22222222-2222-2222-2222-222222222222"));
        assertEquals(422, assertThrows(ApiException.class, () -> LabInputs.id(null)).status());
        assertEquals("INVALID_ID", assertThrows(ApiException.class, () -> LabInputs.id("nope")).code());
        assertEquals("INVALID_PATIENT",
                assertThrows(ApiException.class, () -> LabInputs.patientId("nope")).code());
        assertEquals(1, LabInputs.version(1));
        assertEquals("INVALID_VERSION",
                assertThrows(ApiException.class, () -> LabInputs.version(0)).code());
    }

    @Test
    void instantsMustBeIso8601() {
        assertEquals("2026-11-19T10:15:30Z",
                LabInputs.instant("2026-11-19T10:15:30Z", "collectedAt").toString());
        assertEquals("INVALID_TIMESTAMP",
                assertThrows(ApiException.class, () -> LabInputs.instant(null, "collectedAt")).code());
        assertEquals("INVALID_TIMESTAMP",
                assertThrows(ApiException.class, () -> LabInputs.instant("yesterday", "receivedAt")).code());
    }
}

/** S-G2-08 LAB-01: the immutable history view cannot be mutated through its input list. */
class AccessionViewTest {

    @Test
    void freezesHistoryAgainstCallerMutation() {
        List<AccessionEvent> mutable = new ArrayList<>();
        mutable.add(new AccessionEvent("e1", "id", 1, "accessioned", null,
                "accessioned", null, null, "2026-11-19T09:00:00Z"));
        LabAccession accession = new LabAccession("id", 1, "ACC-1", "LAB-A", "PAT-1", "ORD/1",
                AccessionState.ACCESSIONED, null, null, null, null, null, null, null, null, null);
        AccessionView view = new AccessionView(accession, mutable);

        mutable.add(new AccessionEvent("e2", "id", 2, "collected", "accessioned",
                "collected", null, null, "2026-11-19T10:00:00Z"));

        assertEquals(1, view.events().size());
        assertThrows(UnsupportedOperationException.class,
                () -> view.events().add(mutable.get(0)));
    }
}

/** S-G2-08 LAB-01: the HTTP adapter binds commands without owning any invariant. */
class LabAccessionControllerTest {

    private static final String ID = "33333333-3333-3333-3333-333333333333";

    private LabAccessionService service;
    private LabAccessionController controller;
    private LabAccession accession;

    @BeforeEach
    void setUp() {
        service = mock(LabAccessionService.class);
        controller = new LabAccessionController(service);
        accession = new LabAccession(ID, 1, "ACC-1", "LAB-A", "PAT-1", "ORD/1",
                AccessionState.ACCESSIONED, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void accessionBindsAllFields() {
        AccessionRequest request = new AccessionRequest("ACC-1", "LAB-A", "PAT-1", "ORD/1");
        when(service.accession(any())).thenReturn(accession);
        assertSame(accession, controller.accession(request).getBody());
        assertEquals(HttpStatus.CREATED, controller.accession(request).getStatusCode());
        verify(service, org.mockito.Mockito.times(2)).accession(request);
    }

    @Test
    void collectBindsAllFields() {
        CollectRequest request = new CollectRequest("PAT-1", "ACC-1", "LAB-A", "PLASMA",
                "SITE-A", "Sampler", "2026-11-19T10:15:30Z", 1);
        when(service.collect(ID, request)).thenReturn(accession);
        assertSame(accession, controller.collect(ID, request).getBody());
        assertEquals(HttpStatus.OK, controller.collect(ID, request).getStatusCode());
        verify(service, org.mockito.Mockito.times(2)).collect(ID, request);
    }

    @Test
    void receiveBindsAllFields() {
        ReceiveRequest request = new ReceiveRequest("PAT-1", "ACC-1", "LAB-A", "acceptable",
                "2026-11-19T11:15:30Z", 2);
        when(service.receive(ID, request)).thenReturn(accession);
        assertSame(accession, controller.receive(ID, request).getBody());
        assertEquals(HttpStatus.OK, controller.receive(ID, request).getStatusCode());
        verify(service, org.mockito.Mockito.times(2)).receive(ID, request);
    }

    @Test
    void rejectBindsAllFields() {
        RejectRequest request = new RejectRequest("hemolysed", "lab@example.test", 3);
        when(service.reject(ID, request)).thenReturn(accession);
        assertSame(accession, controller.reject(ID, request).getBody());
        assertEquals(HttpStatus.OK, controller.reject(ID, request).getStatusCode());
        verify(service, org.mockito.Mockito.times(2)).reject(ID, request);
    }

    @Test
    void recollectBindsAllFields() {
        RecollectRequest request = new RecollectRequest("ACC-2", "LAB-A", 4);
        when(service.recollect(ID, request)).thenReturn(accession);
        assertSame(accession, controller.recollect(ID, request).getBody());
        assertEquals(HttpStatus.CREATED, controller.recollect(ID, request).getStatusCode());
        verify(service, org.mockito.Mockito.times(2)).recollect(ID, request);
    }

    @Test
    void getReturnsTheView() {
        AccessionEvent event = new AccessionEvent("e1", ID, 1, "accessioned", null,
                AccessionState.ACCESSIONED.wire(), null, "PAT-1", "2026-11-19T09:00:00Z");
        AccessionView view = new AccessionView(accession, List.of(event));
        when(service.get(anyString())).thenReturn(view);
        assertSame(view, controller.get(ID));
        verify(service).get(ID);
    }
}
