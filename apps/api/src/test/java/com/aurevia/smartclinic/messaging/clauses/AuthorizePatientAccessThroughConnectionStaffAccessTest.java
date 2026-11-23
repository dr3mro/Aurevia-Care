package com.aurevia.smartclinic.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.audit.AuditAppender;
import com.aurevia.smartclinic.messaging.conversations.ConversationActions;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.AssignRequest;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.Conversation;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.OpenRequest;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.State;
import com.aurevia.smartclinic.messaging.conversations.ConversationRepository;
import com.aurevia.smartclinic.messaging.conversations.ConversationService;
import com.aurevia.smartclinic.messaging.conversations.PatientConnectionVerifier;
import com.aurevia.smartclinic.messaging.conversations.PatientConnectionVerifier.VerifiedConnection;
import com.aurevia.smartclinic.messaging.units.CareUnitDirectoryService;
import com.aurevia.smartclinic.messaging.units.InMemoryMessagingStore;
import com.aurevia.smartclinic.messaging.units.MessagingActions;
import com.aurevia.smartclinic.messaging.units.MessagingContract.CareUnit;
import com.aurevia.smartclinic.messaging.units.MessagingContract.ConnectionContext;
import com.aurevia.smartclinic.messaging.units.MessagingContract.ConnectionState;
import com.aurevia.smartclinic.messaging.units.MessagingContract.MessagingAudit;
import com.aurevia.smartclinic.messaging.units.MessagingContract.PatientUnitConnection;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * ARC-07 clause 4 proof: a patient reaches messaging only through the exact
 * verified, active connection whose identities the server resolves from the
 * tenant directory, and staff reach it only with active membership, an action
 * permission and eligibility for the recipient unit. The connection verifier is
 * exercised through its real directory adapter; one isolated test substitutes a
 * mock verifier solely to prove that the conversation service persists the
 * server-derived identities rather than echoing the request body. Every
 * identifier and subject below is synthetic.
 */
