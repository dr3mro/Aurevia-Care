package com.aurevia.acceptance.patient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.messaging.conversations.ConversationActions;
import com.aurevia.messaging.conversations.ConversationContract.Conversation;
import com.aurevia.messaging.conversations.ConversationContract.OpenRequest;
import com.aurevia.messaging.conversations.ConversationContract.ReplyRequest;
import com.aurevia.messaging.conversations.ConversationContract.State;
import com.aurevia.messaging.conversations.ConversationContract.UnitKey;
import com.aurevia.messaging.conversations.ConversationRepository;
import com.aurevia.messaging.conversations.ConversationService;
import com.aurevia.messaging.units.CareUnitDirectoryService;
import com.aurevia.messaging.units.InMemoryMessagingStore;
import com.aurevia.messaging.units.MessagingActions;
import com.aurevia.messaging.units.MessagingContract.CareUnit;
import com.aurevia.messaging.units.MessagingContract.ConnectRequest;
import com.aurevia.messaging.units.MessagingContract.ConnectionContext;
import com.aurevia.messaging.units.MessagingContract.EligibleRelationship;
import com.aurevia.messaging.units.MessagingContract.PatientUnitConnection;
import com.aurevia.tenant.TenantContext;

/**
 * Gate G3 acceptance for connection-scoped conversations.
 *
 * <p>Requirements proved: MSG-01 (a patient connects only through a verified
 * relationship and cannot address another unit), MSG-02 (patient and unit can
 * each open a conversation) and MSG-07 (revocation stops future messaging).
 * All fixtures are synthetic.</p>
 */
