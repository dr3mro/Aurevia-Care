package com.aurevia.smartclinic.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.audit.AuditAppender;
import com.aurevia.smartclinic.messaging.conversations.ConversationActions;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.Conversation;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.OpenRequest;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.ReplyRequest;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.State;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.View;
import com.aurevia.smartclinic.messaging.conversations.ConversationRepository;
import com.aurevia.smartclinic.messaging.conversations.ConversationService;
import com.aurevia.smartclinic.messaging.conversations.PatientConnectionVerifier;
import com.aurevia.smartclinic.messaging.conversations.PatientConnectionVerifier.VerifiedConnection;
import com.aurevia.smartclinic.messaging.enrollment.EnrollmentContract;
import com.aurevia.smartclinic.messaging.units.CareUnitDirectoryService;
import com.aurevia.smartclinic.messaging.units.InMemoryMessagingStore;
import com.aurevia.smartclinic.messaging.units.MessagingActions;
import com.aurevia.smartclinic.messaging.units.MessagingContract.CareUnit;
import com.aurevia.smartclinic.messaging.units.MessagingContract.ConnectRequest;
import com.aurevia.smartclinic.messaging.units.MessagingContract.ConnectionContext;
import com.aurevia.smartclinic.messaging.units.MessagingContract.ConnectionState;
import com.aurevia.smartclinic.messaging.units.MessagingContract.EligibleRelationship;
import com.aurevia.smartclinic.messaging.units.MessagingContract.PatientUnitConnection;
import com.aurevia.smartclinic.messaging.units.MessagingContract.UnitDirectory;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * MSG-01 clause 4 proof through the normal server-side command entry points: a care-unit
 * directory entry is never an authorization, a relationship never carries demographics that
 * could link a patient to a unit, and a conversation stays bound to one verified connection and
 * one unit. Every subject, patient, unit and reference is synthetic. Denials assert an explicit
 * status and code and the absence of a persisted connection, message, state change or work item.
 */
