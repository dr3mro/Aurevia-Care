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
 * CLN-07 subject-existence gate on {@link DocumentService#link}.
 *
 * <p>An original must not be attached to a chart, order or result that does not
 * exist in the active tenant, otherwise an orphan clinical artifact is created.
 * All subjects and content here are synthetic and the repository is a Mockito
 * mock, so no database or real patient data is involved.</p>
 */
class DocumentServiceSubjectLinkTest {

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
        repositoryHasActiveMembership();
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("clinician"));
        when(repository.findAssociation(anyString())).thenReturn(Optional.of(assoc()));
        TenantContext.set(new TenantContext(ORG, ACTOR, "DOCUMENT_ADMIN", "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void repositoryHasActiveMembership() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
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

    @Test
    void linkRejectsAnOrderWhoseTargetDoesNotExist() {
        insertReturns(0);
        ApiException failure = rejectedLink("order");
        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
        verify(repository, never()).appendEvent(anyString(), anyString(), any());
        verify(audit, never()).append(any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), eq("SUCCESS"));
    }

    @Test
    void linkRejectsAResultWhoseTargetDoesNotExist() {
        insertReturns(0);
        ApiException failure = rejectedLink("result");
        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
        verify(repository, never()).appendEvent(anyString(), anyString(), any());
    }

    @Test
    void linkRejectsAChartWhoseInsertFindsNoEffectiveRecord() {
        when(repository.chartExists(SUBJECT)).thenReturn(true);
        insertReturns(0);
        assertEquals(404, rejectedLink("chart").status());
        verify(repository, never()).appendEvent(anyString(), anyString(), any());
    }

    @Test
    void linkStoresAnAssociationWhenAnOrderTargetExists() {
        insertReturns(1);
        assertEquals(ID, service.link(link("order")).id());
        verify(repository).appendEvent(anyString(), eq("link"), eq(null));
        verify(audit).append(any(), eq("DOCUMENT_LINK"), eq("DOCUMENT_LINK"), eq("DOCUMENT"),
                eq("DOCUMENTS"), anyString(), eq("SUCCESS"));
    }

    @Test
    void linkStoresAnAssociationWhenAResultTargetExists() {
        insertReturns(1);
        assertEquals(ID, service.link(link("result")).id());
        verify(repository).appendEvent(anyString(), eq("link"), eq(null));
    }

    @Test
    void linkStillConcealsAnUnknownChartBeforeInserting() {
        when(repository.chartExists(SUBJECT)).thenReturn(false);
        assertEquals(404, rejectedLink("chart").status());
        verify(repository, never()).insert(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(byte[].class), anyInt());
    }

}
