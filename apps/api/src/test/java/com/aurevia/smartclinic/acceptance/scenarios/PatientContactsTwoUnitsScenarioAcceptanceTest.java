package com.aurevia.smartclinic.acceptance.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.messaging.acknowledgment.InstructionFactActions;
import com.aurevia.smartclinic.messaging.acknowledgment.InstructionFactService;
import com.aurevia.smartclinic.messaging.acknowledgment.InstructionFacts.Acknowledgment;
import com.aurevia.smartclinic.messaging.acknowledgment.InstructionFacts.AcknowledgeCommand;
import com.aurevia.smartclinic.messaging.acknowledgment.InstructionFacts.PublishCommand;
import com.aurevia.smartclinic.messaging.acknowledgment.InMemoryInstructionFactStore;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.Actor;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.Attachment;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.ConnectionGrant;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.PublishRequest;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.ScanRequest;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.UploadRequest;
import com.aurevia.smartclinic.messaging.attachments.AttachmentService;
import com.aurevia.smartclinic.messaging.attachments.InMemoryAttachmentStore;
import com.aurevia.smartclinic.messaging.conversations.ConversationActions;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.OpenRequest;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.UnitKey;
import com.aurevia.smartclinic.messaging.conversations.ConversationRepository;
import com.aurevia.smartclinic.messaging.conversations.ConversationService;
import com.aurevia.smartclinic.messaging.units.CareUnitDirectoryService;
import com.aurevia.smartclinic.messaging.units.InMemoryMessagingStore;
import com.aurevia.smartclinic.messaging.units.MessagingActions;
import com.aurevia.smartclinic.messaging.units.MessagingContract.CareUnit;
import com.aurevia.smartclinic.messaging.units.MessagingContract.ConnectRequest;
import com.aurevia.smartclinic.messaging.units.MessagingContract.ConnectionContext;
import com.aurevia.smartclinic.messaging.units.MessagingContract.EligibleRelationship;
import com.aurevia.smartclinic.messaging.units.MessagingContract.PatientUnitConnection;

import static com.aurevia.smartclinic.acceptance.scenarios.ScenarioSupport.CLINIC_ALPHA;
import static com.aurevia.smartclinic.acceptance.scenarios.ScenarioSupport.CLOCK;
import static com.aurevia.smartclinic.acceptance.scenarios.ScenarioSupport.PATIENT;
import static com.aurevia.smartclinic.acceptance.scenarios.ScenarioSupport.PATIENT_SUBJECT;
import static com.aurevia.smartclinic.acceptance.scenarios.ScenarioSupport.assertDenied;
import static com.aurevia.smartclinic.acceptance.scenarios.ScenarioSupport.bind;
import static com.aurevia.smartclinic.acceptance.scenarios.ScenarioSupport.clear;

/**
 * Section 11 cross-cutting scenario: patient contacts two units (MSG-01, MSG-03, MSG-04).
 *
 * <p>The patient holds two independently verified unit connections at one
 * synthetic clinic. They open a separate thread with each unit, share a PDF
 * only with the laboratory, and receive a versioned instruction from
 * radiology. Neither unit can read the other's thread or attachment. All
 * participants and documents are synthetic.</p>
 */
@SuppressWarnings("unchecked")
class PatientContactsTwoUnitsScenarioAcceptanceTest {

    private static final String LAB_UNIT = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String RAD_UNIT = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String LAB_STAFF = "syn-lab-staff-two-units";
    private static final String RAD_STAFF = "syn-rad-staff-two-units";
    private static final String SCANNER = "syn-security-scanner";
    private static final String INSTRUCTION = "99999999-9999-9999-9999-999999999999";
    private static final String MESSAGE = "message-two-units";
    private static final String CHECKSUM = "a".repeat(64);

    private InMemoryMessagingStore unitStore;
    private CareUnitDirectoryService directory;
    private ConversationRepository conversations;
    private ConversationService conversationService;
    private InMemoryAttachmentStore attachmentStore;
    private AttachmentService attachmentService;
    private InMemoryInstructionFactStore instructionStore;
    private InstructionFactService instructionService;

