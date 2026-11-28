package com.aurevia.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.documents.DocumentContract.BindRequest;
import com.aurevia.documents.DocumentContract.DocumentEvent;
import com.aurevia.documents.DocumentContract.EventType;
import com.aurevia.documents.DocumentContract.Language;
import com.aurevia.documents.DocumentContract.PdfBinding;
import com.aurevia.documents.DocumentContract.PdfJob;
import com.aurevia.documents.DocumentContract.PdfJobState;
import com.aurevia.documents.DocumentContract.ReleaseDecision;
import com.aurevia.documents.DocumentContract.ReleaseRequest;
import com.aurevia.documents.DocumentContract.SourceRevision;
import com.aurevia.documents.DocumentModel.DocumentAssoc;
import com.aurevia.documents.DocumentModel.LinkRequest;
import com.aurevia.documents.DocumentModel.RenderRequest;
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenant.TenantContext;

/**
 * Negative-path evidence for the two document HTTP entry points (CLN-07, ARC-02).
 *
 * <p>The production branches are already exercised by neighbouring tests; this
 * class strengthens the assertions they do not make: the exact machine-readable
 * code at the controller boundary, the exact audit outcome on a denial or a
 * concealed foreign-tenant read, the specific compare-and-set that failed, and
 * Mockito call counts proving a content-based retry performs no second write.
 * Every case enters through a public controller method with a real domain
 * service over a mocked persistence boundary. The OpenAPI {@code Idempotency-Key}
 * parameter is optional correlation-only for these routes and is not consumed by
 * either controller, so replay here is proved from content, never from a header.
 * All identifiers and payload metadata are synthetic.</p>
 */
class DocumentsNegativePathsTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String REVIEWER = "reviewer-negative";
    private static final String CLINICIAN = "clinician-negative";
    private static final String RECEPTION = "reception-negative";
    private static final String PATIENT_ROLE = "patient-negative";
    private static final String ID = "33333333-3333-3333-3333-333333333333";
    private static final String SUBJECT = "44444444-4444-4444-4444-444444444444";
    private static final String JOB_ID = "55555555-5555-5555-5555-555555555555";
    private static final String REVISION_ID = "66666666-6666-6666-6666-666666666666";
    private static final String REASON = "wrong chart";
    private static final String CHECKSUM = "a".repeat(64);
    private static final String TEMPLATE = "soap-en";
    private static final String POLICY = "policy-1";
    private static final String CONTENT = Base64.getEncoder()
            .encodeToString("synthetic pdf bytes".getBytes(StandardCharsets.UTF_8));
    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");

    private DocumentRepository repository;
    private AuditAppender audit;
    private DocumentController documents;
    private DocumentStore store;
    private StaffRepository staff;
    private DocumentArtifactController artifacts;
    private ArgumentCaptor<DocumentEvent> facts;

    /** Build both real controller stacks over mocked persistence boundaries. */
    @BeforeEach
    void setUp() {
        repository = mock(DocumentRepository.class);
        audit = mock(AuditAppender.class);
        PdfRenderer renderer = mock(PdfRenderer.class);
        when(renderer.render(any())).thenReturn(new byte[] { '%', 'P', 'D', 'F' });
        DocumentService service = new DocumentService(repository, new DocumentPolicy(), audit,
                transactions(), renderer);
        documents = new DocumentController(service);

        store = mock(DocumentStore.class);
        staff = mock(StaffRepository.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        DocumentGuard guard = new DocumentGuard(store, clock);
        QuarantineService quarantine = new QuarantineService(store, guard, clock);
        // The PDF service is final; construct the real collaborator over the same mocked store.
        PdfBindingService pdf = new PdfBindingService(store, guard, clock);
        artifacts = new DocumentArtifactController(quarantine, pdf, staff, transactions());

        roles(REVIEWER, "document_reviewer");
        useTenant(ORG_A, REVIEWER);
        facts = ArgumentCaptor.forClass(DocumentEvent.class);
    }

    /** Remove the synthetic tenant context so other tests are unaffected. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Synchronous transaction wrapper: the callback runs inside the mocked boundary. */
    private TransactionTemplate transactions() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(template).execute(any());
        return template;
    }

    /** Bind a synthetic organization and subject, mirroring the authentication layer. */
    private void useTenant(String organizationId, String subject) {
        TenantContext.set(new TenantContext(organizationId, subject, "DOCUMENT_NEGATIVE_PATHS", "corr-negative"));
    }

    /** Grant the synthetic subject the given current active roles in the tenant. */
    private void roles(String subject, String... roleKeys) {
        when(staff.activeRoles(subject)).thenReturn(List.of(roleKeys));
    }

    /** Grant the original-document subject active membership and the given roles. */
    private void authorize(String subject, String... roleKeys) {
        when(repository.hasActiveMembership(subject)).thenReturn(true);
        when(repository.activeRoles(subject)).thenReturn(List.of(roleKeys));
    }

    /** Capture the fatal command failure, failing when the command succeeds. */
    private static ApiException reject(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    /** Assert the machine-readable status and code returned to the caller. */
    private static void assertFailure(ApiException failure, int status, String code) {
        assertEquals(status, failure.status());
        assertEquals(code, failure.code());
    }

    /** Assert no command was audited as successful. */
    private void assertNoSuccessAudit() {
        verify(audit, never()).append(any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), eq("SUCCESS"));
    }

    /** A valid link command with one field varied by a caller. */
    private static LinkRequest link(String subjectType) {
        return new LinkRequest(subjectType, SUBJECT, "scan.pdf", "application/pdf", CONTENT, 0);
    }

    /** A valid link command with a varied subject identifier. */
    private static LinkRequest linkWithSubject(String subjectId) {
        return new LinkRequest("chart", subjectId, "scan.pdf", "application/pdf", CONTENT, 0);
    }

    /** A valid link command with a varied file name. */
    private static LinkRequest linkWithFileName(String fileName) {
        return new LinkRequest("chart", SUBJECT, fileName, "application/pdf", CONTENT, 0);
    }

    /** A valid link command with a varied media type. */
    private static LinkRequest linkWithMediaType(String mediaType) {
        return new LinkRequest("chart", SUBJECT, "scan.pdf", mediaType, CONTENT, 0);
    }

    /** A valid link command with varied original bytes. */
    private static LinkRequest linkWithContent(String content) {
        return new LinkRequest("chart", SUBJECT, "scan.pdf", "application/pdf", content, 0);
    }

    /** A valid render command with one field varied by a caller. */
    private static RenderRequest render(String subjectType) {
        return new RenderRequest(subjectType, SUBJECT, "en", "Summary", List.of("Plan: rest"),
                "SYNTHETIC-LAB", "REQ-1", "2026-11-23T10:00:00Z", 0, null);
    }

    /** A valid render command with varied content lines. */
    private static RenderRequest renderWithLines(List<String> lines) {
        return new RenderRequest("chart", SUBJECT, "en", "Summary", lines, "SYNTHETIC-LAB",
                "REQ-1", "2026-11-23T10:00:00Z", 0, null);
    }

    /** A valid render command with a varied title. */
    private static RenderRequest renderWithTitle(String title) {
        return new RenderRequest("chart", SUBJECT, "en", title, List.of("Plan: rest"),
                "SYNTHETIC-LAB", "REQ-1", "2026-11-23T10:00:00Z", 0, null);
    }

    /** A valid synthetic revision selector in the given organization. */
    private static SourceRevision revision(String organizationId) {
        return new SourceRevision(organizationId, REVISION_ID, 1, CHECKSUM);
    }

    /** An active association read at version one by the caller. */
    private static DocumentAssoc activeAssociation() {
        return new DocumentAssoc(ID, 1, "chart", SUBJECT, "scan.pdf", "application/pdf",
                CONTENT.length(), CHECKSUM, 90, "active", null, CLINICIAN,
                "2026-11-23T09:00:00Z", null);
    }

    /**
     * A malformed identifier, target type or media type is refused with a stable code
     * before any repository access or transaction opens.
     */
    @Test
    void linkRefusesMalformedSelectorsWithStableCodes() {
        assertFailure(reject(() -> documents.link(link(null))), 422, "INVALID_SUBJECT_TYPE");
        assertFailure(reject(() -> documents.link(linkWithSubject(null))), 422, "INVALID_ID");
        assertFailure(reject(() -> documents.link(linkWithSubject("nope"))), 422, "INVALID_ID");
        assertFailure(reject(() -> documents.link(linkWithMediaType("image/gif"))), 422,
                "INVALID_MEDIA_TYPE");
        assertFailure(reject(() -> documents.link(linkWithMediaType(null))), 422,
                "INVALID_MEDIA_TYPE");
        verifyNoInteractions(repository);
        verifyNoInteractions(audit);
    }

    /** A traversal, path-bearing or overlong file name cannot enter the retained-original store. */
    @Test
    void linkRefusesPathBearingAndOverlongFileNames() {
        assertFailure(reject(() -> documents.link(linkWithFileName(".."))), 422,
                "INVALID_FILE_NAME");
        assertFailure(reject(() -> documents.link(linkWithFileName("a..b"))), 422,
                "INVALID_FILE_NAME");
        assertFailure(reject(() -> documents.link(linkWithFileName("../x.pdf"))), 422,
                "INVALID_FILE_NAME");
        assertFailure(reject(() -> documents.link(linkWithFileName("x".repeat(201)))), 422,
                "INVALID_FILE_NAME");
        assertFailure(reject(() -> documents.link(linkWithFileName(null))), 422,
                "INVALID_FILE_NAME");
        verifyNoInteractions(repository);
    }

    /** Absent, malformed and oversized original bytes are bounded at the boundary. */
    @Test
    void linkRefusesMissingMalformedAndOversizedContent() {
        assertFailure(reject(() -> documents.link(linkWithContent(null))), 422, "INVALID_CONTENT");
        assertFailure(reject(() -> documents.link(linkWithContent("%%%"))), 422, "INVALID_CONTENT");
        String oversized = Base64.getEncoder().encodeToString(
                new byte[DocumentInputs.MAX_ORIGINAL_BYTES + 1]);
        assertFailure(reject(() -> documents.link(linkWithContent(oversized))), 422,
                "CONTENT_TOO_LARGE");
        verifyNoInteractions(repository);
    }

    /** Empty, excessive and overlong content lines are refused with the render code. */
    @Test
    void renderRefusesUnboundedContentLines() {
        assertFailure(reject(() -> documents.render(renderWithLines(List.of()))), 422,
                "INVALID_LINES");
        assertFailure(reject(() -> documents.render(renderWithLines(
                IntStream.range(0, 41).mapToObj(i -> "line" + i).toList()))),
                422, "INVALID_LINES");
        assertFailure(reject(() -> documents.render(renderWithLines(List.of("x".repeat(501))))),
                422, "INVALID_LINES");
        verifyNoInteractions(repository);
    }

    /** An unsupported render target and an overlong title are refused before authorization. */
    @Test
    void renderRefusesMalformedSelectorsAndBoundedText() {
        assertFailure(reject(() -> documents.render(render("billing"))), 422,
                "INVALID_SUBJECT_TYPE");
        assertFailure(reject(() -> documents.render(renderWithTitle("t".repeat(201)))), 422,
                "INVALID_TITLE");
        verifyNoInteractions(repository);
    }

    /** An inactive membership cannot render, and the denial is audited before it leaves. */
    @Test
    void renderDeniesInactiveMembershipAndAuditsTheDenial() {
        useTenant(ORG_A, CLINICIAN);
        when(repository.hasActiveMembership(CLINICIAN)).thenReturn(false);

        ApiException failure = reject(() -> documents.render(render("chart")));

        assertFailure(failure, 403, "FORBIDDEN");
        verify(audit).append(TenantContext.current(), "DOCUMENT_RENDER", "DOCUMENT_RENDER",
                "DOCUMENT", "DOCUMENTS", SUBJECT, "DENIED");
        assertNoSuccessAudit();
        verify(repository, never()).chartExists(anyString());
    }

    /** A role allowed to link and read cannot void, and the denial is attributed to the action. */
    @Test
    void voidDeniesARoleWithoutVoidPermissionAndAuditsTheDenial() {
        useTenant(ORG_A, RECEPTION);
        authorize(RECEPTION, "reception");

        ApiException failure = reject(() -> documents.voidAssociation(ID,
                new DocumentController.VoidRequest(REASON), "1"));

        assertFailure(failure, 403, "FORBIDDEN");
        verify(audit).append(TenantContext.current(), "DOCUMENT_VOID", "DOCUMENT_VOID",
                "DOCUMENT", "DOCUMENTS", ID, "DENIED");
        assertNoSuccessAudit();
        verify(repository, never()).findAssociation(anyString());
    }

    /** A lost compare-and-set at the read version is stale, not a second void. */
    @Test
    void voidRefusesALostCompareAndSetWithoutAppendingHistory() {
        authorize(CLINICIAN, "clinician");
        useTenant(ORG_A, CLINICIAN);
        when(repository.findAssociation(ID)).thenReturn(Optional.of(activeAssociation()));
        when(repository.voidAssociation(ID, 1, REASON)).thenReturn(0);

        ApiException failure = reject(() -> documents.voidAssociation(ID,
                new DocumentController.VoidRequest(REASON), "1"));

        assertFailure(failure, 412, "STALE_VERSION");
        verify(repository).voidAssociation(ID, 1, REASON);
        verify(repository, never()).appendEvent(anyString(), anyString(), anyString());
        assertNoSuccessAudit();
    }

    /** A concurrent insert conflict surfaces as a stable 409 with no success audit. */
    @Test
    void linkMapsADuplicateInsertToConflictWithoutWritingHistory() {
        authorize(CLINICIAN, "clinician");
        useTenant(ORG_A, CLINICIAN);
        when(repository.chartExists(SUBJECT)).thenReturn(true);
        when(repository.insert(eq(ORG_A), anyString(), eq("chart"), eq(SUBJECT), eq("scan.pdf"),
                eq("application/pdf"), any(byte[].class), eq(0)))
                .thenThrow(new DuplicateKeyException("synthetic duplicate"));

        ApiException failure = reject(() -> documents.link(link("chart")));

        assertFailure(failure, 409, "CONFLICT");
        verify(repository, never()).appendEvent(anyString(), anyString(), anyString());
        assertNoSuccessAudit();
    }

    /** A competing render version conflict is concealed behind a stable 409. */
    @Test
    void renderMapsADuplicateVersionToConflictWithoutAuditingSuccess() {
        authorize(CLINICIAN, "clinician");
        useTenant(ORG_A, CLINICIAN);
        when(repository.chartExists(SUBJECT)).thenReturn(true);
        when(repository.nextRenderVersion("chart", SUBJECT, "en")).thenReturn(1);
        when(repository.insertRendered(eq(ORG_A), anyString(), eq("chart"), eq(SUBJECT), eq(1),
                eq("en"), eq("LTR"), anyString(), anyString(), anyString(), any(byte[].class)))
                .thenThrow(new DuplicateKeyException("synthetic duplicate"));

        ApiException failure = reject(() -> documents.render(render("chart")));

        assertFailure(failure, 409, "CONFLICT");
        assertNoSuccessAudit();
    }

    /** A foreign association identifier resolves empty and is audited only as not found. */
    @Test
    void associationReadConcealsAForeignTenantRecord() {
        useTenant(ORG_B, CLINICIAN);
        authorize(CLINICIAN, "clinician");
        when(repository.findAssociation(ID)).thenReturn(Optional.empty());

        ApiException failure = reject(() -> documents.association(ID));

        assertFailure(failure, 404, "NOT_FOUND");
        verify(audit).append(TenantContext.current(), "DOCUMENT_READ", "DOCUMENT_READ",
                "DOCUMENT", "DOCUMENTS", ID, "NOT_FOUND");
        assertNoSuccessAudit();
        verify(repository, never()).appendEvent(anyString(), anyString(), anyString());
    }

    /** A foreign rendered revision is not found, never a readable success. */
    @Test
    void renderedMetadataConcealsAForeignTenantRecord() {
        useTenant(ORG_B, CLINICIAN);
        authorize(CLINICIAN, "clinician");
        when(repository.findRendered(ID)).thenReturn(Optional.empty());

        ApiException failure = reject(() -> documents.rendered(ID));

        assertFailure(failure, 404, "NOT_FOUND");
        verify(audit).append(TenantContext.current(), "DOCUMENT_READ", "DOCUMENT_READ",
                "DOCUMENT", "DOCUMENTS", ID, "NOT_FOUND");
        assertNoSuccessAudit();
    }

    /** A foreign rendered byte read is not found and never serves the immutable PDF. */
    @Test
    void renderedContentConcealsAForeignTenantRecord() {
        useTenant(ORG_B, CLINICIAN);
        authorize(CLINICIAN, "clinician");
        when(repository.renderedContent(ID)).thenReturn(Optional.empty());

        ApiException failure = reject(() -> documents.renderedContent(ID));

        assertFailure(failure, 404, "NOT_FOUND");
        verify(audit).append(TenantContext.current(), "DOCUMENT_READ", "DOCUMENT_READ",
                "DOCUMENT", "DOCUMENTS", ID, "NOT_FOUND");
        assertNoSuccessAudit();
    }

    /** A foreign revision selector is denied with a fact that carries only the selector. */
    @Test
    void foreignRevisionDenialRecordsOnlyTheSyntheticRevisionSelector() {
        useTenant(ORG_A, CLINICIAN);
        roles(CLINICIAN, "clinician");

        ApiException failure = reject(() -> artifacts.requestRender(
                new com.aurevia.documents.DocumentContract.RenderRequest(
                        revision(ORG_B), TEMPLATE, "en")));

        assertFailure(failure, 403, "WRONG_TENANT");
        verify(store).appendEvent(facts.capture());
        DocumentEvent fact = facts.getValue();
        assertEquals(EventType.ACCESS_DENIED, fact.eventType());
        assertEquals("denied", fact.outcome());
        assertEquals("WRONG_TENANT", fact.detail());
        assertEquals(REVISION_ID, fact.resourceId());
        assertEquals(ORG_A, fact.organizationId());
        assertEquals(CLINICIAN, fact.actorSubject());
        assertEquals(NOW, fact.occurredAt());
        assertFalse(fact.toString().contains(CHECKSUM));
        verify(store, never()).putPdfJob(any());
    }

    /** A patient role fails closed on every artifact command before any service runs. */
    @Test
    void artifactCommandsFailClosedForAPatientRole() {
        roles(PATIENT_ROLE, "patient");
        useTenant(ORG_A, PATIENT_ROLE);

        assertFailure(reject(() -> artifacts.requestRender(
                new com.aurevia.documents.DocumentContract.RenderRequest(
                        revision(ORG_A), TEMPLATE, "en"))), 403, "FORBIDDEN");
        assertFailure(reject(() -> artifacts.bind(new BindRequest(revision(ORG_A), JOB_ID))),
                403, "FORBIDDEN");
        assertFailure(reject(() -> artifacts.release(
                new ReleaseRequest(revision(ORG_A), POLICY, false))), 403, "FORBIDDEN");
        verify(store, never()).pdfJobs(anyString());
        verify(store, never()).appendEvent(any());
    }

    /** A missing tenant context fails closed before the role source is consulted. */
    @Test
    void artifactCommandsRequireAnAuthenticatedTenantContext() {
        TenantContext.clear();

        assertFailure(reject(() -> artifacts.requestRender(
                new com.aurevia.documents.DocumentContract.RenderRequest(
                        revision(ORG_A), TEMPLATE, "en"))), 401, "UNAUTHENTICATED");
        assertFailure(reject(() -> artifacts.bind(new BindRequest(revision(ORG_A), JOB_ID))),
                401, "UNAUTHENTICATED");
        assertFailure(reject(() -> artifacts.release(
                new ReleaseRequest(revision(ORG_A), POLICY, false))), 401, "UNAUTHENTICATED");
        verifyNoInteractions(staff);
        verify(store, never()).appendEvent(any());
    }

    /** Repeating one render request returns the original job and starts exactly one job. */
    @Test
    void repeatedRenderRequestStartsOnlyOneJob() {
        // Feed the job created by the first request back into the second job lookup,
        // so the replay follows the production sameWork path with the real job id.
        AtomicReference<PdfJob> created = new AtomicReference<>();
        doAnswer(invocation -> {
            created.set(invocation.getArgument(0));
            return null;
        }).when(store).putPdfJob(any());
        when(store.pdfJobs(ORG_A)).thenAnswer(invocation -> created.get() == null
                ? List.of() : List.of(created.get()));
        var request = new com.aurevia.documents.DocumentContract.RenderRequest(
                revision(ORG_A), TEMPLATE, "en");

        PdfJob first = artifacts.requestRender(request).getBody();
        PdfJob second = artifacts.requestRender(request).getBody();

        assertEquals(first.jobId(), second.jobId());
        verify(store, times(2)).pdfJobs(ORG_A);
        verify(store, times(1)).putPdfJob(any());
        verify(store, times(1)).appendEvent(any());
    }

    /** Repeating a bind returns the original immutable binding without a second write. */
    @Test
    void repeatedBindReturnsTheOriginalBindingWithoutAWrite() {
        useTenant(ORG_A, REVIEWER);
        PdfJob job = new PdfJob(ORG_A, JOB_ID, REVISION_ID, 1, CHECKSUM, TEMPLATE, Language.EN,
                PdfJobState.COMPLETE, 2, "objects/rendered", 1, CHECKSUM, 128, NOW);
        PdfBinding binding = new PdfBinding(ORG_A, REVISION_ID, 1, CHECKSUM, JOB_ID,
                "objects/rendered", 1, CHECKSUM, Language.EN, NOW);
        when(store.pdfJob(ORG_A, JOB_ID)).thenReturn(Optional.of(job));
        when(store.pdfBinding(ORG_A, REVISION_ID)).thenReturn(Optional.of(binding));

        PdfBinding first = artifacts.bind(new BindRequest(revision(ORG_A), JOB_ID)).getBody();
        PdfBinding second = artifacts.bind(new BindRequest(revision(ORG_A), JOB_ID)).getBody();

        assertEquals(binding, first);
        assertEquals(first, second);
        verify(store, never()).putPdfBinding(any());
        // A replay returns the recorded binding without appending a second fact.
        verify(store, never()).appendEvent(any());
    }

    /** Repeating a release returns the recorded decision without a competing insert. */
    @Test
    void repeatedReleaseReturnsTheOriginalDecisionWithoutAWrite() {
        useTenant(ORG_A, CLINICIAN);
        roles(CLINICIAN, "clinician");
        ReleaseDecision decision = new ReleaseDecision(ORG_A, REVISION_ID, 1, CHECKSUM, POLICY,
                CLINICIAN, NOW);
        when(store.release(ORG_A, REVISION_ID, 1, POLICY)).thenReturn(Optional.of(decision));

        ReleaseDecision first = artifacts.release(
                new ReleaseRequest(revision(ORG_A), POLICY, true)).getBody();
        ReleaseDecision second = artifacts.release(
                new ReleaseRequest(revision(ORG_A), POLICY, true)).getBody();

        assertEquals(decision, first);
        assertEquals(first, second);
        verify(store, times(2)).release(ORG_A, REVISION_ID, 1, POLICY);
        verify(store, never()).pdfBinding(anyString(), anyString());
        verify(store, never()).insertReleaseIfAbsent(any());
        verify(store, never()).appendEvent(any());
    }
}
