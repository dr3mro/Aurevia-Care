package com.aurevia.acceptance.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.acknowledgment.InstructionFactActions;
import com.aurevia.messaging.acknowledgment.InstructionFactService;
import com.aurevia.messaging.acknowledgment.InstructionFacts.AcknowledgeCommand;
import com.aurevia.messaging.acknowledgment.InstructionFacts.PublishCommand;
import com.aurevia.messaging.acknowledgment.InMemoryInstructionFactStore;
import com.aurevia.messaging.attachments.AttachmentContract.Actor;
import com.aurevia.messaging.attachments.AttachmentContract.Attachment;
import com.aurevia.messaging.attachments.AttachmentContract.ConnectionGrant;
import com.aurevia.messaging.attachments.AttachmentContract.PublishRequest;
import com.aurevia.messaging.attachments.AttachmentContract.ScanRequest;
import com.aurevia.messaging.attachments.AttachmentContract.UploadRequest;
import com.aurevia.messaging.attachments.AttachmentService;
import com.aurevia.messaging.attachments.InMemoryAttachmentStore;
import com.aurevia.messaging.conversations.ConversationActions;
import com.aurevia.messaging.conversations.ConversationContract.Conversation;
import com.aurevia.messaging.conversations.ConversationContract.Inbox;
import com.aurevia.messaging.conversations.ConversationContract.InboxEntry;
import com.aurevia.messaging.conversations.ConversationContract.OpenRequest;
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

/**
 * Cross-cutting acceptance for a patient who contacts two care units.
 *
 * <p>Requirement PAT-02: patient-facing material is scoped to the verified
 * connection. Spec section 11 "Patient contacts two units": each unit gets its
 * own authorized thread, a PDF reaches only the lab unit, verified instructions
 * arrive from the radiology unit, neither unit can read or download the other's
 * material, and both threads appear in the patient inbox. All identifiers and
 * notes are synthetic.</p>
 */
class PatientTwoUnitsAcceptanceTest extends FirstSliceFixture {

    private static final String PATIENT_SUBJECT = "syn-patient-two-units";
    private static final String LAB_UNIT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String RAD_UNIT = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String LAB_CONNECTION = "connection-lab";
    private static final String RAD_CONNECTION = "connection-rad";
    private static final String LAB_MESSAGE = "message-lab";
    private static final String RAD_MESSAGE = "message-rad";
    private static final String INSTRUCTION = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String CHECKSUM = "a".repeat(64);
    private static final String DUE_AT = "2026-11-23T13:00:00Z";

    private final Actor patient = Actor.patient(CLINIC_A, PATIENT_SUBJECT, PATIENT);
    private final Actor labStaff = Actor.staff(CLINIC_A, "syn-lab-staff", LAB_UNIT, "lab_collector");
    private final Actor radStaff = Actor.staff(CLINIC_A, "syn-rad-staff", RAD_UNIT, "radiology_staff");
    private final Actor scanner = Actor.staff(CLINIC_A, "syn-scanner", LAB_UNIT, "security_scanner");

    private InMemoryMessagingStore unitStore;
    private CareUnitDirectoryService directory;
    private ConversationRepository conversations;
    private ConversationService service;
    private InMemoryAttachmentStore attachmentStore;
    private AttachmentService attachments;
    private InMemoryInstructionFactStore instructionStore;
    private InstructionFactService instructions;
    private String labConversationId;
    private String radConversationId;
    private UnitKey openingUnit;
    private final Map<String, Conversation> openedThreads = new ConcurrentHashMap<>();