@SuppressWarnings("unchecked")
class PatientConversationAcceptanceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final String CONVERSATION = "33333333-3333-3333-3333-333333333333";
    private static final String LAB_UNIT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String RADIOLOGY_UNIT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String PATIENT_SUBJECT = "syn-patient-conversation";
    private static final String STAFF_SUBJECT = "syn-lab-collector";
    private static final String RELATIONSHIP = "relationship-1";
    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    private ConversationRepository repository;
    private TransactionTemplate transactions;
    private AuditAppender audit;
    private InMemoryMessagingStore unitStore;
    private CareUnitDirectoryService directory;
    private ConversationService service;
    private String connectionId;
    private String insertedState = State.AWAITING_STAFF.wire();

    @BeforeEach
    void setUp() {
        repository = mock(ConversationRepository.class);
        transactions = mock(TransactionTemplate.class);
        audit = mock(AuditAppender.class);
        unitStore = new InMemoryMessagingStore();
        directory = new CareUnitDirectoryService(unitStore, new MessagingActions(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        service = new ConversationService(repository, new ConversationActions(), transactions, audit, directory);
        when(transactions.execute(any(TransactionCallback.class))).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));

        unitStore.addMember(ORG, PATIENT_SUBJECT, "patient", true);
        unitStore.addPatient(ORG, PATIENT);
        unitStore.linkPatient(ORG, PATIENT_SUBJECT, PATIENT);
        unitStore.addUnit(new CareUnit(LAB_UNIT, ORG, "facility-1", "Synthetic Lab", "lab",
                List.of("results"), true));
        unitStore.addUnit(new CareUnit(RADIOLOGY_UNIT, ORG, "facility-1", "Synthetic Radiology", "radiology",
                List.of("imaging"), true));
        unitStore.addRelationship(new EligibleRelationship(RELATIONSHIP, ORG, PATIENT, LAB_UNIT,
                ConnectionContext.ORDER, "order-1", true, null, false));

        bind(PATIENT_SUBJECT);
        // Create the verified connection once, while the patient context is
        // bound, so repository stubs can reuse its authoritative identifier.
        connectionId = connect().id();
        when(repository.hasActiveMembership(PATIENT_SUBJECT)).thenReturn(true);
        when(repository.activeRoles(PATIENT_SUBJECT)).thenReturn(List.of("patient"));
        when(repository.patientExists(PATIENT)).thenReturn(true);
        when(repository.patientLinkActive(PATIENT_SUBJECT, PATIENT)).thenReturn(true);
        // Reflect the state the service actually persisted back into the
        // projection returned by find(), so open() assertions prove the command.
        doAnswer(invocation -> {
            insertedState = invocation.getArgument(6);
            return 1;
        }).when(repository).insertConversation(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString());
        when(repository.find(anyString())).thenAnswer(invocation -> Optional.of(new Conversation(
                invocation.getArgument(0), 1, PATIENT, PATIENT_SUBJECT, UnitKey.LAB.wire(),
                connectionId, null, State.from(insertedState), null, 0, 0, NOW)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void patientCanOpenAConversationThroughTheVerifiedConnection() {
        // MSG-01 / MSG-02: the connection is created from the verified order
        // relationship, then used to open a unit-scoped conversation.
        PatientUnitConnection connection = connect();
        assertEquals(LAB_UNIT, connection.unitId());

        Conversation opened = service.open(new OpenRequest(PATIENT, PATIENT_SUBJECT,
                UnitKey.LAB.wire(), connection.id(), "results"));

        assertEquals(UnitKey.LAB.wire(), opened.unitKey());
        assertEquals(connection.id(), opened.connectionId());
        assertEquals(State.AWAITING_STAFF, opened.state());
        verify(repository).insertConversation(opened.id(), PATIENT, PATIENT_SUBJECT, UnitKey.LAB.wire(),
                connection.id(), "results", State.AWAITING_STAFF.wire());
    }

    @Test
    void unitStaffCanOpenAConversationForTheSameConnectedUnit() {
        // MSG-02: an authorized unit member opens the thread for the verified
        // patient; the conversation starts awaiting the patient.
        PatientUnitConnection connection = connect();
        unitStore.addMember(ORG, STAFF_SUBJECT, "lab_collector", true);
        bind(STAFF_SUBJECT);
        when(repository.hasActiveMembership(STAFF_SUBJECT)).thenReturn(true);
        when(repository.activeRoles(STAFF_SUBJECT)).thenReturn(List.of("lab_collector"));

        Conversation opened = service.open(new OpenRequest(PATIENT, PATIENT_SUBJECT,
                UnitKey.LAB.wire(), connection.id(), null));

        assertEquals(State.AWAITING_PATIENT, opened.state());
        verify(repository).insertConversation(eq(opened.id()), eq(PATIENT), eq(PATIENT_SUBJECT),
                eq(UnitKey.LAB.wire()), eq(connection.id()), isNull(), eq(State.AWAITING_PATIENT.wire()));
    }

    @Test
    void wrongUnitIsDeniedAConversation() {
        // MSG-01 / MSG-07: a connection to the lab cannot be used to address
        // radiology, and the directory's own check denies the wrong unit too.
        PatientUnitConnection connection = connect();

        ApiException opened = assertThrows(ApiException.class, () -> service.open(new OpenRequest(
                PATIENT, PATIENT_SUBJECT, UnitKey.RADIOLOGY.wire(), connection.id(), null)));
        assertEquals(403, opened.status());
        assertEquals("FORBIDDEN", opened.code());

        ApiException used = assertThrows(ApiException.class,
                () -> directory.requireActiveConnection(PATIENT, RADIOLOGY_UNIT));
        assertEquals(403, used.status());
        assertEquals("FORBIDDEN", used.code());
        verify(repository, never()).insertConversation(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString());
    }

    @Test
    void revokedConnectionStopsMessaging() {
        // MSG-07: revoking the connection immediately blocks a patient reply on
        // the existing thread; no new message is published.
        PatientUnitConnection connection = connect();
        Conversation current = new Conversation(CONVERSATION, 1, PATIENT, PATIENT_SUBJECT,
                UnitKey.LAB.wire(), connection.id(), null, State.AWAITING_STAFF, null, 0, 0, NOW);
        when(repository.loadForUpdate(CONVERSATION)).thenReturn(Optional.of(current));

        directory.revoke(connection.id());

        ApiException denial = assertThrows(ApiException.class,
                () -> service.reply(CONVERSATION, new ReplyRequest("hello"), 1));
        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        verify(repository, never()).insertMessage(anyString(), anyInt(), anyString(), anyString(),
                anyString(), anyString());
    }

    private void bind(String subject) {
        TenantContext.set(new TenantContext(ORG, subject, "PURPOSE", "corr-g3-conversation"));
    }

    private PatientUnitConnection connect() {
        return directory.connect(new ConnectRequest(PATIENT, LAB_UNIT, "order", "order-1"));
    }
}