class BlockArbitraryDirectoryContactSelfLinkingBasedTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "patient-a";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String OTHER_PATIENT = "44444444-4444-4444-4444-444444444444";
    private static final String SECOND_PATIENT = "55555555-5555-5555-5555-555555555555";
    private static final String CLIENT_PATIENT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String CONNECTION_ID = "66666666-6666-6666-6666-666666666666";
    private static final String CLIENT_CONNECTION = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String FOREIGN_CONNECTION = "77777777-7777-7777-7777-777777777777";
    private static final String UNIT = "99999999-9999-9999-9999-999999999999";
    private static final String OTHER_UNIT = "88888888-8888-8888-8888-888888888888";
    private static final String CONVERSATION = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String REFERENCE = "order-ref-1";
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private InMemoryMessagingStore store;
    private CareUnitDirectoryService directory;
    private ConversationRepository repository;
    private TransactionTemplate transactions;
    private AuditAppender audit;
    private ConversationService service;

    /**
     * Build the services with the real bounded action and connection matrices and a test-scoped
     * tenant-keyed store, so verification is exercised at the domain boundary rather than
     * stubbed. Conversation persistence and the audit sink are mocked to observe every write.
     */
    @BeforeEach
    void setUp() {
        store = new InMemoryMessagingStore();
        directory = new CareUnitDirectoryService(store, new MessagingActions(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        repository = mock(ConversationRepository.class);
        transactions = mock(TransactionTemplate.class);
        audit = mock(AuditAppender.class);
        when(transactions.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        service = new ConversationService(repository, new ConversationActions(), transactions, audit, directory);

        bind(SUBJECT);
        store.addMember(ORG, SUBJECT, "patient", true);
        store.addPatient(ORG, PATIENT);
        store.addPatient(ORG, OTHER_PATIENT);
        store.addPatient(ORG, SECOND_PATIENT);
        store.linkPatient(ORG, SUBJECT, PATIENT);
        store.addUnit(new CareUnit(UNIT, ORG, "facility-1", "Synthetic Lab",
                List.of("results"), true, "lab"));
        store.addUnit(new CareUnit(OTHER_UNIT, ORG, "facility-1", "Synthetic Radiology",
                List.of("imaging"), true, "radiology"));
        store.addRelationship(relationship("rel-1", PATIENT, UNIT, ConnectionContext.ORDER,
                REFERENCE, true, null, false));
    }

    /** Remove the thread-local tenant context after each test. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void bind(String subject) {
        TenantContext.set(new TenantContext(ORG, subject, "PURPOSE", "corr-1"));
    }

    private void bindOrg(String organizationId, String subject) {
        TenantContext.set(new TenantContext(organizationId, subject, "PURPOSE", "corr-1"));
    }

    private void asPatient(String subject) {
        bind(subject);
        when(repository.hasActiveMembership(subject)).thenReturn(true);
        when(repository.activeRoles(subject)).thenReturn(List.of("patient"));
    }

    private void asStaff(String subject, String role) {
        bind(subject);
        when(repository.hasActiveMembership(subject)).thenReturn(true);
        when(repository.activeRoles(subject)).thenReturn(List.of(role));
    }

    private static EligibleRelationship relationship(String id, String patient, String unit,
                                                     ConnectionContext context, String reference,
                                                     boolean active, Instant expiresAt, boolean consumed) {
        return new EligibleRelationship(id, ORG, patient, unit, context, reference,
                active, expiresAt, consumed);
    }

    private void seedConnection(String id, String patient, String unit, ConnectionState state) {
        store.insertConnection(ORG, new PatientUnitConnection(id, ORG, patient, unit,
                ConnectionContext.ORDER, "ref-" + id, "rel-" + id, state, SUBJECT, NOW, NOW, null));
    }

    private static Conversation conversation(State state, int version, String patientSubject,
                                              String unitKey, String connectionId) {
        return new Conversation(CONVERSATION, version, PATIENT, patientSubject, unitKey,
                connectionId, "topic", state, null, 0, 0, NOW);
    }

    /** Assert that no denial in a test left a durable conversation, message, work item or state change. */
    private void assertNoConversationWrites() {
        verify(repository, never()).insertConversation(any(), any(), any(), any(), any(), any(), any());
        verify(repository, never()).insertMessage(any(), anyInt(), any(), any(), any(), any());
        verify(repository, never()).updateState(any(), anyInt(), any());
        verify(repository, never()).appendEvent(any(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void patientOpensOnlyThroughVerifiedActiveConnection() {
        // The client names a different chart, unit and connection; only the server-resolved
        // values may be persisted on the conversation.
        PatientConnectionVerifier verifier = mock(PatientConnectionVerifier.class);
        when(verifier.requireActive(anyString(), anyString(), anyString()))
                .thenReturn(new VerifiedConnection(PATIENT, CONNECTION_ID, "lab"));
        ConversationService guarded = new ConversationService(repository, new ConversationActions(),
                transactions, audit, verifier);
        asPatient(SUBJECT);
        when(repository.patientExists(CLIENT_PATIENT)).thenReturn(true);
        when(repository.patientLinkActive(SUBJECT, CLIENT_PATIENT)).thenReturn(true);
        when(repository.find(anyString())).thenReturn(Optional.of(
                conversation(State.AWAITING_STAFF, 1, SUBJECT, "lab", CONNECTION_ID)));

        guarded.open(new OpenRequest(CLIENT_PATIENT, SUBJECT, "clinic", CLIENT_CONNECTION, " topic "));

        verify(verifier).requireActive(CLIENT_PATIENT, "clinic", CLIENT_CONNECTION);
        verify(repository).insertConversation(any(), eq(PATIENT), eq(SUBJECT), eq("lab"),
                eq(CONNECTION_ID), eq("topic"), eq("awaiting_staff"));
    }

    @Test
    void directoryListingAndConnectRefuseContactWithoutActiveVerifiedRelationship() {
        store.addMember(ORG, "patient-b", "patient", true);
        store.linkPatient(ORG, "patient-b", SECOND_PATIENT);
        bind("patient-b");

        assertTrue(directory.eligibleUnits().units().isEmpty());
        ApiException absent = assertThrows(ApiException.class, () -> directory.connect(
                new ConnectRequest(SECOND_PATIENT, UNIT, "order", REFERENCE)));
        assertEquals(422, absent.status());
        assertEquals("NO_ELIGIBLE_RELATIONSHIP", absent.code());

        store.addRelationship(relationship("rel-inactive", SECOND_PATIENT, UNIT,
                ConnectionContext.ORDER, "inactive-ref", false, null, false));
        ApiException inactive = assertThrows(ApiException.class, () -> directory.connect(
                new ConnectRequest(SECOND_PATIENT, UNIT, "order", "inactive-ref")));
        assertEquals(422, inactive.status());
        assertEquals("NO_ELIGIBLE_RELATIONSHIP", inactive.code());

        store.addRelationship(relationship("rel-consumed", SECOND_PATIENT, UNIT,
                ConnectionContext.INVITATION, "consumed-ref", true, NOW.plusSeconds(600), true));
        ApiException consumed = assertThrows(ApiException.class, () -> directory.connect(
                new ConnectRequest(SECOND_PATIENT, UNIT, "invitation", "consumed-ref")));
        assertEquals(422, consumed.status());
        assertEquals("NO_ELIGIBLE_RELATIONSHIP", consumed.code());

        store.addRelationship(relationship("rel-expired", SECOND_PATIENT, UNIT,
                ConnectionContext.INVITATION, "expired-ref", true, NOW.minusSeconds(1), false));
        ApiException expired = assertThrows(ApiException.class, () -> directory.connect(
                new ConnectRequest(SECOND_PATIENT, UNIT, "invitation", "expired-ref")));
        assertEquals(422, expired.status());
        assertEquals("NO_ELIGIBLE_RELATIONSHIP", expired.code());

        assertTrue(directory.eligibleUnits().units().isEmpty());
        assertTrue(store.connectionsFor(ORG, SECOND_PATIENT).isEmpty());
    }

    @Test
    void matchingDemographicsOrAnotherPatientsConnectionCannotSelfLink() {
        // A shared phone, name or date of birth is not a relationship: the only reference types
        // are verified care or ordering relationships, and a connection cannot be borrowed.
        ApiException demographics = assertThrows(ApiException.class, () -> directory.connect(
                new ConnectRequest(PATIENT, UNIT, "demographics", REFERENCE)));
        assertEquals(422, demographics.status());
        assertEquals("INVALID_CONTEXT", demographics.code());

        String[] components = Arrays.stream(EnrollmentContract.RelationshipRef.class.getRecordComponents())
                .map(RecordComponent::getName).toArray(String[]::new);
        assertArrayEquals(new String[] {"relationshipId", "siteVerified"}, components);

        seedConnection(FOREIGN_CONNECTION, OTHER_PATIENT, UNIT, ConnectionState.ACTIVE);
        ApiException borrowed = assertThrows(ApiException.class,
                () -> directory.requireActive(PATIENT, "lab", FOREIGN_CONNECTION));
        assertEquals(404, borrowed.status());
        assertEquals("NOT_FOUND", borrowed.code());
        assertTrue(store.connectionsFor(ORG, PATIENT).isEmpty());
        assertEquals("NOT_FOUND", store.auditEvents(ORG).get(0).outcome());
    }

    @Test
    void otherTenantCannotResolveTheConnection() {
        seedConnection(CONNECTION_ID, PATIENT, UNIT, ConnectionState.ACTIVE);
        bindOrg(OTHER_ORG, "foreign-patient");

        ApiException denial = assertThrows(ApiException.class,
                () -> directory.requireActiveConnectionById(PATIENT, CONNECTION_ID));
        assertEquals(404, denial.status());
        assertEquals("NOT_FOUND", denial.code());
        assertEquals(1, store.connectionsFor(ORG, PATIENT).size());
        assertEquals(1, store.auditEvents(OTHER_ORG).size());
        assertEquals("NOT_FOUND", store.auditEvents(OTHER_ORG).get(0).outcome());
    }

    @Test
    void replyDoesNotShareAConversationAcrossUnits() {
        seedConnection(CONNECTION_ID, PATIENT, UNIT, ConnectionState.ACTIVE);
        ApiException mismatch = assertThrows(ApiException.class,
                () -> directory.requireActive(PATIENT, "radiology", CONNECTION_ID));
        assertEquals(403, mismatch.status());
        assertEquals("FORBIDDEN", mismatch.code());

        asStaff("lab-a", "lab_verifier");
        when(repository.loadForUpdate(CONVERSATION)).thenReturn(Optional.of(
                conversation(State.AWAITING_STAFF, 1, SUBJECT, "clinic", CONNECTION_ID)));
        ApiException wrongUnit = assertThrows(ApiException.class,
                () -> service.reply(CONVERSATION, new ReplyRequest("hello"), 1));
        assertEquals(403, wrongUnit.status());
        assertEquals("FORBIDDEN", wrongUnit.code());
        assertNoConversationWrites();
    }

    @Test
    void wrongRoleStaleVersionClosedThreadAndMissingInputAreRefused() {
        asStaff("admin-a", "tenant_admin");
        when(repository.loadForUpdate(CONVERSATION)).thenReturn(Optional.of(
                conversation(State.AWAITING_STAFF, 1, SUBJECT, "lab", CONNECTION_ID)));
        ApiException wrongRole = assertThrows(ApiException.class,
                () -> service.reply(CONVERSATION, new ReplyRequest("hello"), 1));
        assertEquals(403, wrongRole.status());
        assertEquals("FORBIDDEN", wrongRole.code());

        asPatient(SUBJECT);
        ApiException stale = assertThrows(ApiException.class,
                () -> service.reply(CONVERSATION, new ReplyRequest("hello"), 2));
        assertEquals(412, stale.status());
        assertEquals("STALE_VERSION", stale.code());

        when(repository.loadForUpdate(CONVERSATION)).thenReturn(Optional.of(
                conversation(State.CLOSED, 1, SUBJECT, "lab", CONNECTION_ID)));
        ApiException closed = assertThrows(ApiException.class,
                () -> service.reply(CONVERSATION, new ReplyRequest("hello"), 1));
        assertEquals(409, closed.status());
        assertEquals("CLOSED_THREAD", closed.code());

        ApiException invalidKey = assertThrows(ApiException.class,
                () -> directory.requireActive(PATIENT, "demographics", CONNECTION_ID));
        assertEquals(422, invalidKey.status());
        assertEquals("INVALID_UNITKEY", invalidKey.code());

        assertNoConversationWrites();
        verify(audit, atLeastOnce()).append(any(), any(), any(), any(), any(), any(), eq("DENIED"));
    }

    @Test
    void verifiedRelationshipAllowsItsOwnVerifiedConnectionAndUnit() {
        UnitDirectory allowed = directory.eligibleUnits();
        assertEquals(1, allowed.units().size());
        assertEquals(UNIT, allowed.units().get(0).id());

        PatientUnitConnection granted = directory.connect(
                new ConnectRequest(PATIENT, UNIT, "order", REFERENCE));
        assertEquals(ConnectionState.ACTIVE, granted.state());
        assertEquals(UNIT, granted.unitId());

        VerifiedConnection verified = directory.requireActive(PATIENT, "lab", granted.id());
        assertEquals(PATIENT, verified.patientId());
        assertEquals(granted.id(), verified.connectionId());
        assertEquals("lab", verified.unitKey());
        assertEquals(1, store.connectionsFor(ORG, PATIENT).size());
    }

    @Test
    void conversationAcceptsOnlyItsAuthorizedUnitAndConnection() {
        asStaff("lab-a", "lab_verifier");
        when(repository.loadForUpdate(CONVERSATION)).thenReturn(Optional.of(
                conversation(State.AWAITING_STAFF, 1, SUBJECT, "lab", CONNECTION_ID)));
        when(repository.nextSequence(CONVERSATION)).thenReturn(2);
        when(repository.patientLinkActive(SUBJECT, PATIENT)).thenReturn(true);
        when(repository.find(CONVERSATION)).thenReturn(Optional.of(
                conversation(State.AWAITING_PATIENT, 2, SUBJECT, "lab", CONNECTION_ID)));
        when(repository.messages(CONVERSATION)).thenReturn(List.of());
        when(repository.events(CONVERSATION)).thenReturn(List.of());

        View view = service.reply(CONVERSATION, new ReplyRequest("hello"), 1);

        assertEquals(State.AWAITING_PATIENT, view.conversation().state());
        verify(repository).insertMessage(CONVERSATION, 2, "staff", "lab-a", "lab_verifier", "hello");
        verify(repository).updateState(CONVERSATION, 1, "awaiting_patient");
        verify(repository, never()).appendEvent(eq(CONVERSATION), eq(2), eq("reopened"),
                any(), any(), any(), any());
    }
}
