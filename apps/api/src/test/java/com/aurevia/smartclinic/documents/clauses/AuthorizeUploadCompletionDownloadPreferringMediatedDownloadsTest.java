package com.aurevia.smartclinic.documents.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.documents.DocumentArtifactController;
import com.aurevia.smartclinic.documents.DocumentContract.Artifact;
import com.aurevia.smartclinic.documents.DocumentContract.ArtifactState;
import com.aurevia.smartclinic.documents.DocumentContract.CompleteRequest;
import com.aurevia.smartclinic.documents.DocumentContract.DocumentEvent;
import com.aurevia.smartclinic.documents.DocumentContract.EventType;
import com.aurevia.smartclinic.documents.DocumentContract.MediatedDownload;
import com.aurevia.smartclinic.documents.DocumentContract.MediaType;
import com.aurevia.smartclinic.documents.DocumentContract.ScanVerdict;
import com.aurevia.smartclinic.documents.DocumentGuard;
import com.aurevia.smartclinic.documents.DocumentStore;
import com.aurevia.smartclinic.documents.PdfBindingService;
import com.aurevia.smartclinic.documents.QuarantineService;
import com.aurevia.smartclinic.staff.StaffRepository;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * ARC-06 clause 3 (API test): upload completion and every download are authorized
 * by current role and tenant, and a clinical download returns only a mediated,
 * metadata-only handle. Every case enters through the real
 * {@link com.aurevia.smartclinic.documents.DocumentArtifactController} command
 * methods, the same entry points the HTTP routes call. The persistence boundary
 * is a Mockito mock while the guard and quarantine service are real, so a denial
 * is proven to leave the artifact and its success ledger untouched. All
 * identifiers, subjects and payload metadata are synthetic.
 */
