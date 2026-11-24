package com.aurevia.documents;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
import com.aurevia.documents.DocumentModel.VerifiedContent;
import com.aurevia.documents.DocumentPolicy;
import com.aurevia.documents.DocumentRepository;
import com.aurevia.documents.DocumentService;
import com.aurevia.documents.PdfRenderer;
import com.aurevia.tenant.TenantContext;

/**
 * CLN-07 verified download: the retained original is returned only after its
 * stored SHA-256 has been re-checked, a void keeps the original, and another
 * tenant cannot read it. All fixtures are synthetic and the repository is a
 * Mockito mock, so no database or real patient data is involved.
 */
class DocumentVerifiedDownloadTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String ACTOR = "clinician-a";
    private static final String OTHER_ACTOR = "clinician-b";
    private static final String ID = "33333333-3333-3333-3333-333333333333";
    private static final String SUBJECT = "44444444-4444-4444-4444-444444444444";
    private static final byte[] CONTENT = "%PDF-1.4 synthetic original".getBytes(StandardCharsets.UTF_8);
    private static final String DIGEST = sha256(CONTENT);

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
        when(repository.findAssociation(ID)).thenReturn(Optional.of(assoc("active", 1, null)));
        when(repository.verifiedAssociationContent(ID))
                .thenReturn(Optional.of(new VerifiedContent(CONTENT, true)));
        TenantContext.set(new TenantContext(ORG, ACTOR, "DOCUMENT_READ", "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static DocumentAssoc assoc(String status, int version, String reason) {
        return new DocumentAssoc(ID, version, "chart", SUBJECT, "scan.pdf", "application/pdf",
                CONTENT.length, DIGEST, 90, status, reason, ACTOR, "2026-11-19T10:00:00Z", null);
    }

    private static int status(ApiException exception) {
        return exception.status();
    }

    @Test
    void downloadRejectsAnInvalidIdBeforeAnyStoreAccess() {
        assertEquals(422, status(assertThrows(ApiException.class, () -> service.associationContent("bad"))));
        verify(repository, never()).verifiedAssociationContent(anyString());
    }

    @Test
    void downloadDeniesInactiveMembershipAndAudits() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, status(assertThrows(ApiException.class, () -> service.associationContent(ID))));
        verify(audit).append(eq(TenantContext.current()), eq("DOCUMENT_READ"), eq("DOCUMENT_READ"),
                eq("DOCUMENT"), eq("DOCUMENTS"), eq(ID), eq("DENIED"));
        verify(repository, never()).verifiedAssociationContent(anyString());
    }

    @Test
    void downloadDeniesAMissingPermissionAndAudits() {
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("privacy_auditor"));
        assertEquals(403, status(assertThrows(ApiException.class, () -> service.associationContent(ID))));
        verify(audit).append(any(), eq("DOCUMENT_READ"), anyString(), anyString(), anyString(),
                eq(ID), eq("DENIED"));
        verify(repository, never()).verifiedAssociationContent(anyString());
    }

    @Test
    void downloadConcealsAMissingAssociation() {
        when(repository.findAssociation(ID)).thenReturn(Optional.empty());
        assertEquals(404, status(assertThrows(ApiException.class, () -> service.associationContent(ID))));
        verify(audit).append(any(), eq("DOCUMENT_READ"), eq("DOCUMENT_CONTENT"), anyString(),
                anyString(), eq(ID), eq("NOT_FOUND"));
        verify(repository, never()).verifiedAssociationContent(anyString());
    }

    @Test
    void downloadDetectsTamperedRetainedBytes() {
        // The retained bytes are present but flagged: no SQL error aborts the transaction.
        when(repository.verifiedAssociationContent(ID))
                .thenReturn(Optional.of(new VerifiedContent(CONTENT, false)));
        ApiException failure = assertThrows(ApiException.class, () -> service.associationContent(ID));
        assertEquals(409, failure.status());
        assertEquals("CHECKSUM_MISMATCH", failure.code());
        verify(audit).append(any(), eq("DOCUMENT_READ"), eq("DOCUMENT_CONTENT"), anyString(),
                anyString(), eq(ID), eq("TAMPERED"));
    }

    @Test
    void downloadConcealsMissingContent() {
        when(repository.verifiedAssociationContent(ID)).thenReturn(Optional.empty());
        assertEquals(404, status(assertThrows(ApiException.class, () -> service.associationContent(ID))));
        verify(audit).append(any(), eq("DOCUMENT_READ"), eq("DOCUMENT_CONTENT"), anyString(),
                anyString(), eq(ID), eq("NOT_FOUND"));
    }

    @Test
    void downloadReturnsTheVerifiedOriginalBytes() {
        assertArrayEquals(CONTENT, service.associationContent(ID));
        verify(repository).verifiedAssociationContent(ID);
        verify(audit).append(any(), eq("DOCUMENT_READ"), eq("DOCUMENT_CONTENT"), anyString(),
                anyString(), eq(ID), eq("SUCCESS"));
    }

    @Test
    void voidKeepsTheOriginalBytesAndItsHistory() {
        when(repository.voidAssociation(ID, 1, "wrong chart")).thenReturn(1);
        when(repository.findAssociation(ID))
                .thenReturn(Optional.of(assoc("active", 1, null)))
                .thenReturn(Optional.of(assoc("voided", 2, "wrong chart")));
        DocumentAssoc voided = service.voidAssociation(ID, 1, "wrong chart");
        assertEquals("voided", voided.status());
        assertEquals(DIGEST, voided.sha256());
        assertEquals(CONTENT.length, voided.byteSize());

        assertArrayEquals(CONTENT, service.associationContent(ID));
        verify(repository).appendEvent(ID, "void", "wrong chart");
        verify(repository).verifiedAssociationContent(ID);
    }

    @Test
    void anotherTenantCannotReadTheAssociation() {
        TenantContext.set(new TenantContext(OTHER_ORG, OTHER_ACTOR, "DOCUMENT_READ", "corr-2"));
        when(repository.hasActiveMembership(OTHER_ACTOR)).thenReturn(true);
        when(repository.activeRoles(OTHER_ACTOR)).thenReturn(List.of("clinician"));
        // Forced RLS on document_association returns no row for the other tenant.
        when(repository.findAssociation(ID)).thenReturn(Optional.empty());

        assertEquals(404, status(assertThrows(ApiException.class, () -> service.associationContent(ID))));
        verify(repository, never()).verifiedAssociationContent(anyString());
        verify(audit).append(any(), eq("DOCUMENT_READ"), eq("DOCUMENT_CONTENT"), anyString(),
                anyString(), eq(ID), eq("NOT_FOUND"));
    }
}
