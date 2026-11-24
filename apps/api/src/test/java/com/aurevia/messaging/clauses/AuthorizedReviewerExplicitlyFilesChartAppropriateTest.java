package com.aurevia.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.attachments.AttachmentContract.Actor;
import com.aurevia.messaging.attachments.AttachmentContract.Attachment;
import com.aurevia.messaging.attachments.AttachmentContract.AttachmentEvent;
import com.aurevia.messaging.attachments.AttachmentContract.AttachmentState;
import com.aurevia.messaging.attachments.AttachmentContract.ConnectionGrant;
import com.aurevia.messaging.attachments.AttachmentContract.EventType;
import com.aurevia.messaging.attachments.AttachmentContract.FileRequest;
import com.aurevia.messaging.attachments.AttachmentContract.PublishRequest;
import com.aurevia.messaging.attachments.AttachmentContract.ScanRequest;
import com.aurevia.messaging.attachments.AttachmentContract.UploadRequest;
import com.aurevia.messaging.attachments.AttachmentService;
import com.aurevia.messaging.attachments.AttachmentStore;
import com.aurevia.messaging.attachments.InMemoryAttachmentStore;

/**
 * MSG-03 clause 9 (API evidence): an authorized reviewer explicitly files
 * published patient-supplied material in the chart if appropriate.
 *
 * <p>The rule is implemented by the domain operation
 * {@code AttachmentService.fileIntoChart}; there is no HTTP filing route, so this
 * class tests that operation directly rather than claiming an HTTP-level proof.
 * Only a clinician assigned to the receiving unit may file an attachment that
 * has passed quarantine and explicit publication. Every refusal is asserted by
 * status and stable code, and the happy path asserts that the sole persisted
 * effect is an appended {@code FILED_TO_CHART} fact while the attachment
 * projection is left untouched.</p>
 *
 * <p>All identifiers, subjects and payload metadata are synthetic. No real
 * patient data, credential or token is used.</p>
 */