@ExtendWith(MockitoExtension.class)
class AuthorizePatientAccessThroughConnectionStaffAccessTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final String OTHER_PATIENT = "55555555-5555-5555-5555-555555555555";
    private static final String CONVERSATION_ID = "33333333-3333-3333-3333-333333333333";
    private static final String CONNECTION = "44444444-4444-4444-4444-444444444444";
    private static final String UNKNOWN_CONNECTION = "66666666-6666-6666-6666-666666666666";
    private static final String REVOKED_CONNECTION = "77777777-7777-7777-7777-777777777777";
    private static final String FOREIGN_CONNECTION = "88888888-8888-8888-8888-888888888888";
    private static final String RADIOLOGY_CONNECTION = "99999999-9999-9999-9999-999999999999";
    private static final String LAB_UNIT_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String RADIOLOGY_UNIT_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String SERVER_CONNECTION = "connection-server-derived";
    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");

    @Mock
    private ConversationRepository repository;
    @Mock
    private TransactionTemplate transactions;
    @Mock
    private AuditAppender audit;
    @Mock
    private PatientConnectionVerifier verifier;

    private InMemoryMessagingStore store;
    private ConversationService service;

    /**
     * Wire the service with the real action and connection matrices and a
     * transaction template that runs its callback synchronously. The messaging
     * store is real so connection verification is proven at the domain boundary.
     */
    @BeforeEach
    void setUp() {
        store = new InMemoryMessagingStore();
        CareUnitDirectoryService directory = new CareUnitDirectoryService(store, new MessagingActions(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        service = new ConversationService(repository, new ConversationActions(), transactions, audit, directory);
        lenient().when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        bind("patient-a");
        store.addMember(ORG, "patient-a", "patient", true);
        store.addPatient(ORG, PATIENT);
        store.addPatient(ORG, OTHER_PATIENT);
        store.linkPatient(ORG, "patient-a", PATIENT);
        store.addUnit(new CareUnit(LAB_UNIT_ID, ORG, "facility-1", "Synthetic Lab",
                List.of("results"), true, "lab"));
        store.addUnit(new CareUnit(RADIOLOGY_UNIT_ID, ORG, "facility-1", "Synthetic Radiology",
                List.of("imaging"), true, "radiology"));
        seedConnection(CONNECTION, PATIENT, "lab", ConnectionState.ACTIVE);
    }

    /** Clear the thread-local tenant context after each test. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Bind a synthetic caller to the active organization. */
    private void bind(String subject) {
        TenantContext.set(new TenantContext(ORG, subject, "PATIENT_MESSAGING", "corr-clause-4"));
    }

    /** Bind a verified patient caller whose membership fold is active. */
    private void asPatient(String subject) {
        bind(subject);
        when(repository.hasActiveMembership(subject)).thenReturn(true);
        when(repository.activeRoles(subject)).thenReturn(List.of("patient"));
    }

    /** Bind a staff caller whose membership fold is active for the given role. */
    private void asStaff(String subject, String role) {
        bind(subject);
        when(repository.hasActiveMembership(subject)).thenReturn(true);
        when(repository.activeRoles(subject)).thenReturn(List.of(role));
        store.addMember(ORG, subject, role, true);
    }

    /** Seed one synthetic connection owned by a chart and addressed to a unit. */
    private void seedConnection(String id, String patientId, String unitKey, ConnectionState state) {
        String unitId = "lab".equals(unitKey) ? LAB_UNIT_ID : RADIOLOGY_UNIT_ID;
        store.insertConnection(ORG, new PatientUnitConnection(id, ORG, patientId, unitId,
                ConnectionContext.ORDER, "order-ref-" + id, "rel-" + id, state,
                "patient-a", NOW, NOW, null));
    }

    /** Build a conversation snapshot with the given unit, subject and state. */
    private static Conversation conversation(State state, String unitKey, String patientSubject) {
        return new Conversation(CONVERSATION_ID, 1, PATIENT, patientSubject, unitKey, CONNECTION,
                "topic", state, null, 0, 0, NOW);
    }

    /** Assert one successful conversation command audit in the service's audit appender. */
    private void assertSuccessAudit() {
        verify(audit).append(any(), eq("CONVERSATION_OPEN"), eq("CONVERSATION_OPEN"),
                eq("CONVERSATION"), eq("CONVERSATION"), any(), eq("SUCCESS"));
    }

    /** Assert one denial audit for the named conversation command. */
    private void assertDeniedAudit(String action) {
        verify(audit).append(any(), eq(action), eq(action), eq("CONVERSATION"),
                eq("CONVERSATION"), any(), eq("DENIED"));
    }

    /** Assert the directory's connection-use audit records the expected outcome. */
    private void assertConnectionAudit(String outcome) {
        List<MessagingAudit> recorded = store.auditEvents(ORG).stream()
                .filter(event -> "CONNECTION_USE".equals(event.action()))
                .toList();
        assertEquals(1, recorded.size());
        assertEquals(outcome, recorded.get(0).outcome());
    }

    @Test
    void patientOpenPersistsServerDerivedIdentitiesFromTheVerifiedConnection() {
        // The verifier deliberately returns different values from the request:
        // persisting these proves the identities are server-derived, not echoed.
        ConversationService isolated = new ConversationService(repository, new ConversationActions(),
                transactions, audit, verifier);
        when(verifier.requireActive(PATIENT, "lab", CONNECTION)).thenReturn(
                new VerifiedConnection(OTHER_PATIENT, SERVER_CONNECTION, "radiology"));
        asPatient("patient-a");
        when(repository.patientExists(PATIENT)).thenReturn(true);
        when(repository.patientLinkActive("patient-a", PATIENT)).thenReturn(true);
        when(repository.find(any())).thenReturn(Optional.of(conversation(State.AWAITING_STAFF, "radiology", "patient-a")));

        Conversation created = isolated.open(new OpenRequest(PATIENT, "patient-a", "lab", CONNECTION, " topic "));

        assertEquals(State.AWAITING_STAFF, created.state());
        verify(verifier).requireActive(PATIENT, "lab", CONNECTION);
        verify(repository).insertConversation(any(), eq(OTHER_PATIENT), eq("patient-a"), eq("radiology"),
                eq(SERVER_CONNECTION), eq("topic"), eq("awaiting_staff"));
        assertSuccessAudit();
    }

    @Test
    void patientOpenThroughAnActiveConnectionPersistsTheDirectoryDerivedIdentities() {
        asPatient("patient-a");
        when(repository.patientExists(PATIENT)).thenReturn(true);
        when(repository.patientLinkActive("patient-a", PATIENT)).thenReturn(true);
        when(repository.find(any())).thenReturn(Optional.of(conversation(State.AWAITING_STAFF, "lab", "patient-a")));

        Conversation created = service.open(new OpenRequest(PATIENT, "patient-a", "lab", CONNECTION, null));

        assertEquals(State.AWAITING_STAFF, created.state());
        verify(repository).insertConversation(any(), eq(PATIENT), eq("patient-a"), eq("lab"),
                eq(CONNECTION), isNull(), eq("awaiting_staff"));
        assertSuccessAudit();
        assertTrue(store.auditEvents(ORG).isEmpty());
    }

    @Test
    void patientOpenDeniesARevokedConnection() {
        seedConnection(REVOKED_CONNECTION, PATIENT, "lab", ConnectionState.REVOKED);
        asPatient("patient-a");
        when(repository.patientExists(PATIENT)).thenReturn(true);
        when(repository.patientLinkActive("patient-a", PATIENT)).thenReturn(true);

        ApiException failure = assertThrows(ApiException.class,
                () -> service.open(new OpenRequest(PATIENT, "patient-a", "lab", REVOKED_CONNECTION, null)));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        verify(repository, never()).insertConversation(any(), any(), any(), any(), any(), any(), any());
        assertConnectionAudit("DENIED");
    }

    @Test
    void patientOpenDeniesAnotherPatientsConnection() {
        seedConnection(FOREIGN_CONNECTION, OTHER_PATIENT, "lab", ConnectionState.ACTIVE);
        asPatient("patient-a");
        when(repository.patientExists(PATIENT)).thenReturn(true);
        when(repository.patientLinkActive("patient-a", PATIENT)).thenReturn(true);

        ApiException failure = assertThrows(ApiException.class,
                () -> service.open(new OpenRequest(PATIENT, "patient-a", "lab", FOREIGN_CONNECTION, null)));

        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
        verify(repository, never()).insertConversation(any(), any(), any(), any(), any(), any(), any());
        assertConnectionAudit("NOT_FOUND");
    }

    @Test
    void patientOpenDeniesAnUnknownConnection() {
        asPatient("patient-a");
        when(repository.patientExists(PATIENT)).thenReturn(true);
        when(repository.patientLinkActive("patient-a", PATIENT)).thenReturn(true);

        ApiException failure = assertThrows(ApiException.class,
                () -> service.open(new OpenRequest(PATIENT, "patient-a", "lab", UNKNOWN_CONNECTION, null)));

        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
        verify(repository, never()).insertConversation(any(), any(), any(), any(), any(), any(), any());
        assertConnectionAudit("NOT_FOUND");
    }

    @Test
    void patientOpenDeniesAConnectionForAnotherUnit() {
        seedConnection(RADIOLOGY_CONNECTION, PATIENT, "radiology", ConnectionState.ACTIVE);
        asPatient("patient-a");
        when(repository.patientExists(PATIENT)).thenReturn(true);
        when(repository.patientLinkActive("patient-a", PATIENT)).thenReturn(true);

        ApiException failure = assertThrows(ApiException.class,
                () -> service.open(new OpenRequest(PATIENT, "patient-a", "lab", RADIOLOGY_CONNECTION, null)));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        verify(repository, never()).insertConversation(any(), any(), any(), any(), any(), any(), any());
        assertConnectionAudit("DENIED");
    }

    @Test
    void patientOpenRejectsAnUnboundedUnitKeyBeforeAnyLookup() {
        bind("patient-a");

        ApiException failure = assertThrows(ApiException.class,
                () -> service.open(new OpenRequest(PATIENT, "patient-a", "bogus", CONNECTION, null)));

        assertEquals(422, failure.status());
        assertEquals("INVALID_UNITKEY", failure.code());
        verifyNoInteractions(repository, audit);
        assertTrue(store.auditEvents(ORG).isEmpty());
    }

    @Test
    void patientOpenConcealsAnotherSubjectWithoutProbingExistence() {
        asPatient("patient-a");

        ApiException failure = assertThrows(ApiException.class,
                () -> service.open(new OpenRequest(PATIENT, "patient-b", "lab", CONNECTION, null)));

        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
        verify(repository, never()).patientExists(any());
        verify(repository, never()).patientLinkActive(any(), any());
        verify(repository, never()).insertConversation(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void staffOpenAllowsAnEligibleRoleInItsUnit() {
        asStaff("lab-a", "lab_collector");
        when(repository.patientExists(PATIENT)).thenReturn(true);
        when(repository.patientLinkActive("patient-a", PATIENT)).thenReturn(true);
        when(repository.find(any())).thenReturn(Optional.of(conversation(State.AWAITING_PATIENT, "lab", "patient-a")));

        Conversation created = service.open(new OpenRequest(PATIENT, "patient-a", "lab", CONNECTION, null));

        assertEquals(State.AWAITING_PATIENT, created.state());
        verify(repository).insertConversation(any(), eq(PATIENT), eq("patient-a"), eq("lab"),
                eq(CONNECTION), isNull(), eq("awaiting_patient"));
        assertSuccessAudit();
        assertTrue(store.auditEvents(ORG).isEmpty());
    }

    @Test
    void staffOpenDeniesInactiveMembership() {
        bind("inactive-staff");
        when(repository.hasActiveMembership("inactive-staff")).thenReturn(false);

        ApiException failure = assertThrows(ApiException.class,
                () -> service.open(new OpenRequest(PATIENT, "patient-a", "lab", CONNECTION, null)));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        assertTrue(failure.getMessage().contains("no active membership"));
        assertDeniedAudit("CONVERSATION_OPEN");
        verify(repository, never()).activeRoles(anyString());
        verify(repository, never()).patientExists(any());
        verify(repository, never()).insertConversation(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void staffOpenDeniesARoleWithoutTheOpenPermission() {
        asStaff("admin-a", "tenant_admin");

        ApiException failure = assertThrows(ApiException.class,
                () -> service.open(new OpenRequest(PATIENT, "patient-a", "lab", CONNECTION, null)));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        assertTrue(failure.getMessage().contains("missing CONVERSATION_OPEN permission"));
        assertDeniedAudit("CONVERSATION_OPEN");
        verify(repository, never()).patientExists(any());
        verify(repository, never()).insertConversation(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void staffOpenForAnUnassignedUnitIsDenied() {
        asStaff("lab-a", "lab_collector");
        when(repository.patientExists(PATIENT)).thenReturn(true);
        when(repository.patientLinkActive("patient-a", PATIENT)).thenReturn(true);

        ApiException failure = assertThrows(ApiException.class,
                () -> service.open(new OpenRequest(PATIENT, "patient-a", "clinic", CONNECTION, null)));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        assertTrue(failure.getMessage().contains("not eligible for this care unit"));
        verify(repository, never()).insertConversation(any(), any(), any(), any(), any(), any(), any());
        verify(audit, never()).append(any(), any(), any(), any(), any(), any(), eq("SUCCESS"));
        assertTrue(store.auditEvents(ORG).isEmpty());
    }

    @Test
    void staffWorkItemDeniesWrongRoleAndCrossUnit() {
        // A patient has no work-item action permission; the denial is audited.
        asPatient("patient-a");
        ApiException patient = assertThrows(ApiException.class,
                () -> service.assign(CONVERSATION_ID, new AssignRequest("lab-b", " cover "), 1));
        assertEquals(403, patient.status());
        assertEquals("FORBIDDEN", patient.code());
        assertTrue(patient.getMessage().contains("missing WORK_ASSIGN permission"));
        assertDeniedAudit("WORK_ASSIGN");
        verify(repository, never()).loadForUpdate(any());
        verify(repository, never()).assign(any(), anyInt(), any());

        // Reception holds the action but not the lab unit capability; the
        // object-level refusal is thrown inside the work, so no denial is audited.
        asStaff("reception-a", "reception");
        when(repository.loadForUpdate(CONVERSATION_ID)).thenReturn(
                Optional.of(conversation(State.AWAITING_STAFF, "lab", "patient-a")));
        ApiException crossUnit = assertThrows(ApiException.class,
                () -> service.assign(CONVERSATION_ID, new AssignRequest("lab-b", " cover "), 1));
        assertEquals(403, crossUnit.status());
        assertEquals("FORBIDDEN", crossUnit.code());
        assertTrue(crossUnit.getMessage().contains("not eligible for this care unit"));
        verify(repository, never()).assign(any(), anyInt(), any());
    }

    @Test
    void getConcealsAnotherPatientsThreadFromThePatient() {
        asPatient("patient-a");
        when(repository.find(CONVERSATION_ID)).thenReturn(
                Optional.of(conversation(State.AWAITING_STAFF, "lab", "patient-b")));

        ApiException failure = assertThrows(ApiException.class, () -> service.get(CONVERSATION_ID));

        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
        verify(repository, never()).messages(any());
    }

    @Test
    void getDeniesStaffOfAnIneligibleUnitOnAnExistingThread() {
        asStaff("lab-a", "lab_collector");
        when(repository.find(CONVERSATION_ID)).thenReturn(
                Optional.of(conversation(State.AWAITING_STAFF, "clinic", "patient-a")));

        ApiException failure = assertThrows(ApiException.class, () -> service.get(CONVERSATION_ID));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        verify(repository, never()).messages(any());
    }
}