class AuthorizeUploadCompletionDownloadPreferringMediatedDownloadsTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String REVIEWER = "reviewer-c03";
    private static final String RENDERER = "renderer-c03";
    private static final String CLINICIAN = "clinician-c03";
    private static final String PATIENT_ROLE = "patient-role-c03";
    private static final String UPLOADER = "uploader-c03";
    private static final String ID = "33333333-3333-3333-3333-333333333333";
    private static final String CONTEXT = "ctx-c03";
    private static final String PATIENT = "pat-c03";
    private static final String FILE_NAME = "notes.pdf";
    private static final String CHECKSUM = "a".repeat(64);
    private static final String OBJECT_KEY = "objects/" + ORG_A + "/" + ID + "/v1";
    private static final long SIZE_BYTES = 128L;
    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");

    private DocumentStore store;
    private StaffRepository staff;
    private DocumentArtifactController controller;
    private ArgumentCaptor<DocumentEvent> facts;

    /** Build the real controller over a mocked persistence boundary and clock. */
    @BeforeEach
    void setUp() {
        store = mock(DocumentStore.class);
        staff = mock(StaffRepository.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        DocumentGuard guard = new DocumentGuard(store, clock);
        QuarantineService quarantine = new QuarantineService(store, guard, clock);
        // The PDF service is final; build the real collaborator over the same mocked boundary
        // so the controller is constructed exactly as in production.
        PdfBindingService pdf = new PdfBindingService(store, guard, clock);
        controller = new DocumentArtifactController(quarantine, pdf, staff, transactions());
        roles(REVIEWER, "document_reviewer");
        useTenant(ORG_A, REVIEWER);
        facts = ArgumentCaptor.forClass(DocumentEvent.class);
    }

    /** Remove the synthetic tenant context so other tests are unaffected. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Transaction template that runs the guarded callback synchronously, as production does. */
    private TransactionTemplate transactions() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(template).execute(any());
        return template;
    }

    /** Bind the caller's organization and subject, mirroring the authentication layer. */
    private void useTenant(String organizationId, String subject) {
        TenantContext.set(new TenantContext(organizationId, subject, "DOCUMENT_ARTIFACTS", "corr-c03"));
    }

    /** Grant the synthetic subject the given current active roles in this tenant. */
    private void roles(String subject, String... roleKeys) {
        when(staff.activeRoles(subject)).thenReturn(List.of(roleKeys));
    }

    /** Synthetic artifact whose state, verdict and immutable object version stay coherent. */
    private static Artifact artifact(ArtifactState state, ScanVerdict verdict, int version) {
        return new Artifact(ORG_A, ID, version, UPLOADER, CONTEXT, PATIENT, FILE_NAME,
                MediaType.PDF, SIZE_BYTES, CHECKSUM, OBJECT_KEY, 1, verdict, state, NOW);
    }

    /** Make one synthetic artifact readable in its owning tenant. */
    private void givenArtifact(Artifact artifact) {
        when(store.artifact(ORG_A, ID)).thenReturn(Optional.of(artifact));
    }

    /** Allow completion to commit its compare-and-set when the caller read the current version. */
    private void givenCommittedReplace(int expectedVersion) {
        when(store.replaceArtifactIfVersion(eq(ORG_A), eq(ID), eq(expectedVersion),
                any(Artifact.class))).thenReturn(true);
    }

    /** Capture the ApiException raised by a refused command. */
    private static ApiException reject(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    /** Assert the machine-readable status and code returned to the caller. */
    private static void assertFailure(ApiException failure, int status, String code) {
        assertEquals(status, failure.status());
        assertEquals(code, failure.code());
    }

    /** Read the single immutable fact a command recorded, failing if it recorded none or several. */
    private DocumentEvent onlyFact() {
        verify(store).appendEvent(facts.capture());
        assertEquals(1, facts.getAllValues().size());
        return facts.getValue();
    }

    /** Assert the one denial/conflict fact carries no payload, name or sensitive detail. */
    private void assertDenialFact(String outcome, String code, String subject) {
        DocumentEvent fact = onlyFact();
        assertEquals(EventType.ACCESS_DENIED, fact.eventType());
        assertEquals(outcome, fact.outcome());
        assertEquals(code, fact.detail());
        assertEquals(ORG_A, fact.organizationId());
        assertEquals(ID, fact.resourceId());
        assertEquals(subject, fact.actorSubject());
        assertEquals(NOW, fact.occurredAt());
        assertNotNull(fact.id());
    }

    /** Assert no command changed the artifact through its versioned compare-and-set. */
    private void assertArtifactUnchanged() {
        verify(store, never()).replaceArtifactIfVersion(anyString(), anyString(), anyInt(), any());
    }

    /** Assert no command reached the verified private object. */
    private void assertObjectNotRead() {
        verify(store, never()).verifyObject(anyString(), anyString(), anyInt(), anyString());
    }

    /**
     * A reviewer may complete a clean artifact only after its private object verifies;
     * the artifact advances one version and one completion fact is appended.
     */
    @Test
    void completeAcceptsACleanVerifiedArtifactForAReviewer() {
        Artifact clean = artifact(ArtifactState.CLEAN, ScanVerdict.CLEAN, 2);
        givenArtifact(clean);
        when(store.verifyObject(ORG_A, OBJECT_KEY, 1, CHECKSUM)).thenReturn(true);
        givenCommittedReplace(2);

        Artifact completed = controller.complete(ID, new CompleteRequest("verified"), "2");

        assertEquals(ArtifactState.COMPLETED, completed.state());
        assertEquals(3, completed.version());
        assertEquals(clean.checksum(), completed.checksum());
        assertEquals(clean.objectKey(), completed.objectKey());
        assertEquals(clean.objectVersion(), completed.objectVersion());
        assertEquals(clean.patientId(), completed.patientId());
        assertEquals(clean.createdAt(), completed.createdAt());
        verify(store).artifact(ORG_A, ID);
        verify(store).verifyObject(ORG_A, OBJECT_KEY, 1, CHECKSUM);
        ArgumentCaptor<Artifact> persisted = ArgumentCaptor.forClass(Artifact.class);
        verify(store).replaceArtifactIfVersion(eq(ORG_A), eq(ID), eq(2), persisted.capture());
        assertEquals(ArtifactState.COMPLETED, persisted.getValue().state());
        assertEquals(3, persisted.getValue().version());
        DocumentEvent fact = onlyFact();
        assertEquals(EventType.UPLOAD_COMPLETED, fact.eventType());
        assertEquals("success", fact.outcome());
        assertEquals("verified", fact.detail());
        assertEquals(ID, fact.resourceId());
        assertEquals(REVIEWER, fact.actorSubject());
    }

    /**
     * A role that may render cannot complete an upload: the guard denies before the
     * artifact is read even though the object would have verified.
     */
    @Test
    void completeIsDeniedForARoleWithoutCompletionPermission() {
        roles(RENDERER, "pdf_renderer");
        useTenant(ORG_A, RENDERER);
        when(store.verifyObject(ORG_A, OBJECT_KEY, 1, CHECKSUM)).thenReturn(true);

        ApiException failure = reject(() -> controller.complete(ID, new CompleteRequest("verified"), "2"));

        assertFailure(failure, 403, "FORBIDDEN");
        verify(store, never()).artifact(anyString(), anyString());
        assertObjectNotRead();
        assertArtifactUnchanged();
        assertDenialFact("denied", "FORBIDDEN", RENDERER);
    }

    /** A stale If-Match is refused before the object is read or the artifact is written. */
    @Test
    void completeRejectsAStaleVersion() {
        givenArtifact(artifact(ArtifactState.CLEAN, ScanVerdict.CLEAN, 3));
        when(store.verifyObject(ORG_A, OBJECT_KEY, 1, CHECKSUM)).thenReturn(true);

        ApiException failure = reject(() -> controller.complete(ID, new CompleteRequest("verified"), "2"));

        assertFailure(failure, 412, "STALE_VERSION");
        assertObjectNotRead();
        assertArtifactUnchanged();
        assertDenialFact("conflict", "STALE_VERSION", REVIEWER);
    }

    /** An artifact that is not clean cannot be completed, whatever its role holder sends. */
    @Test
    void completeRefusesAnArtifactThatIsNotClean() {
        givenArtifact(artifact(ArtifactState.QUARANTINED, ScanVerdict.PENDING, 2));
        when(store.verifyObject(ORG_A, OBJECT_KEY, 1, CHECKSUM)).thenReturn(true);

        ApiException failure = reject(() -> controller.complete(ID, new CompleteRequest("verified"), "2"));

        assertFailure(failure, 409, "NOT_CLEAN");
        assertObjectNotRead();
        assertArtifactUnchanged();
        assertDenialFact("conflict", "NOT_CLEAN", REVIEWER);
    }

    /** Completion is refused when the immutable private object version or checksum does not verify. */
    @Test
    void completeRefusesAnUnverifiedPrivateObject() {
        givenArtifact(artifact(ArtifactState.CLEAN, ScanVerdict.CLEAN, 2));

        ApiException failure = reject(() -> controller.complete(ID, new CompleteRequest("verified"), "2"));

        assertFailure(failure, 409, "OBJECT_UNVERIFIED");
        verify(store).verifyObject(ORG_A, OBJECT_KEY, 1, CHECKSUM);
        assertArtifactUnchanged();
        assertDenialFact("conflict", "OBJECT_UNVERIFIED", REVIEWER);
    }

    /** A competing completion that loses the compare-and-set is refused, with no success fact. */
    @Test
    void completeRejectsALostCompareAndSet() {
        givenArtifact(artifact(ArtifactState.CLEAN, ScanVerdict.CLEAN, 2));
        when(store.verifyObject(ORG_A, OBJECT_KEY, 1, CHECKSUM)).thenReturn(true);
        when(store.replaceArtifactIfVersion(eq(ORG_A), eq(ID), eq(2), any(Artifact.class)))
                .thenReturn(false);

        ApiException failure = reject(() -> controller.complete(ID, new CompleteRequest("verified"), "2"));

        assertFailure(failure, 412, "STALE_VERSION");
        verify(store).verifyObject(ORG_A, OBJECT_KEY, 1, CHECKSUM);
        verify(store).replaceArtifactIfVersion(eq(ORG_A), eq(ID), eq(2), any(Artifact.class));
        assertDenialFact("conflict", "STALE_VERSION", REVIEWER);
    }

    /**
     * An authorized reviewer receives a mediated handle containing only object metadata:
     * no payload, original file name, URL or other field can leak to the caller.
     */
    @Test
    void downloadReturnsAMetadataOnlyMediatedHandleForAnAuthorizedReviewer() {
        givenArtifact(artifact(ArtifactState.COMPLETED, ScanVerdict.CLEAN, 3));
        when(store.verifyObject(ORG_A, OBJECT_KEY, 1, CHECKSUM)).thenReturn(true);

        MediatedDownload handle = controller.download(ID, "3");

        assertEquals(new MediatedDownload(OBJECT_KEY, 1, CHECKSUM, "application/pdf", SIZE_BYTES), handle);
        assertEquals(Set.of("objectKey", "objectVersion", "checksum", "contentType", "sizeBytes"),
                Arrays.stream(MediatedDownload.class.getRecordComponents())
                        .map(RecordComponent::getName).collect(Collectors.toSet()));
        assertFalse(handle.toString().contains(FILE_NAME));
        verify(store).artifact(ORG_A, ID);
        verify(store).verifyObject(ORG_A, OBJECT_KEY, 1, CHECKSUM);
        DocumentEvent fact = onlyFact();
        assertEquals(EventType.DOWNLOADED, fact.eventType());
        assertEquals("success", fact.outcome());
        assertEquals(null, fact.detail());
        assertEquals(ID, fact.resourceId());
        assertEquals(REVIEWER, fact.actorSubject());
    }

    /** A renderer has no download permission, so the guard denies before any object access. */
    @Test
    void downloadIsDeniedForARoleWithoutDownloadPermission() {
        roles(RENDERER, "pdf_renderer");
        useTenant(ORG_A, RENDERER);
        when(store.verifyObject(ORG_A, OBJECT_KEY, 1, CHECKSUM)).thenReturn(true);

        ApiException failure = reject(() -> controller.download(ID, "3"));

        assertFailure(failure, 403, "FORBIDDEN");
        verify(store, never()).artifact(anyString(), anyString());
        assertObjectNotRead();
        assertDenialFact("denied", "FORBIDDEN", RENDERER);
    }

    /**
     * An actor active only in another tenant cannot probe this artifact: the tenant-scoped
     * read is empty and no fact is appended, so the ledger cannot reveal existence.
     */
    @Test
    void downloadConcealsAnArtifactOwnedByAnotherTenant() {
        useTenant(ORG_B, CLINICIAN);
        roles(CLINICIAN, "clinician");
        when(store.artifact(ORG_B, ID)).thenReturn(Optional.empty());

        ApiException failure = reject(() -> controller.download(ID, "3"));

        assertFailure(failure, 404, "NOT_FOUND");
        verify(store).artifact(ORG_B, ID);
        verify(store, never()).artifact(ORG_A, ID);
        assertObjectNotRead();
        assertArtifactUnchanged();
        verify(store, never()).appendEvent(any());
    }

    /** An artifact that has not been completed is not downloadable. */
    @Test
    void downloadRefusesAnArtifactThatIsNotDownloadable() {
        givenArtifact(artifact(ArtifactState.CLEAN, ScanVerdict.CLEAN, 2));
        when(store.verifyObject(ORG_A, OBJECT_KEY, 1, CHECKSUM)).thenReturn(true);

        ApiException failure = reject(() -> controller.download(ID, "2"));

        assertFailure(failure, 409, "NOT_COMPLETED");
        assertObjectNotRead();
        assertDenialFact("conflict", "NOT_COMPLETED", REVIEWER);
    }

    /** A stale download precondition is refused before the private object is touched. */
    @Test
    void downloadRejectsAStaleVersion() {
        givenArtifact(artifact(ArtifactState.COMPLETED, ScanVerdict.CLEAN, 4));
        when(store.verifyObject(ORG_A, OBJECT_KEY, 1, CHECKSUM)).thenReturn(true);

        ApiException failure = reject(() -> controller.download(ID, "3"));

        assertFailure(failure, 412, "STALE_VERSION");
        assertObjectNotRead();
        assertDenialFact("conflict", "STALE_VERSION", REVIEWER);
    }

    /** A completed artifact whose object no longer verifies is refused, with no DOWNLOADED fact. */
    @Test
    void downloadRefusesAnUnverifiedPrivateObject() {
        givenArtifact(artifact(ArtifactState.COMPLETED, ScanVerdict.CLEAN, 3));

        ApiException failure = reject(() -> controller.download(ID, "3"));

        assertFailure(failure, 409, "OBJECT_UNVERIFIED");
        verify(store).verifyObject(ORG_A, OBJECT_KEY, 1, CHECKSUM);
        assertDenialFact("conflict", "OBJECT_UNVERIFIED", REVIEWER);
    }

    /**
     * A patient-role principal is rejected by the controller before the service runs:
     * it carries no verified patient link, and no denial fact is attributed to a tenant.
     */
    @Test
    void completionAndDownloadFailClosedForAPatientRoleActor() {
        roles(PATIENT_ROLE, "patient");
        useTenant(ORG_A, PATIENT_ROLE);

        ApiException completion = reject(() -> controller.complete(ID, new CompleteRequest("verified"), "2"));
        ApiException download = reject(() -> controller.download(ID, "3"));

        assertFailure(completion, 403, "FORBIDDEN");
        assertFailure(download, 403, "FORBIDDEN");
        verify(store, never()).artifact(anyString(), anyString());
        verify(store, never()).appendEvent(any());
    }

    /** Without an authenticated tenant context both commands fail closed before any role lookup. */
    @Test
    void completionAndDownloadRequireAnAuthenticatedTenantContext() {
        TenantContext.clear();

        ApiException completion = reject(() -> controller.complete(ID, new CompleteRequest("verified"), "2"));
        ApiException download = reject(() -> controller.download(ID, "3"));

        assertFailure(completion, 401, "UNAUTHENTICATED");
        assertFailure(download, 401, "UNAUTHENTICATED");
        verify(store, never()).artifact(anyString(), anyString());
        verify(store, never()).appendEvent(any());
        verifyNoInteractions(staff);
    }

    /** A missing If-Match precondition is refused before the artifact is read. */
    @Test
    void downloadRequiresAVersionPrecondition() {
        ApiException failure = reject(() -> controller.download(ID, null));

        assertFailure(failure, 428, "PRECONDITION_REQUIRED");
        verify(store, never()).artifact(anyString(), anyString());
        verify(store, never()).appendEvent(any());
    }
}