    /** Build two connected units, their threads, the attachment and instruction services. */
    @BeforeEach
    void setUpTwoUnits() {
        unitStore = new InMemoryMessagingStore();
        directory = new CareUnitDirectoryService(unitStore, new MessagingActions(), clock());
        conversations = mock(ConversationRepository.class);
        service = new ConversationService(conversations, new ConversationActions(), transactions, audit,
                directory);

        attachmentStore = new InMemoryAttachmentStore();
        attachments = new AttachmentService(attachmentStore, clock());
        instructionStore = new InMemoryInstructionFactStore();
        instructions = new InstructionFactService(instructionStore, new InstructionFactActions(), clock());

        unitStore.addMember(CLINIC_A, PATIENT_SUBJECT, "patient", true);
        unitStore.addPatient(CLINIC_A, PATIENT);
        unitStore.linkPatient(CLINIC_A, PATIENT_SUBJECT, PATIENT);
        unitStore.addUnit(new CareUnit(LAB_UNIT, CLINIC_A, FACILITY, "Synthetic lab", "lab",
                List.of("results"), true));
        unitStore.addUnit(new CareUnit(RAD_UNIT, CLINIC_A, FACILITY, "Synthetic radiology", "radiology",
                List.of("imaging"), true));
        unitStore.addRelationship(new EligibleRelationship("rel-lab", CLINIC_A, PATIENT, LAB_UNIT,
                ConnectionContext.ORDER, "order-1", true, null, false));
        unitStore.addRelationship(new EligibleRelationship("rel-rad", CLINIC_A, PATIENT, RAD_UNIT,
                ConnectionContext.REFERRAL, "referral-1", true, null, false));
        instructionStore.addMember(CLINIC_A, "syn-rad-clinician", "clinician", true);
        instructionStore.assignStaffUnit(CLINIC_A, "syn-rad-clinician", RAD_UNIT);
        instructionStore.addMember(CLINIC_A, PATIENT_SUBJECT, "patient", true);
        instructionStore.linkPatient(CLINIC_A, PATIENT_SUBJECT, PATIENT);
        instructionStore.addConnection(CLINIC_A, PATIENT, RAD_UNIT, true);

        attachmentStore.putGrant(new ConnectionGrant(CLINIC_A, LAB_CONNECTION, LAB_UNIT, PATIENT, true));
        attachmentStore.putGrant(new ConnectionGrant(CLINIC_A, RAD_CONNECTION, RAD_UNIT, PATIENT, true));

        bind(CLINIC_A, PATIENT_SUBJECT);
        when(conversations.hasActiveMembership(PATIENT_SUBJECT)).thenReturn(true);
        when(conversations.activeRoles(PATIENT_SUBJECT)).thenReturn(List.of("patient"));
        when(conversations.patientExists(PATIENT)).thenReturn(true);
        when(conversations.patientLinkActive(PATIENT_SUBJECT, PATIENT)).thenReturn(true);
        doAnswer(invocation -> {
            String id = invocation.getArgument(0);
            openedThreads.put(id, conversation(id, openingUnit));
            return 1;
        }).when(conversations).insertConversation(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString());
        when(conversations.find(anyString())).thenAnswer(invocation -> Optional.ofNullable(
                openedThreads.get(invocation.getArgument(0))));
        labConversationId = open("lab", UnitKey.LAB, LAB_UNIT, "order", "order-1");
        radConversationId = open("radiology", UnitKey.RADIOLOGY, RAD_UNIT, "referral", "referral-1");
        when(conversations.inbox(PATIENT_SUBJECT)).thenReturn(List.of(
                new InboxEntry(conversation(labConversationId, UnitKey.LAB), 1),
                new InboxEntry(conversation(radConversationId, UnitKey.RADIOLOGY), 1)));
    }

    @Test
    void pdfReachesOnlyTheLabUnitAndBothThreadsAppearInThePatientInbox() {
        // PAT-02 / spec 11: a PDF shared on the lab thread is delivered only to
        // the lab unit; the separate radiology thread remains untouched; the
        // patient sees both threads in one inbox.
        bind(CLINIC_A, PATIENT_SUBJECT);
        Attachment uploaded = attachments.upload(patient, pdf(LAB_CONNECTION));
        Attachment clean = attachments.scan(scanner, uploaded.id(), new ScanRequest("clean", null), 1);
        attachments.publish(patient, new PublishRequest(List.of(clean.id()), LAB_MESSAGE, LAB_UNIT));

        assertEquals(1, attachments.inbox(labStaff, LAB_UNIT).size());
        assertTrue(attachments.inbox(radStaff, RAD_UNIT).isEmpty());
        assertEquals(LAB_UNIT, attachments.inbox(labStaff, LAB_UNIT).get(0).unitId());

        Inbox inbox = service.inbox();
        assertEquals(2, inbox.entries().size());
        assertTrue(inbox.entries().stream().anyMatch(entry -> entry.conversation().id().equals(labConversationId)));
        assertTrue(inbox.entries().stream().anyMatch(entry -> entry.conversation().id().equals(radConversationId)));
    }

