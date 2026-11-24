package com.aurevia.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.aurevia.documents.DocumentModel.RenderRequest;
import com.aurevia.documents.DocumentModel.RenderedDocument;
import com.aurevia.tenant.TenantContext;

/**
 * CLN-07 subject-existence gate on {@link DocumentService#render} and
 * {@link DocumentService#renderedVersions}.
 *
 * <p>An immutable rendered PDF must not be bound to an order or result that does
 * not exist in the active tenant, and listing renders must not answer for a
 * bogus subject. The repository is a Mockito mock and every identifier is
 * synthetic, so no database or real patient data is involved.</p>
 */
class DocumentServiceSubjectRenderTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "clinician-a";
    private static final String ID = "33333333-3333-3333-3333-333333333333";
    private static final String SUBJECT = "22222222-2222-2222-2222-222222222222";

    private DocumentRepository repository;
    private AuditAppender audit;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        repository = mock(DocumentRepository.class);
        audit = mock(AuditAppender.class);
        PdfRenderer renderer = mock(PdfRenderer.class);
        when(renderer.render(any())).thenReturn(new byte[] { '%', 'P', 'D', 'F' });
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        service = new DocumentService(repository, new DocumentPolicy(), audit, transactions, renderer);
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("clinician"));
        // A present target is the default; rejection tests override it to false.
        when(repository.subjectExists(anyString(), anyString())).thenReturn(true);
        TenantContext.set(new TenantContext(ORG, ACTOR, "DOCUMENT_ADMIN", "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private RenderRequest render(String subjectType) {
        return new RenderRequest(subjectType, SUBJECT, "en", "Summary", List.of("Plan: rest"),
                "SYNTHETIC-LAB", "REQ-1", "2026-11-19T10:00:00Z", 0, null);
    }

    private static RenderedDocument rendered(String subjectType) {
        return new RenderedDocument(ID, 1, subjectType, SUBJECT, "en", "LTR", 1, 4L,
                "digest", "provenance", "now", ACTOR);
    }

    private void renderWillSucceed(String subjectType) {
        when(repository.nextRenderVersion(subjectType, SUBJECT, "en")).thenReturn(1);
        when(repository.findRendered(anyString())).thenReturn(Optional.of(rendered(subjectType)));
    }

    private ApiException rejectedRender(String subjectType) {
        return assertThrows(ApiException.class, () -> service.render(render(subjectType)));
    }

    private ApiException rejectedVersions(String subjectType) {
        return assertThrows(ApiException.class, () -> service.renderedVersions(subjectType, SUBJECT, "en"));
    }

    @Test
    void renderRejectsAnOrderWhoseTargetDoesNotExist() {
        when(repository.subjectExists("order", SUBJECT)).thenReturn(false);
        ApiException failure = rejectedRender("order");
        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
        verify(repository, never()).nextRenderVersion(anyString(), anyString(), anyString());
        verify(repository, never()).insertRendered(any(), any(), any(), any(), anyInt(), any(), any(),
                any(), any(), any(), any());
        verify(audit, never()).append(any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), eq("SUCCESS"));
    }

    @Test
    void renderRejectsAResultWhoseTargetDoesNotExist() {
        when(repository.subjectExists("result", SUBJECT)).thenReturn(false);
        ApiException failure = rejectedRender("result");
        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
        verify(repository, never()).insertRendered(any(), any(), any(), any(), anyInt(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    void renderStoresAnOrderRenderWhenTheTargetExists() {
        when(repository.subjectExists("order", SUBJECT)).thenReturn(true);
        renderWillSucceed("order");
        assertEquals(ID, service.render(render("order")).id());
        verify(repository).subjectExists("order", SUBJECT);
        verify(repository).insertRendered(eq(ORG), anyString(), eq("order"), eq(SUBJECT), eq(1),
                eq("en"), eq("LTR"), eq("SYNTHETIC-LAB"), eq("REQ-1"), eq("2026-11-19T10:00:00Z"),
                any(byte[].class));
    }

    @Test
    void renderStoresAResultRenderWhenTheTargetExists() {
        when(repository.subjectExists("result", SUBJECT)).thenReturn(true);
        renderWillSucceed("result");
        assertEquals(ID, service.render(render("result")).id());
        verify(repository).subjectExists("result", SUBJECT);
        verify(repository).insertRendered(eq(ORG), anyString(), eq("result"), eq(SUBJECT), eq(1),
                eq("en"), eq("LTR"), eq("SYNTHETIC-LAB"), eq("REQ-1"), eq("2026-11-19T10:00:00Z"),
                any(byte[].class));
    }

    @Test
    void renderedVersionsRejectsAnOrderWhoseTargetDoesNotExist() {
        when(repository.subjectExists("order", SUBJECT)).thenReturn(false);
        ApiException failure = rejectedVersions("order");
        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
        verify(repository, never()).renderedVersions(anyString(), anyString(), anyString());
        verify(audit, never()).append(any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), eq("SUCCESS"));
    }

    @Test
    void renderedVersionsRejectsAResultWhoseTargetDoesNotExist() {
        when(repository.subjectExists("result", SUBJECT)).thenReturn(false);
        ApiException failure = rejectedVersions("result");
        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
        verify(repository, never()).renderedVersions(anyString(), anyString(), anyString());
    }

    @Test
    void renderedVersionsRejectsAChartWhoseTargetDoesNotExist() {
        when(repository.subjectExists("chart", SUBJECT)).thenReturn(false);
        assertEquals(404, rejectedVersions("chart").status());
        verify(repository, never()).renderedVersions(anyString(), anyString(), anyString());
    }

    @Test
    void renderedVersionsListsAnOrderWhenTheTargetExists() {
        when(repository.subjectExists("order", SUBJECT)).thenReturn(true);
        when(repository.renderedVersions("order", SUBJECT, "en")).thenReturn(List.of(rendered("order")));
        assertEquals(1, service.renderedVersions("order", SUBJECT, "en").size());
        verify(repository).subjectExists("order", SUBJECT);
        verify(audit).append(any(), eq("DOCUMENT_READ"), eq("DOCUMENT_LIST"), eq("DOCUMENT"),
                eq("DOCUMENTS"), eq("LIST"), eq("SUCCESS"));
    }

    @Test
    void renderedVersionsListsAResultWhenTheTargetExists() {
        when(repository.subjectExists("result", SUBJECT)).thenReturn(true);
        when(repository.renderedVersions("result", SUBJECT, "en")).thenReturn(List.of(rendered("result")));
        assertEquals(1, service.renderedVersions("result", SUBJECT, "en").size());
        verify(repository).subjectExists("result", SUBJECT);
    }
}