    private PatientUnitConnection labConnection;
    private PatientUnitConnection radConnection;

    @BeforeEach
    void setUp() {
        bind(CLINIC_ALPHA, PATIENT_SUBJECT);
        unitStore = new InMemoryMessagingStore();
        directory = new CareUnitDirectoryService(unitStore, new MessagingActions(), CLOCK);
        unitStore.addMember(CLINIC_ALPHA, PATIENT_SUBJECT, "patient", true);
        unitStore.addPatient(CLINIC_ALPHA, PATIENT);
        unitStore.linkPatient(CLINIC_ALPHA, PATIENT_SUBJECT, PATIENT);
        unitStore.addUnit(new CareUnit(LAB_UNIT, CLINIC_ALPHA, "facility-1", "Synthetic Lab", "lab",
                List.of("results"), true));
        unitStore.addUnit(new CareUnit(RAD_UNIT, CLINIC_ALPHA, "facility-1", "Synthetic Radiology",
                "radiology", List.of("imaging"), true));
        unitStore.addRelationship(new EligibleRelationship("rel-lab", CLINIC_ALPHA, PATIENT, LAB_UNIT,
                ConnectionContext.ORDER, "order-lab", true, null, false));
        unitStore.addRelationship(new EligibleRelationship("rel-rad", CLINIC_ALPHA, PATIENT, RAD_UNIT,
                ConnectionContext.ORDER, "order-rad", true, null, false));
        labConnection = directory.connect(new ConnectRequest(PATIENT, LAB_UNIT, "order", "order-lab"));
        radConnection = directory.connect(new ConnectRequest(PATIENT, RAD_UNIT, "order", "order-rad"));

        conversations = mock(ConversationRepository.class);
        conversationService = new ConversationService(conversations, new ConversationActions(),
                transactionTemplate(), mock(com.aurevia.smartclinic.audit.AuditAppender.class), directory);
        when(conversations.hasActiveMembership(anyString())).thenReturn(true);
        when(conversations.activeRoles(anyString())).thenReturn(List.of("patient"));
        when(conversations.patientExists(anyString())).thenReturn(true);
        when(conversations.patientLinkActive(anyString(), anyString())).thenReturn(true);
        when(conversations.find(anyString())).thenAnswer(invocation -> java.util.Optional.of(
                new com.aurevia.smartclinic.messaging.conversations.ConversationContract.Conversation(
                        invocation.getArgument(0), 1, PATIENT, PATIENT_SUBJECT, UnitKey.LAB.wire(),
                        labConnection.id(), null,
                        com.aurevia.smartclinic.messaging.conversations.ConversationContract.State.AWAITING_STAFF,
                        null, 0, 0, com.aurevia.smartclinic.acceptance.scenarios.ScenarioSupport.NOW)));

        attachmentStore = new InMemoryAttachmentStore();
        attachmentService = new AttachmentService(attachmentStore, CLOCK);
        attachmentStore.putGrant(new ConnectionGrant(CLINIC_ALPHA, labConnection.id(), LAB_UNIT, PATIENT, true));
        attachmentStore.putGrant(new ConnectionGrant(CLINIC_ALPHA, radConnection.id(), RAD_UNIT, PATIENT, true));

        instructionStore = new InMemoryInstructionFactStore();
        instructionService = new InstructionFactService(instructionStore, new InstructionFactActions(), CLOCK);
        instructionStore.addMember(CLINIC_ALPHA, RAD_STAFF, "clinician", true);
        instructionStore.assignStaffUnit(CLINIC_ALPHA, RAD_STAFF, RAD_UNIT);
        instructionStore.addMember(CLINIC_ALPHA, PATIENT_SUBJECT, "patient", true);
        instructionStore.linkPatient(CLINIC_ALPHA, PATIENT_SUBJECT, PATIENT);
        instructionStore.addConnection(CLINIC_ALPHA, PATIENT, RAD_UNIT, true);
    }

    @AfterEach
    void tearDown() {
        clear();
    }

