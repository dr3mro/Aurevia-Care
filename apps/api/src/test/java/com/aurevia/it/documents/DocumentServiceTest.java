package com.aurevia.it.documents;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

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
import com.aurevia.documents.DocumentModel.AssociationEvent;
import com.aurevia.documents.DocumentModel.DocumentAssoc;
import com.aurevia.documents.DocumentModel.DocumentAssocView;
import com.aurevia.documents.DocumentPolicy;
import com.aurevia.documents.DocumentRepository;
import com.aurevia.documents.DocumentService;
import com.aurevia.documents.DocumentModel.LinkRequest;
import com.aurevia.documents.PdfRenderer;
import com.aurevia.documents.DocumentModel.PdfSpec;
import com.aurevia.documents.DocumentModel.RenderRequest;
import com.aurevia.documents.DocumentModel.RenderedDocument;
import com.aurevia.tenant.TenantContext;

/** S-G1-07 service branches: authorization, tenant concealment, immutability and audit. */
class DocumentServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "clinician-a";
    private static final String ID = "33333333-3333-3333-3333-333333333333";
    private static final String SUBJECT = "22222222-2222-2222-2222-222222222222";
    private static final byte[] CONTENT = "synthetic original".getBytes(StandardCharsets.UTF_8);
    private static final String BASE64 = Base64.getEncoder().encodeToString(CONTENT);

    private DocumentRepository repository;
    private AuditAppender audit;
    private PdfRenderer renderer;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        repository = mock(DocumentRepository.class);
        audit = mock(AuditAppender.class);
        renderer = mock(PdfRenderer.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        service = new DocumentService(repository, new DocumentPolicy(), audit, transactions, renderer);
        when(renderer.render(any())).thenReturn(new byte[] { '%', 'P', 'D', 'F' });
        // Order/result renders and every rendered-version listing now probe the
        // subject through the RLS-bounded fold; default the existing cases to present.
        when(repository.subjectExists(anyString(), anyString())).thenReturn(true);
        TenantContext.set(new TenantContext(ORG, ACTOR, "DOCUMENT_ADMIN", "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorized(String... roles) {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of(roles.length == 0 ? "clinician" : roles[0]));
    }

    private DocumentAssoc assoc(String status, int version, String reason) {
        return new DocumentAssoc(ID, version, "chart", SUBJECT, "scan.pdf", "application/pdf",
                CONTENT.length, "digest", 90, status, reason, ACTOR, "2026-11-19T10:00:00Z", null);
    }

    private LinkRequest link() {
        return new LinkRequest("chart", SUBJECT, "scan.pdf", "application/pdf", BASE64, 90);
    }

    private RenderRequest renderRequest() {
        return new RenderRequest("chart", SUBJECT, "en", "Summary", List.of("Plan: rest"),
                "SYNTHETIC-LAB", "REQ-1", "2026-11-19T10:00:00Z", 90, null);
    }

    private static int status(ApiException exception) {
        return exception.status();
    }

    @Test
    void linkRejectsInvalidInputBeforeAnyWrite() {
        assertEquals(422, status(assertThrows(ApiException.class,
                () -> service.link(new LinkRequest("encounter", SUBJECT, "scan.pdf", "application/pdf", BASE64, 0)))));
        assertEquals(422, status(assertThrows(ApiException.class,
                () -> service.link(new LinkRequest("chart", "bad-id", "scan.pdf", "application/pdf", BASE64, 0)))));
        assertEquals(422, status(assertThrows(ApiException.class,
                () -> service.link(new LinkRequest("chart", SUBJECT, "../scan.pdf", "application/pdf", BASE64, 0)))));
        assertEquals(422, status(assertThrows(ApiException.class,
                () -> service.link(new LinkRequest("chart", SUBJECT, "scan.pdf", "image/gif", BASE64, 0)))));
        assertEquals(422, status(assertThrows(ApiException.class,
                () -> service.link(new LinkRequest("chart", SUBJECT, "scan.pdf", "application/pdf", "%%%", 0)))));
        assertEquals(422, status(assertThrows(ApiException.class,
                () -> service.link(new LinkRequest("chart", SUBJECT, "scan.pdf", "application/pdf", BASE64, 45)))));
        verify(repository, never()).insert(any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void linkDeniesInactiveMembershipAndAudits() {
        assertEquals(403, status(assertThrows(ApiException.class, () -> service.link(link()))));
        verify(audit).append(eq(TenantContext.current()), eq("DOCUMENT_LINK"), eq("DOCUMENT_LINK"),
                eq("DOCUMENT"), eq("DOCUMENTS"), eq(SUBJECT), eq("DENIED"));
        verify(repository, never()).insert(any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void linkDeniesMissingPermissionAndAudits() {
        authorized("privacy_auditor");
        assertEquals(403, status(assertThrows(ApiException.class, () -> service.link(link()))));
        verify(audit).append(any(), eq("DOCUMENT_LINK"), anyString(), anyString(), anyString(), anyString(), eq("DENIED"));
    }

    @Test
    void linkConcealsAnUnknownChart() {
        authorized();
        when(repository.chartExists(SUBJECT)).thenReturn(false);
        assertEquals(404, status(assertThrows(ApiException.class, () -> service.link(link()))));
        verify(repository, never()).insert(any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void linkStoresOriginalBytesAndAppendsHistory() {
        authorized();
        when(repository.chartExists(SUBJECT)).thenReturn(true);
        when(repository.insert(any(), any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(1);
        when(repository.findAssociation(anyString())).thenReturn(Optional.of(assoc("active", 1, null)));
        assertEquals(ID, service.link(link()).id());
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(repository).insert(eq(ORG), anyString(), eq("chart"), eq(SUBJECT), eq("scan.pdf"),
                eq("application/pdf"), bytes.capture(), eq(90));
        assertArrayEquals(CONTENT, bytes.getValue());
        verify(repository).appendEvent(anyString(), eq("link"), eq(null));
        verify(audit).append(eq(TenantContext.current()), eq("DOCUMENT_LINK"), eq("DOCUMENT_LINK"),
                eq("DOCUMENT"), eq("DOCUMENTS"), anyString(), eq("SUCCESS"));
    }

    @Test
    void linkForOrderNeedsNoChartLookup() {
        authorized();
        when(repository.insert(any(), any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(1);
        when(repository.findAssociation(anyString())).thenReturn(Optional.of(assoc("active", 1, null)));
        service.link(new LinkRequest("order", SUBJECT, "req.pdf", "application/pdf", BASE64, 0));
        verify(repository, never()).chartExists(anyString());
    }

    @Test
    void linkMapsAConcurrentConflictTo409() {
        authorized();
        when(repository.chartExists(SUBJECT)).thenReturn(true);
        when(repository.insert(any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenThrow(new DuplicateKeyException("duplicate"));
        assertEquals(409, status(assertThrows(ApiException.class, () -> service.link(link()))));
    }

    @Test
    void associationReadsAreValidatedAuthorizedAndAudited() {
        assertEquals(422, status(assertThrows(ApiException.class, () -> service.association("bad"))));
        assertEquals(403, status(assertThrows(ApiException.class, () -> service.association(ID))));

        authorized();
        when(repository.findAssociation(ID)).thenReturn(Optional.empty());
        assertEquals(404, status(assertThrows(ApiException.class, () -> service.association(ID))));
        verify(audit).append(any(), eq("DOCUMENT_READ"), anyString(), anyString(), anyString(), eq(ID), eq("NOT_FOUND"));

        when(repository.findAssociation(ID)).thenReturn(Optional.of(assoc("active", 1, null)));
        AssociationEvent event = new AssociationEvent("e-1", ID, "link", null, ACTOR, "2026-11-19T10:00:00Z");
        when(repository.associationHistory(ID)).thenReturn(List.of(event));
        DocumentAssocView view = service.association(ID);
        assertEquals(1, view.history().size());
        verify(audit).append(any(), eq("DOCUMENT_READ"), anyString(), anyString(), anyString(), eq(ID), eq("SUCCESS"));
    }

    @Test
    void voidChecksEveryBoundaryAndPreservesHistory() {
        assertEquals(422, status(assertThrows(ApiException.class,
                () -> service.voidAssociation("bad", 1, "reason"))));
        assertEquals(403, status(assertThrows(ApiException.class,
                () -> service.voidAssociation(ID, 1, "reason"))));

        authorized();
        when(repository.findAssociation(ID)).thenReturn(Optional.empty());
        assertEquals(404, status(assertThrows(ApiException.class,
                () -> service.voidAssociation(ID, 1, "reason"))));

        when(repository.findAssociation(ID)).thenReturn(Optional.of(assoc("voided", 2, "old")));
        assertEquals(409, status(assertThrows(ApiException.class,
                () -> service.voidAssociation(ID, 1, "reason"))));

        when(repository.findAssociation(ID)).thenReturn(Optional.of(assoc("active", 2, null)));
        assertEquals(412, status(assertThrows(ApiException.class,
                () -> service.voidAssociation(ID, 1, "reason"))));

        when(repository.findAssociation(ID)).thenReturn(Optional.of(assoc("active", 1, null)));
        when(repository.voidAssociation(ID, 1, "reason")).thenReturn(0);
        assertEquals(412, status(assertThrows(ApiException.class,
                () -> service.voidAssociation(ID, 1, "reason"))));

        when(repository.voidAssociation(ID, 1, "reason")).thenReturn(1);
        when(repository.findAssociation(ID))
                .thenReturn(Optional.of(assoc("active", 1, null)))
                .thenReturn(Optional.of(assoc("voided", 2, "reason")));
        assertEquals("voided", service.voidAssociation(ID, 1, "reason").status());
        verify(repository).appendEvent(ID, "void", "reason");
        verify(audit).append(any(), eq("DOCUMENT_VOID"), eq("DOCUMENT_VOID"), anyString(), anyString(),
                eq(ID), eq("SUCCESS"));
    }

    @Test
    void renderRejectsInvalidInputBeforeAnyWrite() {
        assertEquals(422, status(assertThrows(ApiException.class,
                () -> service.render(new RenderRequest("chart", SUBJECT, "fr", "T", List.of("l"),
                        "S", "I", "E", 0, null)))));
        assertEquals(422, status(assertThrows(ApiException.class,
                () -> service.render(new RenderRequest("chart", SUBJECT, "en", " ", List.of("l"),
                        "S", "I", "E", 0, null)))));
        assertEquals(422, status(assertThrows(ApiException.class,
                () -> service.render(new RenderRequest("chart", SUBJECT, "en", "T", List.of(" "),
                        "S", "I", "E", 0, null)))));
        assertEquals(422, status(assertThrows(ApiException.class,
                () -> service.render(new RenderRequest("chart", SUBJECT, "en", "T", List.of("l"),
                        "S", "I", "E", 45, null)))));
        assertEquals(422, status(assertThrows(ApiException.class,
                () -> service.render(new RenderRequest("chart", SUBJECT, "en", "T", null,
                        "S", "I", "E", 0, null)))));
        assertEquals(422, status(assertThrows(ApiException.class,
                () -> service.render(new RenderRequest("chart", SUBJECT, "en", "T",
                        java.util.Arrays.asList("l", null), "S", "I", "E", 0, null)))));
        verify(repository, never()).nextRenderVersion(anyString(), anyString(), anyString());
    }

    @Test
    void renderDeniesInactiveMembershipAndMissingPermission() {
        assertEquals(403, status(assertThrows(ApiException.class, () -> service.render(renderRequest()))));
        authorized("reception");
        assertEquals(403, status(assertThrows(ApiException.class, () -> service.render(renderRequest()))));
    }

    @Test
    void renderConcealsMissingChart() {
        authorized();
        when(repository.chartExists(SUBJECT)).thenReturn(false);
        assertEquals(404, status(assertThrows(ApiException.class, () -> service.render(renderRequest()))));
    }

    @Test
    void renderForOrderSkipsTheChartLookup() {
        authorized();
        when(repository.nextRenderVersion("order", SUBJECT, "en")).thenReturn(1);
        when(repository.findRendered(anyString())).thenReturn(Optional.of(
                new RenderedDocument(ID, 1, "order", SUBJECT, "en", "LTR", 1, 4L,
                        "digest", "provenance", "now", ACTOR)));
        service.render(new RenderRequest("order", SUBJECT, "en", "T", List.of("l"),
                "S", "I", "E", 0, null));
        verify(repository, never()).chartExists(anyString());
    }

    @Test
    void renderConcealsOrRejectsInvalidSourceAssociation() {
        authorized();
        when(repository.chartExists(SUBJECT)).thenReturn(true);
        RenderRequest withSource = new RenderRequest("chart", SUBJECT, "en", "T", List.of("l"),
                "S", "I", "E", 0, ID);
        when(repository.findAssociation(ID)).thenReturn(Optional.empty());
        assertEquals(404, status(assertThrows(ApiException.class, () -> service.render(withSource))));
        when(repository.findAssociation(ID)).thenReturn(Optional.of(assoc("voided", 2, "reason")));
        assertEquals(409, status(assertThrows(ApiException.class, () -> service.render(withSource))));
    }

    @Test
    void renderAcceptsAnActiveSourceAssociation() {
        authorized();
        when(repository.chartExists(SUBJECT)).thenReturn(true);
        when(repository.findAssociation(ID)).thenReturn(Optional.of(assoc("active", 1, null)));
        when(repository.nextRenderVersion("chart", SUBJECT, "en")).thenReturn(1);
        when(repository.findRendered(anyString())).thenReturn(Optional.of(
                new RenderedDocument(ID, 1, "chart", SUBJECT, "en", "LTR", 1, 4L,
                        "digest", "provenance", "now", ACTOR)));
        RenderRequest withSource = new RenderRequest("chart", SUBJECT, "en", "T", List.of("l"),
                "S", "I", "E", 0, ID);
        assertEquals(ID, service.render(withSource).id());
        verify(repository).findAssociation(ID);
    }

    @Test
    void renderRejectsASourceFromADifferentSubject() {
        authorized();
        when(repository.chartExists(SUBJECT)).thenReturn(true);
        DocumentAssoc foreign = new DocumentAssoc(ID, 1, "chart",
                "44444444-4444-4444-4444-444444444444", "scan.pdf", "application/pdf",
                1L, "digest", 0, "active", null, ACTOR, "now", null);
        when(repository.findAssociation(ID)).thenReturn(Optional.of(foreign));
        RenderRequest withForeignSource = new RenderRequest("chart", SUBJECT, "en", "T", List.of("l"),
                "S", "I", "E", 0, ID);
        assertEquals(409, status(assertThrows(ApiException.class,
                () -> service.render(withForeignSource))));
        DocumentAssoc wrongType = new DocumentAssoc(ID, 1, "order", SUBJECT, "scan.pdf",
                "application/pdf", 1L, "digest", 0, "active", null, ACTOR, "now", null);
        when(repository.findAssociation(ID)).thenReturn(Optional.of(wrongType));
        assertEquals(409, status(assertThrows(ApiException.class,
                () -> service.render(withForeignSource))));
        verify(repository, never()).nextRenderVersion(anyString(), anyString(), anyString());
    }

    @Test
    void renderCreatesAVersionedImmutableArtifact() {
        authorized();
        when(repository.chartExists(SUBJECT)).thenReturn(true);
        when(repository.nextRenderVersion("chart", SUBJECT, "en")).thenReturn(4);
        RenderedDocument rendered = new RenderedDocument(ID, 4, "chart", SUBJECT, "en", "LTR",
                1, 4L, "digest", "provenance", "2026-11-19T10:00:00Z", ACTOR);
        when(repository.findRendered(anyString())).thenReturn(Optional.of(rendered));
        assertEquals(ID, service.render(renderRequest()).id());

        ArgumentCaptor<PdfSpec> spec = ArgumentCaptor.forClass(PdfSpec.class);
        verify(renderer).render(spec.capture());
        assertEquals(ORG, spec.getValue().organizationId());
        assertEquals("chart", spec.getValue().subjectType());
        assertEquals(SUBJECT, spec.getValue().subjectId());
        assertEquals("en", spec.getValue().locale());
        assertEquals(4, spec.getValue().version());
        assertEquals(90, spec.getValue().rotationDegrees());
        assertEquals("Summary", spec.getValue().title());
        assertEquals(List.of("Plan: rest"), spec.getValue().lines());
        assertEquals("SYNTHETIC-LAB", spec.getValue().sourceSystem());
        assertEquals("REQ-1", spec.getValue().sourceId());
        assertEquals("2026-11-19T10:00:00Z", spec.getValue().effectiveAt());

        verify(repository).insertRendered(eq(ORG), anyString(), eq("chart"), eq(SUBJECT), eq(4),
                eq("en"), eq("LTR"), eq("SYNTHETIC-LAB"), eq("REQ-1"), eq("2026-11-19T10:00:00Z"),
                any(byte[].class));
        verify(audit).append(any(), eq("DOCUMENT_RENDER"), eq("DOCUMENT_RENDER"), anyString(), anyString(),
                anyString(), eq("SUCCESS"));
    }

    @Test
    void renderBindsTheValidatedSourceAssociationIntoTheRenderSpec() {
        authorized();
        when(repository.chartExists(SUBJECT)).thenReturn(true);
        when(repository.findAssociation(ID)).thenReturn(Optional.of(assoc("active", 1, null)));
        when(repository.nextRenderVersion("chart", SUBJECT, "en")).thenReturn(1);
        when(repository.findRendered(anyString())).thenReturn(Optional.of(
                new RenderedDocument(ID, 1, "chart", SUBJECT, "en", "LTR", 1, 4L,
                        "digest", "provenance", "now", ACTOR)));
        RenderRequest withSource = new RenderRequest("chart", SUBJECT, "en", "T", List.of("l"),
                "S", "I", "E", 0, ID);
        service.render(withSource);
        ArgumentCaptor<PdfSpec> spec = ArgumentCaptor.forClass(PdfSpec.class);
        verify(renderer).render(spec.capture());
        assertEquals(ID, spec.getValue().sourceAssociationId(),
                "the validated original must be bound to its derived PDF");
    }

    @Test
    void renderMapsADuplicateVersionTo409() {
        authorized();
        when(repository.chartExists(SUBJECT)).thenReturn(true);
        when(repository.nextRenderVersion("chart", SUBJECT, "en")).thenReturn(2);
        when(repository.insertRendered(any(), any(), any(), any(), anyInt(), any(), any(), any(),
                any(), any(), any())).thenThrow(new DuplicateKeyException("duplicate"));
        assertEquals(409, status(assertThrows(ApiException.class, () -> service.render(renderRequest()))));
    }

    @Test
    void renderedReadsAreValidatedAuthorizedAndAudited() {
        RenderedDocument rendered = new RenderedDocument(ID, 1, "chart", SUBJECT, "en", "LTR",
                1, 4L, "digest", "provenance", "2026-11-19T10:00:00Z", ACTOR);

        assertEquals(422, status(assertThrows(ApiException.class, () -> service.rendered("bad"))));
        assertEquals(403, status(assertThrows(ApiException.class, () -> service.rendered(ID))));
        assertEquals(403, status(assertThrows(ApiException.class, () -> service.renderedContent(ID))));
        assertEquals(403, status(assertThrows(ApiException.class,
                () -> service.renderedVersions("chart", SUBJECT, "en"))));

        authorized();
        when(repository.findRendered(ID)).thenReturn(Optional.empty());
        assertEquals(404, status(assertThrows(ApiException.class, () -> service.rendered(ID))));
        assertEquals(404, status(assertThrows(ApiException.class, () -> service.renderedContent(ID))));
        verify(audit, org.mockito.Mockito.times(2)).append(any(), eq("DOCUMENT_READ"), anyString(),
                anyString(), anyString(), eq(ID), eq("NOT_FOUND"));

        when(repository.findRendered(ID)).thenReturn(Optional.of(rendered));
        when(repository.renderedContent(ID)).thenReturn(Optional.of(new byte[] { '%', 'P', 'D', 'F' }));
        assertEquals(ID, service.rendered(ID).id());
        assertArrayEquals(new byte[] { '%', 'P', 'D', 'F' }, service.renderedContent(ID));
        verify(audit, org.mockito.Mockito.times(2)).append(any(), eq("DOCUMENT_READ"), anyString(),
                anyString(), anyString(), eq(ID), eq("SUCCESS"));
    }

    @Test
    void renderedVersionListingValidatesAndAudits() {
        assertEquals(422, status(assertThrows(ApiException.class,
                () -> service.renderedVersions("encounter", SUBJECT, "en"))));
        assertEquals(422, status(assertThrows(ApiException.class,
                () -> service.renderedVersions("chart", "bad", "en"))));
        assertEquals(422, status(assertThrows(ApiException.class,
                () -> service.renderedVersions("chart", SUBJECT, "fr"))));
        authorized();
        RenderedDocument rendered = new RenderedDocument(ID, 1, "chart", SUBJECT, "en", "LTR",
                1, 4L, "digest", "provenance", "2026-11-19T10:00:00Z", ACTOR);
        when(repository.renderedVersions("chart", SUBJECT, "en")).thenReturn(List.of(rendered));
        assertEquals(1, service.renderedVersions("chart", SUBJECT, "en").size());
        verify(audit).append(any(), eq("DOCUMENT_READ"), eq("DOCUMENT_LIST"), anyString(), anyString(),
                eq("LIST"), eq("SUCCESS"));
        assertTrue(true);
    }
}
