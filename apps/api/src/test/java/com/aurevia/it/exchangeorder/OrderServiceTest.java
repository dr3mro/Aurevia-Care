package com.aurevia.it.exchangeorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.exchange.order.AuthorizationRequest;
import com.aurevia.exchange.order.AuthorizationView;
import com.aurevia.exchange.order.OrderController;
import com.aurevia.exchange.order.OrderRepository;
import com.aurevia.exchange.order.OrderRequest;
import com.aurevia.exchange.order.OrderService;
import com.aurevia.exchange.order.OrderView;
import com.aurevia.exchange.order.VerifyPatientRequest;
import com.aurevia.exchange.order.WorkItemView;
import com.aurevia.tenant.TenantContext;

/** S-G2-01 service branches: XCH-01 authorization, snapshot, disclosure and work item. */
class OrderServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String DEST = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String LOCAL_PATIENT = "44444444-4444-4444-4444-444444444444";
    private static final String GRANT = "55555555-5555-5555-5555-555555555555";
    private static final String ACTOR = "clinician-a";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private OrderRepository repository;
    private AuditAppender audit;
    private OrderService service;

    @BeforeEach
    void setUp() {
        repository = mock(OrderRepository.class);
        audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        service = new OrderService(repository, audit, transactions,
                Clock.fixed(NOW, ZoneOffset.UTC));
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, "EXCHANGE", "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorized(String role) {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of(role));
    }

    private static AuthorizationView grant() {
        return new AuthorizationView(GRANT, ORG, "CBC", DEST, "lab review", 60,
                NOW.minusSeconds(60), NOW.plusSeconds(3600), true);
    }

    private static VerifyPatientRequest verifyRequest() {
        return new VerifyPatientRequest(LOCAL_PATIENT, "MRN-1", "CLINIC", "site-verified MRN match");
    }

    private static OrderRequest request() {
        return new OrderRequest(PATIENT, "ENC-1", "CBC", "routine", "Synthetic CBC question",
                DEST, "lab review", GRANT, "dr-a", "lab-queue", 30);
    }

    private static AuthorizationRequest authorizationRequest() {
        return new AuthorizationRequest("CBC", DEST, "lab review", 60, 7);
    }

    @Test
    void createsAnAuthorizedOrderWithSnapshotDisclosureAndWorkItem() {
        authorized("clinician");
        when(repository.authorization(GRANT)).thenReturn(Optional.of(grant()));
        when(repository.patientIdentity(PATIENT)).thenReturn(Optional.of(new OrderRepository.PatientIdentity("MRN-1", "CLINIC")));

        OrderView created = service.create(request());

        assertEquals(1, created.version());
        assertEquals("routine", created.priority());
        assertEquals(ACTOR, created.sourceActor());
        assertEquals(DEST, created.destinationOrg());
        assertEquals(GRANT, created.authorizationId());
        assertEquals(36, created.snapshotFingerprint().length());
        assertNotNull(created.snapshotId());
        assertNotNull(created.disclosureId());
        assertNotNull(created.workItemId());
        verify(repository).insertOrder(created);
        verify(repository).appendSnapshot(org.mockito.ArgumentMatchers.eq(created.snapshotId()),
                org.mockito.ArgumentMatchers.eq(created.id()), org.mockito.ArgumentMatchers.eq(1),
                anyString(), org.mockito.ArgumentMatchers.eq(created.snapshotFingerprint()));
        verify(repository).appendDisclosure(created.disclosureId(), created.id(), DEST,
                "lab review", 30, NOW.plusSeconds(1800), created.snapshotFingerprint());
        verify(repository).insertWorkItem(created.workItemId(), DEST, GRANT, created.id(),
                PATIENT, "MRN-1", "CLINIC", "routine", "Synthetic CBC question", "lab-queue",
                created.snapshotFingerprint());
        verify(audit).append(any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), org.mockito.ArgumentMatchers.eq("SUCCESS"));
    }

    @Test
    void fingerprintIsStableForTheSamePayloadAndDifferentForChangedContent() {
        authorized("clinician");
        when(repository.authorization(GRANT)).thenReturn(Optional.of(grant()));
        when(repository.patientIdentity(PATIENT)).thenReturn(Optional.of(new OrderRepository.PatientIdentity("MRN-1", "CLINIC")));
        OrderView first = service.create(request());
        OrderView second = service.create(request());
        OrderView changed = service.create(new OrderRequest(PATIENT, "ENC-1", "CBC", "routine",
                "A different question", DEST, "lab review", GRANT, "dr-a", "lab-queue", 30));
        assertEquals(first.snapshotFingerprint(), second.snapshotFingerprint());
        assertFalse(first.snapshotFingerprint().equals(changed.snapshotFingerprint()));
        assertFalse(first.id().equals(second.id()), "every release gets a fresh opaque identifier");
    }

    private ApiException denial(String role, AuthorizationView granted) {
        authorized(role);
        when(repository.authorization(GRANT)).thenReturn(Optional.ofNullable(granted));
        when(repository.patientIdentity(PATIENT)).thenReturn(Optional.of(new OrderRepository.PatientIdentity("MRN-1", "CLINIC")));
        return assertThrows(ApiException.class, () -> service.create(request()));
    }

    @Test
    void deniesWhenThereIsNoActiveMembership() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        ApiException failure = assertThrows(ApiException.class, () -> service.create(request()));
        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        verify(repository, never()).insertOrder(any());
    }

    @Test
    void deniesWhenTheRoleCannotOrder() {
        ApiException failure = denial("reception", grant());
        assertEquals(403, failure.status());
        verify(repository, never()).insertOrder(any());
    }

    @Test
    void deniesWhenTheAuthorizationDoesNotExist() {
        ApiException failure = denial("clinician", null);
        assertEquals(403, failure.status());
        assertEquals("DISCLOSURE_NOT_AUTHORIZED", failure.code());
    }

    @Test
    void deniesWhenTheAuthorizationIsInactive() {
        ApiException failure = denial("clinician", new AuthorizationView(GRANT, ORG, "CBC", DEST,
                "lab review", 60, NOW.minusSeconds(60), NOW.plusSeconds(3600), false));
        assertEquals(403, failure.status());
        assertEquals("DISCLOSURE_NOT_AUTHORIZED", failure.code());
    }

    @Test
    void deniesWhenTheAuthorizationIsNotYetEffective() {
        ApiException failure = denial("clinician", new AuthorizationView(GRANT, ORG, "CBC", DEST,
                "lab review", 60, NOW.plusSeconds(1), NOW.plusSeconds(3600), true));
        assertEquals(403, failure.status());
        assertEquals("DISCLOSURE_NOT_AUTHORIZED", failure.code());
    }

    @Test
    void deniesWhenTheAuthorizationHasExpired() {
        ApiException failure = denial("clinician", new AuthorizationView(GRANT, ORG, "CBC", DEST,
                "lab review", 60, NOW.minusSeconds(120), NOW, true));
        assertEquals(403, failure.status());
        assertEquals("DISCLOSURE_NOT_AUTHORIZED", failure.code());
    }

    @Test
    void acceptsAnAuthorizationWithoutAnExpiry() {
        authorized("clinician");
        when(repository.authorization(GRANT)).thenReturn(Optional.of(new AuthorizationView(
                GRANT, ORG, "CBC", DEST, "lab review", 60, NOW.minusSeconds(60), null, true)));
        when(repository.patientIdentity(PATIENT)).thenReturn(Optional.of(new OrderRepository.PatientIdentity("MRN-1", "CLINIC")));
        OrderView created = service.create(request());
        assertEquals("CBC", created.orderCode());
        verify(repository).insertOrder(created);
    }

    @Test
    void deniesWhenTheOrderCodeIsOutsideTheAuthorization() {
        authorized("clinician");
        when(repository.authorization(GRANT)).thenReturn(Optional.of(grant()));
        when(repository.patientIdentity(PATIENT)).thenReturn(Optional.of(new OrderRepository.PatientIdentity("MRN-1", "CLINIC")));
        ApiException failure = assertThrows(ApiException.class, () -> service.create(
                new OrderRequest(PATIENT, "ENC-1", "CMP", "routine", "Synthetic question",
                        DEST, "lab review", GRANT, "dr-a", "lab-queue", 30)));
        assertEquals(403, failure.status());
        assertEquals("DISCLOSURE_NOT_AUTHORIZED", failure.code());
    }

    @Test
    void deniesWhenTheDestinationIsNotTheAuthorizedRecipient() {
        authorized("clinician");
        when(repository.authorization(GRANT)).thenReturn(Optional.of(grant()));
        when(repository.patientIdentity(PATIENT)).thenReturn(Optional.of(new OrderRepository.PatientIdentity("MRN-1", "CLINIC")));
        String other = "66666666-6666-6666-6666-666666666666";
        ApiException failure = assertThrows(ApiException.class, () -> service.create(
                new OrderRequest(PATIENT, "ENC-1", "CBC", "routine", "Synthetic question",
                        other, "lab review", GRANT, "dr-a", "lab-queue", 30)));
        assertEquals(403, failure.status());
        assertEquals("DISCLOSURE_NOT_AUTHORIZED", failure.code());
    }

    @Test
    void deniesWhenThePurposeIsNotAuthorized() {
        authorized("clinician");
        when(repository.authorization(GRANT)).thenReturn(Optional.of(grant()));
        when(repository.patientIdentity(PATIENT)).thenReturn(Optional.of(new OrderRepository.PatientIdentity("MRN-1", "CLINIC")));
        ApiException failure = assertThrows(ApiException.class, () -> service.create(
                new OrderRequest(PATIENT, "ENC-1", "CBC", "routine", "Synthetic question",
                        DEST, "marketing", GRANT, "dr-a", "lab-queue", 30)));
        assertEquals(403, failure.status());
        assertEquals("DISCLOSURE_NOT_AUTHORIZED", failure.code());
    }

    @Test
    void deniesWhenTheDurationExceedsTheAuthorization() {
        authorized("clinician");
        when(repository.authorization(GRANT)).thenReturn(Optional.of(grant()));
        when(repository.patientIdentity(PATIENT)).thenReturn(Optional.of(new OrderRepository.PatientIdentity("MRN-1", "CLINIC")));
        ApiException failure = assertThrows(ApiException.class, () -> service.create(
                new OrderRequest(PATIENT, "ENC-1", "CBC", "routine", "Synthetic question",
                        DEST, "lab review", GRANT, "dr-a", "lab-queue", 61)));
        assertEquals(403, failure.status());
        assertEquals("DISCLOSURE_NOT_AUTHORIZED", failure.code());
    }

    @Test
    void concealsAPatientOutsideTheActiveTenant() {
        authorized("clinician");
        when(repository.authorization(GRANT)).thenReturn(Optional.of(grant()));
        when(repository.patientIdentity(PATIENT)).thenReturn(Optional.empty());
        ApiException failure = assertThrows(ApiException.class, () -> service.create(request()));
        assertEquals(404, failure.status());
        verify(repository, never()).insertOrder(any());
    }

    @Test
    void anAdminCreatesAnApprovedDestinationAuthorization() {
        authorized("tenant_admin");
        AuthorizationView created = service.authorize(authorizationRequest());
        assertEquals("CBC", created.orderCode());
        assertEquals(DEST, created.destinationOrg());
        assertEquals(60, created.maxDurationMinutes());
        assertEquals(NOW.plusSeconds(7L * 24 * 3600), created.expiresAt());
        verify(repository).insertAuthorization(created);
        verify(audit).append(any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), org.mockito.ArgumentMatchers.eq("SUCCESS"));
    }

    @Test
    void authorizationCreationRequiresAnAdminRole() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("clinician"));
        ApiException failure = assertThrows(ApiException.class,
                () -> service.authorize(authorizationRequest()));
        assertEquals(403, failure.status());
        verify(repository, never()).insertAuthorization(any());
    }

    @Test
    void readsItsOwnOrderAndConcealsAMissingOne() {
        authorized("clinician");
        OrderView expected = new OrderView("77777777-7777-7777-7777-777777777777", 1, PATIENT,
                "ENC-1", "CBC", "routine", "Synthetic question", DEST, ACTOR, "dr-a", "lab-queue",
                GRANT, "s1", "f1", "d1", "77777777-7777-7777-7777-777777777777", NOW);
        when(repository.find(expected.id())).thenReturn(Optional.of(expected));
        assertEquals(expected, service.get(expected.id()));
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.get("88888888-8888-8888-8888-888888888888")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.get("not-a-uuid")).status());
    }

    @Test
    void deniesReadingOrVerifyingWithoutTheExchangeRole() {
        authorized("reception");
        assertEquals(403, assertThrows(ApiException.class, () -> service.get(
                "77777777-7777-7777-7777-777777777777")).status());
        assertEquals(403, assertThrows(ApiException.class, () -> service.verifyPatient(
                "77777777-7777-7777-7777-777777777777", verifyRequest())).status());
    }

    @Test
    void concealsARepeatVerification() {
        authorized("clinician");
        when(repository.workItem("77777777-7777-7777-7777-777777777777")).thenReturn(
                Optional.of(new WorkItemView("77777777-7777-7777-7777-777777777777", ORG, DEST,
                        ORG, PATIENT, "MRN-1", "CLINIC", null, false, "routine", "question", "lab-queue", "f1",
                        "awaiting_local_patient", NOW)));
        when(repository.patientExists(LOCAL_PATIENT)).thenReturn(true);
        when(repository.localPatientMatches(LOCAL_PATIENT, "MRN-1", "CLINIC")).thenReturn(true);
        when(repository.verifyPatient("77777777-7777-7777-7777-777777777777", LOCAL_PATIENT))
                .thenReturn(0);
        ApiException failure = assertThrows(ApiException.class,
                () -> service.verifyPatient("77777777-7777-7777-7777-777777777777", verifyRequest()));
        assertEquals(409, failure.status());
        assertEquals("ALREADY_VERIFIED", failure.code());
    }

    @Test
    void refusesToBindAnUnrelatedLocalPatient() {
        authorized("clinician");
        WorkItemView item = new WorkItemView("77777777-7777-7777-7777-777777777777", ORG, DEST,
                "77777777-7777-7777-7777-777777777777", PATIENT, "MRN-1", "CLINIC", null, false,
                "routine", "question", "lab-queue", "f1", "awaiting_local_patient", NOW);
        when(repository.workItem("77777777-7777-7777-7777-777777777777")).thenReturn(Optional.of(item));
        when(repository.patientExists(LOCAL_PATIENT)).thenReturn(true);

        // A wrong MRN is rejected before the database match is even attempted.
        ApiException wrongMrn = assertThrows(ApiException.class, () -> service.verifyPatient(
                "77777777-7777-7777-7777-777777777777",
                new VerifyPatientRequest(LOCAL_PATIENT, "MRN-2", "CLINIC", "attested")));
        assertEquals(403, wrongMrn.status());
        assertEquals("IDENTITY_MISMATCH", wrongMrn.code());
        ApiException wrongIssuer = assertThrows(ApiException.class, () -> service.verifyPatient(
                "77777777-7777-7777-7777-777777777777",
                new VerifyPatientRequest(LOCAL_PATIENT, "MRN-1", "OTHER", "attested")));
        assertEquals("IDENTITY_MISMATCH", wrongIssuer.code());

        // A matching projected MRN still needs a real receiver-side identifier match.
        when(repository.localPatientMatches(LOCAL_PATIENT, "MRN-1", "CLINIC")).thenReturn(false);
        ApiException unmatched = assertThrows(ApiException.class,
                () -> service.verifyPatient("77777777-7777-7777-7777-777777777777", verifyRequest()));
        assertEquals(403, unmatched.status());
        verify(repository, never()).verifyPatient(anyString(), anyString());

        // Every identity field is validated at the boundary.
        assertEquals(422, assertThrows(ApiException.class, () -> service.verifyPatient(
                "77777777-7777-7777-7777-777777777777",
                new VerifyPatientRequest(LOCAL_PATIENT, " ", "CLINIC", "attested"))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.verifyPatient(
                "77777777-7777-7777-7777-777777777777",
                new VerifyPatientRequest(LOCAL_PATIENT, "MRN-1", " ", "attested"))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.verifyPatient(
                "77777777-7777-7777-7777-777777777777",
                new VerifyPatientRequest(LOCAL_PATIENT, "MRN-1", "CLINIC", " "))).status());
    }

    @Test
    void listsWorkItemsOnlyForAuthorizedReceiverStaff() {
        authorized("clinician");
        WorkItemView item = new WorkItemView("77777777-7777-7777-7777-777777777777", ORG, DEST,
                "77777777-7777-7777-7777-777777777777", PATIENT, "MRN-1", "CLINIC", null, false,
                "routine", "Synthetic question", "lab-queue", "f1",
                "awaiting_local_patient", NOW);
        when(repository.workItems()).thenReturn(List.of(item));
        assertEquals(List.of(item), service.workItems());
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("reception"));
        assertEquals(403, assertThrows(ApiException.class, () -> service.workItems()).status());
    }

    @Test
    void verifiesTheLocalPatientAndConcealsMissingRecords() {
        authorized("clinician");
        WorkItemView unverified = new WorkItemView("77777777-7777-7777-7777-777777777777", ORG, DEST,
                "77777777-7777-7777-7777-777777777777", PATIENT, "MRN-1", "CLINIC", null, false,
                "routine", "Synthetic question", "lab-queue", "f1",
                "awaiting_local_patient", NOW);
        WorkItemView verified = new WorkItemView("77777777-7777-7777-7777-777777777777", ORG, DEST,
                "77777777-7777-7777-7777-777777777777", PATIENT, "MRN-1", "CLINIC", LOCAL_PATIENT, true,
                "routine", "Synthetic question", "lab-queue", "f1", "ready", NOW);
        when(repository.workItem("77777777-7777-7777-7777-777777777777")).thenReturn(Optional.of(unverified), Optional.of(verified));
        when(repository.patientExists(LOCAL_PATIENT)).thenReturn(true);
        when(repository.localPatientMatches(LOCAL_PATIENT, "MRN-1", "CLINIC")).thenReturn(true);
        when(repository.verifyPatient("77777777-7777-7777-7777-777777777777", LOCAL_PATIENT)).thenReturn(1);
        assertEquals(verified, service.verifyPatient("77777777-7777-7777-7777-777777777777", verifyRequest()));
        verify(repository).verifyPatient("77777777-7777-7777-7777-777777777777", LOCAL_PATIENT);

        assertEquals(404, assertThrows(ApiException.class,
                () -> service.verifyPatient("99999999-9999-9999-9999-999999999999", verifyRequest()))
                .status());
        when(repository.patientExists(LOCAL_PATIENT)).thenReturn(false);
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.verifyPatient("77777777-7777-7777-7777-777777777777", verifyRequest())).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.verifyPatient("not-a-uuid", verifyRequest())).status());
    }

    @Test
    void controllerDelegatesEveryRouteToTheDomainService() {
        OrderService mocked = mock(OrderService.class);
        OrderController controller = new OrderController(mocked);
        OrderRequest command = request();
        OrderView order = new OrderView("77777777-7777-7777-7777-777777777777", 1, PATIENT,
                "ENC-1", "CBC", "routine", "question", DEST, ACTOR, "dr-a", "lab-queue",
                GRANT, "s1", "f1", "d1", "w1", NOW);
        AuthorizationView authorization = grant();
        WorkItemView item = new WorkItemView("77777777-7777-7777-7777-777777777777", ORG, DEST,
                order.id(), PATIENT, "MRN-1", "CLINIC", null, false, "routine", "question", "lab-queue", "f1",
                "awaiting_local_patient", NOW);
        when(mocked.create(command)).thenReturn(order);
        when(mocked.get(order.id())).thenReturn(order);
        when(mocked.authorize(authorizationRequest())).thenReturn(authorization);
        when(mocked.workItems()).thenReturn(List.of(item));
        when(mocked.verifyPatient(order.id(), verifyRequest())).thenReturn(item);

        assertEquals(201, controller.create(command).getStatusCode().value());
        assertEquals("/api/v1/orders/" + order.id(),
                controller.create(command).getHeaders().getLocation().toString());
        assertEquals(order, controller.get(order.id()));
        assertEquals(201, controller.authorize(authorizationRequest()).getStatusCode().value());
        assertEquals("/api/v1/exchange/authorizations/" + authorization.id(),
                controller.authorize(authorizationRequest()).getHeaders().getLocation().toString());
        assertEquals(List.of(item), controller.workItems());
        assertEquals(item, controller.verifyPatient(order.id(),
                verifyRequest()));
    }

    @Test
    void validationFailuresHappenBeforeAnyWrite() {
        ApiException badPatient = assertThrows(ApiException.class, () -> service.create(
                new OrderRequest("bad", "ENC-1", "CBC", "routine", "question", DEST,
                        "lab review", GRANT, "dr-a", "lab-queue", 30)));
        assertEquals(422, badPatient.status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.create(
                new OrderRequest(PATIENT, "  ", "CBC", "routine", "question", DEST,
                        "lab review", GRANT, "dr-a", "lab-queue", 30))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.create(
                new OrderRequest(PATIENT, "ENC-1", "", "routine", "question", DEST,
                        "lab review", GRANT, "dr-a", "lab-queue", 30))).status());
        assertEquals("INVALID_PRIORITY", assertThrows(ApiException.class, () -> service.create(
                new OrderRequest(PATIENT, "ENC-1", "CBC", "soon", "question", DEST,
                        "lab review", GRANT, "dr-a", "lab-queue", 30))).code());
        assertEquals(422, assertThrows(ApiException.class, () -> service.create(
                new OrderRequest(PATIENT, "ENC-1", "CBC", "routine", " ", DEST,
                        "lab review", GRANT, "dr-a", "lab-queue", 30))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.create(
                new OrderRequest(PATIENT, "ENC-1", "CBC", "routine", "question", "bad",
                        "lab review", GRANT, "dr-a", "lab-queue", 30))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.create(
                new OrderRequest(PATIENT, "ENC-1", "CBC", "routine", "question", DEST,
                        " ", GRANT, "dr-a", "lab-queue", 30))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.create(
                new OrderRequest(PATIENT, "ENC-1", "CBC", "routine", "question", DEST,
                        "lab review", "bad", "dr-a", "lab-queue", 30))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.create(
                new OrderRequest(PATIENT, "ENC-1", "CBC", "routine", "question", DEST,
                        "lab review", GRANT, " ", "lab-queue", 30))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.create(
                new OrderRequest(PATIENT, "ENC-1", "CBC", "routine", "question", DEST,
                        "lab review", GRANT, "dr-a", " ", 30))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.create(
                new OrderRequest(PATIENT, "ENC-1", "CBC", "routine", "question", DEST,
                        "lab review", GRANT, "dr-a", "lab-queue", 0))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.authorize(
                new AuthorizationRequest(" ", DEST, "lab review", 60, 7))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.authorize(
                new AuthorizationRequest("CBC", "bad", "lab review", 60, 7))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.authorize(
                new AuthorizationRequest("CBC", DEST, " ", 60, 7))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.authorize(
                new AuthorizationRequest("CBC", DEST, "lab review", 0, 7))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.authorize(
                new AuthorizationRequest("CBC", DEST, "lab review", 60, 0))).status());
        verify(repository, never()).insertOrder(any());
        verify(repository, never()).insertAuthorization(any());
    }

    @Test
    void authorizationValidationBoundsAreEnforced() {
        authorized("tenant_admin");
        assertEquals(43200, service.authorize(new AuthorizationRequest("CBC", DEST,
                "lab review", 43200, 1)).maxDurationMinutes());
        assertTrue(service.authorize(new AuthorizationRequest("CBC", DEST,
                "lab review", 1, 365)).expiresAt().isAfter(NOW));
        assertEquals(422, assertThrows(ApiException.class, () -> service.authorize(
                new AuthorizationRequest("CBC", DEST, "lab review", 43201, 7))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.authorize(
                new AuthorizationRequest("CBC", DEST, "lab review", 60, 366))).status());
    }
}
