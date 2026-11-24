package com.aurevia.it.documents;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.aurevia.documents.DocumentPolicy;

/** S-G1-07 authorization: the smallest bounded document action matrix (IAM-01). */
class DocumentPolicyTest {

    private final DocumentPolicy policy = new DocumentPolicy();

    @Test
    void tenantAdminHasNoClinicalDocumentAccessByDefault() {
        assertFalse(policy.allows(DocumentPolicy.DOCUMENT_LINK, "tenant_admin"));
        assertFalse(policy.allows(DocumentPolicy.DOCUMENT_READ, "tenant_admin"));
        assertFalse(policy.allows(DocumentPolicy.DOCUMENT_RENDER, "tenant_admin"));
        assertFalse(policy.allows(DocumentPolicy.DOCUMENT_VOID, "tenant_admin"));
    }

    @Test
    void clinicianMayLinkReadRenderAndVoid() {
        assertTrue(policy.allows(DocumentPolicy.DOCUMENT_LINK, "clinician"));
        assertTrue(policy.allows(DocumentPolicy.DOCUMENT_READ, "clinician"));
        assertTrue(policy.allows(DocumentPolicy.DOCUMENT_RENDER, "clinician"));
        assertTrue(policy.allows(DocumentPolicy.DOCUMENT_VOID, "clinician"));
    }

    @Test
    void receptionMayLinkAndReadButNotRenderOrVoid() {
        assertTrue(policy.allows(DocumentPolicy.DOCUMENT_LINK, "reception"));
        assertTrue(policy.allows(DocumentPolicy.DOCUMENT_READ, "reception"));
        assertFalse(policy.allows(DocumentPolicy.DOCUMENT_RENDER, "reception"));
        assertFalse(policy.allows(DocumentPolicy.DOCUMENT_VOID, "reception"));
    }

    @Test
    void everyOtherRoleAndActionFailsClosed() {
        assertFalse(policy.allows(DocumentPolicy.DOCUMENT_READ, "privacy_auditor"));
        assertFalse(policy.allows(DocumentPolicy.DOCUMENT_READ, "patient"));
        assertFalse(policy.allows(DocumentPolicy.DOCUMENT_READ, null));
        assertFalse(policy.allows("UNKNOWN_ACTION", "clinician"));
        assertFalse(policy.allows(null, "clinician"));
    }
}