class AuthorizedReviewerExplicitlyFilesChartAppropriateTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String UNIT = "unit-1";
    private static final String OTHER_UNIT = "unit-2";
    private static final String PATIENT = "pat-001";
    private static final String CONNECTION = "conv-1";
    private static final String MESSAGE = "msg-1";
    private static final String CHECKSUM = "a".repeat(64);
    private static final String UNKNOWN_ID = "00000000-0000-0000-0000-000000000000";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private final Actor patientA = Actor.patient(ORG_A, "patient-a", PATIENT);
    private final Actor scannerA = Actor.staff(ORG_A, "scanner-a", UNIT, "security_scanner");
    private final Actor clinicianA = Actor.staff(ORG_A, "clinician-a", UNIT, "clinician");
    private final Actor wrongUnitClinicianA = Actor.staff(ORG_A, "clinician-b", OTHER_UNIT, "clinician");
    private final Actor unitlessClinicianA = Actor.staff(ORG_A, "clinician-c", null, "clinician");
    private final Actor receptionistA = Actor.staff(ORG_A, "reception-a", UNIT, "reception");
    private final Actor clinicianB = Actor.staff(ORG_B, "clinician-b-org", UNIT, "clinician");

    private AttachmentStore backing;
    private WriteCountingAttachmentStore store;
    private AttachmentService service;

    @BeforeEach
    void setUp() {
        backing = new InMemoryAttachmentStore();
        store = new WriteCountingAttachmentStore(backing);
        service = new AttachmentService(store, Clock.fixed(NOW, ZoneOffset.UTC));
        backing.putGrant(new ConnectionGrant(ORG_A, CONNECTION, UNIT, PATIENT, true));
    }

    @Test
    void clinicianFilesPublishedMaterialAndAppendsFiledFact() {
        Attachment published = publishedPatientFile();
        List<AttachmentEvent> before = service.events(patientA, published.id());
        int writesBefore = store.writes();

        Attachment filed = service.fileIntoChart(clinicianA, published.id(),
                new FileRequest("enc-1", "reviewed"), published.version());

        assertSame(published, filed);
        assertEquals(3, filed.version());
        assertEquals(AttachmentState.PUBLISHED, filed.state());
        assertEquals(writesBefore, store.writes(), "filing must not rewrite the attachment projection");
        Attachment stored = backing.attachment(ORG_A, published.id()).orElseThrow();
        assertSame(published, stored);
        assertEquals(MESSAGE, stored.messageId());
        assertEquals(UNIT, stored.recipientUnitId());

        List<AttachmentEvent> after = service.events(patientA, published.id());
        assertEquals(before.size() + 1, after.size());
        assertEquals(before, after.subList(0, before.size()), "facts are append-only");
        AttachmentEvent fact = after.get(after.size() - 1);
        assertEquals(EventType.FILED_TO_CHART, fact.eventType());
        assertEquals("clinician-a", fact.actorSubject());
        assertEquals("success", fact.outcome());
        assertNull(fact.detail());
        assertEquals(published.id(), fact.attachmentId());
        assertEquals(ORG_A, fact.organizationId());
        assertEquals(NOW, fact.occurredAt());
        assertTrue(after.stream().anyMatch(event -> event.eventType() == EventType.PUBLISHED));
        assertNotEquals(before, after);
    }

    @Test
    void receptionStaffCannotFileIntoTheChart() {
        Attachment published = publishedPatientFile();
        assertDenied(403, "FORBIDDEN", () -> service.fileIntoChart(receptionistA, published.id(),
                new FileRequest("enc-1", null), published.version()));
    }

    @Test
    void clinicianOfAnotherUnitCannotFileIntoTheChart() {
        Attachment published = publishedPatientFile();
        assertDenied(403, "WRONG_UNIT", () -> service.fileIntoChart(wrongUnitClinicianA, published.id(),
                new FileRequest("enc-1", null), published.version()));
    }

    @Test
    void clinicianWithoutAUnitCannotFileIntoTheChart() {
        Attachment published = publishedPatientFile();
        assertDenied(403, "WRONG_UNIT", () -> service.fileIntoChart(unitlessClinicianA, published.id(),
                new FileRequest("enc-1", null), published.version()));
    }

    @Test
    void clinicianOfAnotherTenantCannotFileIntoTheChart() {
        Attachment published = publishedPatientFile();
        assertDenied(404, "NOT_FOUND", () -> service.fileIntoChart(clinicianB, published.id(),
                new FileRequest("enc-1", null), published.version()));
    }

    @Test
    void staleVersionCannotFileIntoTheChart() {
        Attachment published = publishedPatientFile();
        assertDenied(409, "STALE_VERSION", () -> service.fileIntoChart(clinicianA, published.id(),
                new FileRequest("enc-1", null), published.version() + 1));
    }

    @Test
    void unpublishedDraftCannotBeFiledIntoTheChart() {
        Attachment quarantined = service.upload(patientA, pdf());
        assertDenied(409, "NOT_PUBLISHED", () -> service.fileIntoChart(clinicianA, quarantined.id(),
                new FileRequest("enc-1", null), quarantined.version()));
        Attachment clean = service.scan(scannerA, quarantined.id(),
                new ScanRequest("clean", null), quarantined.version());
        assertEquals(AttachmentState.CLEAN, clean.state());
        assertDenied(409, "NOT_PUBLISHED", () -> service.fileIntoChart(clinicianA, clean.id(),
                new FileRequest("enc-1", null), clean.version()));
    }

    @Test
    void invalidEncounterIdCannotFileIntoTheChart() {
        Attachment published = publishedPatientFile();
        assertDenied(422, "INVALID_ENCOUNTER_ID", () -> service.fileIntoChart(clinicianA, published.id(),
                new FileRequest("!", null), published.version()));
        assertDenied(422, "INVALID_DETAIL", () -> service.fileIntoChart(clinicianA, published.id(),
                new FileRequest("enc-1", "x".repeat(201)), published.version()));
    }

    @Test
    void unknownAttachmentCannotBeFiled() {
        assertDenied(404, "NOT_FOUND", () -> service.fileIntoChart(clinicianA, UNKNOWN_ID,
                new FileRequest("enc-1", null), 3));
    }

    /** Upload, scan clean and explicitly publish one synthetic patient file. */
    private Attachment publishedPatientFile() {
        Attachment uploaded = service.upload(patientA, pdf());
        Attachment clean = service.scan(scannerA, uploaded.id(),
                new ScanRequest("clean", null), uploaded.version());
        return service.publish(patientA,
                new PublishRequest(List.of(clean.id()), MESSAGE, UNIT)).get(0);
    }

    private static UploadRequest pdf() {
        return new UploadRequest(CONNECTION, "notes.pdf", "application/pdf", "25504446", 128, CHECKSUM);
    }

    private static void assertDenied(int status, String code, Runnable action) {
        ApiException failure = assertThrows(ApiException.class, action::run);
        assertEquals(status, failure.status());
        assertEquals(code, failure.code());
    }

    /**
     * Tenant-scoped attachment store that counts projection writes without
     * changing behaviour. Filing must append a fact only, so an unchanged count
     * proves the attachment projection was not rewritten. A chart write path, if
     * one is added later, needs its own persisted-effect assertion.
     */
    private static final class WriteCountingAttachmentStore implements AttachmentStore {

        private final AttachmentStore delegate;
        private int writes;

        WriteCountingAttachmentStore(AttachmentStore delegate) {
            this.delegate = delegate;
        }

        int writes() {
            return writes;
        }

        @Override
        public Optional<ConnectionGrant> grant(String organizationId, String connectionId) {
            return delegate.grant(organizationId, connectionId);
        }

        @Override
        public void putGrant(ConnectionGrant grant) {
            delegate.putGrant(grant);
        }

        @Override
        public Optional<Attachment> attachment(String organizationId, String id) {
            return delegate.attachment(organizationId, id);
        }

        @Override
        public void putAttachment(Attachment attachment) {
            writes++;
            delegate.putAttachment(attachment);
        }

        @Override
        public List<Attachment> attachments(String organizationId) {
            return delegate.attachments(organizationId);
        }

        @Override
        public void appendEvent(AttachmentEvent event) {
            delegate.appendEvent(event);
        }

        @Override
        public List<AttachmentEvent> events(String organizationId) {
            return delegate.events(organizationId);
        }
    }
}
