package com.aurevia.documents.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
import com.aurevia.documents.DocumentModel.DocumentAssoc;
import com.aurevia.documents.DocumentModel.LinkRequest;
import com.aurevia.documents.DocumentPolicy;
import com.aurevia.documents.DocumentRepository;
import com.aurevia.documents.DocumentService;
import com.aurevia.documents.PdfRenderer;
import com.aurevia.tenant.TenantContext;

/**
 * CLN-07 clause 1: an authorized actor may attach an original PDF or image to a
 * chart, an order or a result, and an unauthorized actor is refused.
 *
 * <p>The repository is a Mockito mock and the transaction template invokes its
 * callback directly, so the tenant/role decision in {@code DocumentService.authorize}
 * and the chart/subject gates in {@code DocumentService.link} are exercised without
 * a database. Every identifier and byte string here is synthetic. The four
 * positives prove the attachment and its history/audit; the two authorization
 * denials and the unknown-chart denial prove what the rule must refuse. No
 * production code is changed by this proof.</p>
 */
class LinkOriginalPdfsImagesChartOrderResultTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "clinician-a";
    private static final String ID = "33333333-3333-3333-3333-333333333333";
    private static final String SUBJECT = "22222222-2222-2222-2222-222222222222";
    private static final byte[] CONTENT = "synthetic original".getBytes(StandardCharsets.UTF_8);
    private static final String BASE64 = Base64.getEncoder().encodeToString(CONTENT);

    private DocumentRepository repository;
    private AuditAppender audit;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        repository = mock(DocumentRepository.class);
        audit = mock(AuditAppender.class);
        PdfRenderer renderer = mock(PdfRenderer.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        service = new DocumentService(repository, new DocumentPolicy(), audit, transactions, renderer);
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("clinician"));
        when(repository.findAssociation(anyString())).thenReturn(Optional.of(assoc()));
        TenantContext.set(new TenantContext(ORG, ACTOR, "DOCUMENT_ADMIN", "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private LinkRequest link(String subjectType) {
        return new LinkRequest(subjectType, SUBJECT, "scan.pdf", "application/pdf", BASE64, 0);
    }

    private static DocumentAssoc assoc() {
        return new DocumentAssoc(ID, 1, "chart", SUBJECT, "scan.pdf", "application/pdf",
                CONTENT.length, "digest", 0, "active", null, ACTOR, "2026-11-19T10:00:00Z", null);
    }

    private void insertReturns(int rows) {
        when(repository.insert(eq(ORG), anyString(), anyString(), eq(SUBJECT),
                anyString(), anyString(), any(byte[].class), anyInt())).thenReturn(rows);
    }

    private ApiException rejectedLink(String subjectType) {
        return assertThrows(ApiException.class, () -> service.link(link(subjectType)));
    }

    private void verifyNoAssociationWasWritten() {
        verify(repository, never()).insert(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(byte[].class), anyInt());
        verify(repository, never()).appendEvent(anyString(), anyString(), any());
        verify(audit, never()).append(any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), eq("SUCCESS"));
    }

    private void verifyDeniedAudit() {
        verify(audit).append(eq(TenantContext.current()), eq("DOCUMENT_LINK"), eq("DOCUMENT_LINK"),
                eq("DOCUMENT"), eq("DOCUMENTS"), eq(SUBJECT), eq("DENIED"));
    }

    @Test
    void linkAttachesOriginalToChartWhenActorIsClinician() {
        when(repository.chartExists(SUBJECT)).thenReturn(true);
        insertReturns(1);
        assertEquals(ID, service.link(link("chart")).id());
        verify(repository).appendEvent(anyString(), eq("link"), eq(null));
        verify(audit).append(eq(TenantContext.current()), eq("DOCUMENT_LINK"), eq("DOCUMENT_LINK"),
                eq("DOCUMENT"), eq("DOCUMENTS"), anyString(), eq("SUCCESS"));
    }

    @Test
    void linkAttachesOriginalPngImageToChart() {
        when(repository.chartExists(SUBJECT)).thenReturn(true);
        insertReturns(1);
        LinkRequest png = new LinkRequest("chart", SUBJECT, "scan.png", "image/png", BASE64, 0);
        assertEquals(ID, service.link(png).id());
        // The bounded media type must reach the store unchanged, so an image is retained as an image.
        verify(repository).insert(eq(ORG), anyString(), eq("chart"), eq(SUBJECT),
                eq("scan.png"), eq("image/png"), any(byte[].class), anyInt());
        verify(repository).appendEvent(anyString(), eq("link"), eq(null));
    }

    @Test
    void linkAttachesOriginalToOrderAndToResult() {
        insertReturns(1);
        assertEquals(ID, service.link(link("order")).id());
        assertEquals(ID, service.link(link("result")).id());
        verify(repository).insert(eq(ORG), anyString(), eq("order"), eq(SUBJECT),
                anyString(), anyString(), any(byte[].class), anyInt());
        verify(repository).insert(eq(ORG), anyString(), eq("result"), eq(SUBJECT),
                anyString(), anyString(), any(byte[].class), anyInt());
        verify(repository, times(2)).appendEvent(anyString(), eq("link"), eq(null));
    }

    @Test
    void linkRefusesActorWithNoActiveMembership() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        ApiException failure = rejectedLink("chart");
        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        assertEquals("no active membership", failure.getMessage());
        verifyDeniedAudit();
        verifyNoAssociationWasWritten();
    }

    @Test
    void linkRefusesRoleWithoutLinkPermission() {
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("patient"));
        ApiException failure = rejectedLink("order");
        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        assertEquals("missing document permission", failure.getMessage());
        verifyDeniedAudit();
        verifyNoAssociationWasWritten();
    }

    @Test
    void linkRefusesUnknownChart() {
        when(repository.chartExists(SUBJECT)).thenReturn(false);
        ApiException failure = rejectedLink("chart");
        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
        verifyNoAssociationWasWritten();
    }
}