    @Test
    void verifiedInstructionsComeFromTheOtherUnitAndCrossThreadAccessIsRefused() {
        // PAT-02 / spec 11: the radiology unit sends versioned instructions that
        // the patient acknowledges on the exact revision; the lab unit cannot
        // read the radiology thread or download its material, and a stale
        // acknowledgment is refused.
        bind(CLINIC_A, "syn-rad-clinician");
        instructions.publish(new PublishCommand(INSTRUCTION, PATIENT, RAD_UNIT, 1, "content-ref-1",
                true, DUE_AT, false, "publish-1"));
        instructions.publish(new PublishCommand(INSTRUCTION, PATIENT, RAD_UNIT, 2, "content-ref-2",
                true, DUE_AT, false, "publish-2"));

        bind(CLINIC_A, PATIENT_SUBJECT);
        ApiException stale = assertThrows(ApiException.class,
                () -> instructions.acknowledge(new AcknowledgeCommand(INSTRUCTION, 1, "ack-stale")));
        assertEquals(409, stale.status());
        assertEquals("STALE_REVISION", stale.code());
        assertEquals(2, instructions.acknowledge(new AcknowledgeCommand(INSTRUCTION, 2, "ack-current")).revision());

        bind(CLINIC_A, "syn-lab-staff");
        ApiException wrongThread = assertThrows(ApiException.class,
                () -> service.get(radConversationId));
        assertEquals(403, wrongThread.status());
        assertEquals("FORBIDDEN", wrongThread.code());

        bind(CLINIC_A, PATIENT_SUBJECT);
        Attachment uploaded = attachments.upload(patient, pdf(LAB_CONNECTION));
        Attachment clean = attachments.scan(scanner, uploaded.id(), new ScanRequest("clean", null), 1);
        attachments.publish(patient, new PublishRequest(List.of(clean.id()), LAB_MESSAGE, LAB_UNIT));
        bind(CLINIC_A, "syn-rad-staff");
        ApiException foreignView = assertThrows(ApiException.class,
                () -> attachments.view(radStaff, clean.id()));
        assertEquals(404, foreignView.status());
        assertEquals("NOT_FOUND", foreignView.code());
        ApiException foreignDownload = assertThrows(ApiException.class,
                () -> attachments.inbox(radStaff, LAB_UNIT));
        assertEquals(403, foreignDownload.status());
        assertEquals("WRONG_UNIT", foreignDownload.code());
    }

    private String open(String topic, UnitKey unit, String unitId, String context, String reference) {
        String connectionId = directory.connect(new ConnectRequest(PATIENT, unitId, context, reference)).id();
        openingUnit = unit;
        Conversation opened = service.open(new OpenRequest(PATIENT, PATIENT_SUBJECT, unit.wire(), connectionId, topic));
        assertEquals(State.AWAITING_STAFF, opened.state());
        return opened.id();
    }

    private static Conversation conversation(String id, UnitKey unit) {
        String connectionId = unit == UnitKey.LAB ? LAB_CONNECTION : RAD_CONNECTION;
        return new Conversation(id, 1, PATIENT, PATIENT_SUBJECT, unit.wire(), connectionId, null,
                State.AWAITING_STAFF, null, 0, 0, NOW);
    }

    private UploadRequest pdf(String connectionId) {
        return new UploadRequest(connectionId, "notes.pdf", "application/pdf", "25504446", 128, CHECKSUM);
    }
}