    @Test
    void patientOpensTwoThreadsAndSharesAPdfWithOnlyOneUnit() {
        // MSG-01 / MSG-02: each contact uses its own verified connection; a
        // connection to one unit cannot be used to address the other.
        bind(CLINIC_ALPHA, PATIENT_SUBJECT);
        conversationService.open(new OpenRequest(PATIENT, PATIENT_SUBJECT,
                UnitKey.LAB.wire(), labConnection.id(), "results"));
        conversationService.open(new OpenRequest(PATIENT, PATIENT_SUBJECT,
                UnitKey.RADIOLOGY.wire(), radConnection.id(), "imaging"));
        // Each thread is persisted with its own verified connection and unit.
        verify(conversations).insertConversation(anyString(), eq(PATIENT), eq(PATIENT_SUBJECT),
                eq(UnitKey.LAB.wire()), eq(labConnection.id()), any(), any());
        verify(conversations).insertConversation(anyString(), eq(PATIENT), eq(PATIENT_SUBJECT),
                eq(UnitKey.RADIOLOGY.wire()), eq(radConnection.id()), any(), any());
        assertDenied(403, "FORBIDDEN", () -> conversationService.open(new OpenRequest(PATIENT,
                PATIENT_SUBJECT, UnitKey.RADIOLOGY.wire(), labConnection.id(), null)));

        // MSG-03: the PDF is published to the lab inbox only; radiology staff
        // cannot view or list it.
        Actor patient = Actor.patient(CLINIC_ALPHA, PATIENT_SUBJECT, PATIENT);
        Actor labStaff = Actor.staff(CLINIC_ALPHA, LAB_STAFF, LAB_UNIT, "lab_collector");
        Actor radStaff = Actor.staff(CLINIC_ALPHA, RAD_STAFF, RAD_UNIT, "radiology_staff");
        Actor scanner = Actor.staff(CLINIC_ALPHA, SCANNER, LAB_UNIT, "security_scanner");
        Attachment uploaded = attachmentService.upload(patient, new UploadRequest(labConnection.id(),
                "shared-scan.pdf", "application/pdf", "25504446", 128, CHECKSUM));
        Attachment clean = attachmentService.scan(scanner, uploaded.id(), new ScanRequest("clean", null), 1);
        attachmentService.publish(patient, new PublishRequest(List.of(clean.id()), MESSAGE, LAB_UNIT));

        assertEquals(1, attachmentService.inbox(labStaff, LAB_UNIT).size());
        assertTrue(attachmentService.inbox(radStaff, RAD_UNIT).isEmpty());
        assertDenied(404, "NOT_FOUND", () -> attachmentService.view(radStaff, uploaded.id()));
    }

    @Test
    void otherUnitSendsVerifiedInstructionsBoundToItsConnection() {
        // MSG-04 / MSG-05: radiology publishes a versioned instruction over its
        // own connection and the patient acknowledges the exact revision.
        bind(CLINIC_ALPHA, RAD_STAFF);
        instructionService.publish(new PublishCommand(INSTRUCTION, PATIENT, RAD_UNIT, 1,
                "content-ref-1", true, "2026-11-22T13:00:00Z", false, "publish-two-units"));
        bind(CLINIC_ALPHA, PATIENT_SUBJECT);
        Acknowledgment acknowledgment = instructionService.acknowledge(
                new AcknowledgeCommand(INSTRUCTION, 1, "ack-two-units"));

        assertEquals(1, acknowledgment.revision());
        assertEquals(PATIENT_SUBJECT, acknowledgment.actor());
        assertTrue(instructionStore.acknowledgments(CLINIC_ALPHA, INSTRUCTION, 1).contains(acknowledgment));
    }

    /** A transaction template that runs its callback inline for the mocked repository. */
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate() {
        org.springframework.transaction.support.TransactionTemplate template =
                mock(org.springframework.transaction.support.TransactionTemplate.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback =
                    invocation.getArgument(0);
            return callback.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
        }).when(template).execute(any());
        return template;
    }
}
