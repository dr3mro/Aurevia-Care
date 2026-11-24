package com.aurevia.documents;

import com.aurevia.documents.DocumentModel.DocumentAssoc;
import com.aurevia.documents.DocumentModel.AssociationEvent;
import com.aurevia.documents.DocumentModel.DocumentAssocView;
import com.aurevia.documents.DocumentModel.RenderedDocument;
import com.aurevia.documents.DocumentModel.PdfSpec;
import com.aurevia.documents.DocumentModel.LinkRequest;
import com.aurevia.documents.DocumentModel.RenderRequest;
import com.aurevia.documents.DocumentModel.VerifiedContent;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.tenant.TenantContext;

/**
 * Original-document invariants for CLN-07.
 *
 * <p>Each command is one tenant-guarded transaction: membership and the document
 * action matrix are re-derived, the subject is resolved inside the active
 * tenant, the original or immutable render commits with its history and one
 * OPS-02 audit row. Byte retention and checksums are database-owned; rotation
 * never touches the original. Denials are audited before the failure leaves the
 * transaction so they survive.</p>
 */
@Service
public class DocumentService {

    private static final String READ_PURPOSE = "DOCUMENT_READ";
    private static final String RESOURCE_TYPE = "DOCUMENT";
    private static final String SCOPE = "DOCUMENTS";
    private static final String CHART = "chart";
    private static final String ACTIVE = "active";
    private static final String VOIDED = "voided";

    private final DocumentRepository repository;
    private final DocumentPolicy policy;
    private final AuditAppender audit;
    private final TransactionTemplate transactions;
    private final PdfRenderer renderer;

    /**
     * Create the service.
     *
     * @param repository   RLS-bounded document repository
     * @param policy       bounded document action matrix
     * @param audit        transactional audit appender
     * @param transactions tenant-guarded transaction template
     * @param renderer     deterministic PDF renderer
     */
    public DocumentService(DocumentRepository repository, DocumentPolicy policy, AuditAppender audit,
                           TransactionTemplate transactions, PdfRenderer renderer) {
        this.repository = repository;
        this.policy = policy;
        this.audit = audit;
        this.transactions = transactions;
        this.renderer = renderer;
    }

    /**
     * Attach an original PDF or image to a chart, order or result.
     *
     * @param request link command
     * @return the stored association without its bytes
     * @throws ApiException on invalid input, denial, an unknown chart, order or
     *                      result subject, or a conflict
     */
    public DocumentAssoc link(LinkRequest request) {
        String subjectType = DocumentInputs.subjectType(request.subjectType());
        String subjectId = DocumentInputs.uuid(request.subjectId(), "subjectId");
        String fileName = DocumentInputs.fileName(request.fileName());
        String mediaType = DocumentInputs.mediaType(request.mediaType());
        int rotation = DocumentInputs.rotation(request.rotationDegrees());
        byte[] content = DocumentInputs.decode(request.contentBase64());
        AtomicReference<ApiException> failure = new AtomicReference<>();
        DocumentAssoc created = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, DocumentPolicy.DOCUMENT_LINK, subjectId,
                    DocumentPolicy.DOCUMENT_LINK);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            if (CHART.equals(subjectType) && !repository.chartExists(subjectId)) {
                failure.set(new ApiException(404, "NOT_FOUND", "chart not found"));
                return null;
            }
            String id = DocumentInputs.newId();
            int written;
            try {
                written = repository.insert(context.organizationId(), id, subjectType, subjectId,
                        fileName, mediaType, content, rotation);
            } catch (DuplicateKeyException exception) {
                failure.set(new ApiException(409, "CONFLICT", "association already exists"));
                return null;
            }
            if (written == 0) {
                failure.set(new ApiException(404, "NOT_FOUND", "subject not found"));
                return null;
            }
            repository.appendEvent(id, "link", null);
            audit.append(context, DocumentPolicy.DOCUMENT_LINK, DocumentPolicy.DOCUMENT_LINK,
                    RESOURCE_TYPE, SCOPE, id, "SUCCESS");
            return repository.findAssociation(id).orElseThrow();
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return created;
    }

    /**
     * Read an association with its append-only history.
     *
     * @param id opaque association identifier
     * @return the association and its history
     * @throws ApiException on invalid input, denial or a concealed resource
     */
    public DocumentAssocView association(String id) {
        DocumentInputs.uuid(id, "id");
        AtomicReference<ApiException> failure = new AtomicReference<>();
        DocumentAssocView view = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, DocumentPolicy.DOCUMENT_READ, id, READ_PURPOSE);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            DocumentAssoc association = repository.findAssociation(id).orElse(null);
            if (association == null) {
                audit.append(context, READ_PURPOSE, DocumentPolicy.DOCUMENT_READ, RESOURCE_TYPE, SCOPE,
                        id, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "association not found"));
                return null;
            }
            List<AssociationEvent> history = repository.associationHistory(id);
            audit.append(context, READ_PURPOSE, DocumentPolicy.DOCUMENT_READ, RESOURCE_TYPE, SCOPE,
                    id, "SUCCESS");
            return new DocumentAssocView(association, history);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return view;
    }

    /**
     * Download the retained original bytes after re-verifying their SHA-256.
     *
     * <p>The database recomputes the digest from the exact stored bytes and
     * reports whether they still match the digest captured on upload (CLN-07,
     * ARC-06). A mismatch means the retained original was altered: its bytes are
     * never served, the failure is audited with a tamper outcome and surfaced as
     * a conflict. The verification result is returned rather than raised as a
     * SQL error, so the transaction stays usable for the audit and the 409.
     * Rotation is presentation metadata and never changes these bytes. Voiding
     * does not remove the original: the history and bytes remain available to an
     * authorized reader.</p>
     *
     * @param id opaque association identifier
     * @return the verified original bytes
     * @throws ApiException on invalid input, denial, a concealed or missing
     *                      association, or a checksum mismatch
     */
    public byte[] associationContent(String id) {
        DocumentInputs.uuid(id, "id");
        AtomicReference<ApiException> failure = new AtomicReference<>();
        byte[] content = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, DocumentPolicy.DOCUMENT_READ, id, READ_PURPOSE);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            if (repository.findAssociation(id).isEmpty()) {
                audit.append(context, READ_PURPOSE, "DOCUMENT_CONTENT", RESOURCE_TYPE, SCOPE, id, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "association not found"));
                return null;
            }
            VerifiedContent verified = repository.verifiedAssociationContent(id).orElse(null);
            if (verified == null) {
                audit.append(context, READ_PURPOSE, "DOCUMENT_CONTENT", RESOURCE_TYPE, SCOPE, id, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "association content not found"));
                return null;
            }
            if (!verified.intact()) {
                audit.append(context, READ_PURPOSE, "DOCUMENT_CONTENT", RESOURCE_TYPE, SCOPE, id, "TAMPERED");
                failure.set(new ApiException(409, "CHECKSUM_MISMATCH",
                        "retained original failed checksum verification"));
                return null;
            }
            audit.append(context, READ_PURPOSE, "DOCUMENT_CONTENT", RESOURCE_TYPE, SCOPE, id, "SUCCESS");
            return verified.content();
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return content;
    }

    /**
     * Void an association, preserving the original and its history.
     *
     * @param id              opaque association identifier
     * @param expectedVersion version read by the caller
     * @param reason          recorded reason
     * @return the voided association
     * @throws ApiException on invalid input, denial, concealment, stale version or illegal transition
     */
    public DocumentAssoc voidAssociation(String id, int expectedVersion, String reason) {
        DocumentInputs.uuid(id, "id");
        String recordedReason = DocumentInputs.reason(reason);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        DocumentAssoc voided = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, DocumentPolicy.DOCUMENT_VOID, id,
                    DocumentPolicy.DOCUMENT_VOID);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            DocumentAssoc current = repository.findAssociation(id).orElse(null);
            if (current == null) {
                audit.append(context, DocumentPolicy.DOCUMENT_VOID, DocumentPolicy.DOCUMENT_VOID,
                        RESOURCE_TYPE, SCOPE, id, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "association not found"));
                return null;
            }
            if (VOIDED.equals(current.status())) {
                failure.set(new ApiException(409, "INVALID_TRANSITION", "association is already voided"));
                return null;
            }
            if (current.version() != expectedVersion) {
                failure.set(staleVersion());
                return null;
            }
            if (repository.voidAssociation(id, expectedVersion, recordedReason) == 0) {
                failure.set(staleVersion());
                return null;
            }
            repository.appendEvent(id, "void", recordedReason);
            audit.append(context, DocumentPolicy.DOCUMENT_VOID, DocumentPolicy.DOCUMENT_VOID,
                    RESOURCE_TYPE, SCOPE, id, "SUCCESS");
            return repository.findAssociation(id).orElseThrow();
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return voided;
    }

    /**
     * Render an immutable, versioned, localized PDF.
     *
     * @param request render command
     * @return metadata of the stored render
     * @throws ApiException on invalid input, denial, an unknown chart, order or
     *                      result subject, or a conflict
     */
    public RenderedDocument render(RenderRequest request) {
        String subjectType = DocumentInputs.subjectType(request.subjectType());
        String subjectId = DocumentInputs.uuid(request.subjectId(), "subjectId");
        String locale = DocumentInputs.locale(request.locale());
        String title = DocumentInputs.bounded(request.title(), "TITLE", 1, 200);
        List<String> lines = DocumentInputs.lines(request.lines());
        String sourceSystem = DocumentInputs.bounded(request.sourceSystem(), "SOURCE_SYSTEM", 1, 64);
        String sourceId = DocumentInputs.bounded(request.sourceId(), "SOURCE_ID", 1, 64);
        String effectiveAt = DocumentInputs.bounded(request.effectiveAt(), "EFFECTIVE_AT", 1, 64);
        int rotation = DocumentInputs.rotation(request.rotationDegrees());
        String sourceAssociationId = request.sourceAssociationId() == null
                ? null : DocumentInputs.uuid(request.sourceAssociationId(), "sourceAssociationId");

        AtomicReference<ApiException> failure = new AtomicReference<>();
        RenderedDocument rendered = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, DocumentPolicy.DOCUMENT_RENDER, subjectId,
                    DocumentPolicy.DOCUMENT_RENDER);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            if (CHART.equals(subjectType) && !repository.chartExists(subjectId)) {
                failure.set(new ApiException(404, "NOT_FOUND", "chart not found"));
                return null;
            }
            if (!CHART.equals(subjectType) && !repository.subjectExists(subjectType, subjectId)) {
                failure.set(new ApiException(404, "NOT_FOUND", "subject not found"));
                return null;
            }
            if (sourceAssociationId != null) {
                DocumentAssoc source = repository.findAssociation(sourceAssociationId).orElse(null);
                if (source == null) {
                    failure.set(new ApiException(404, "NOT_FOUND", "source association not found"));
                    return null;
                }
                if (VOIDED.equals(source.status())) {
                    failure.set(new ApiException(409, "INVALID_SOURCE", "source association is voided"));
                    return null;
                }
                if (!source.subjectType().equals(subjectType)
                        || !source.subjectId().equals(subjectId)) {
                    failure.set(new ApiException(409, "INVALID_SOURCE",
                            "source association belongs to a different subject"));
                    return null;
                }
            }
            int version = repository.nextRenderVersion(subjectType, subjectId, locale);
            PdfSpec spec = new PdfSpec(context.organizationId(), subjectType, subjectId, locale,
                    version, rotation, title, lines, sourceSystem, sourceId, effectiveAt,
                    sourceAssociationId);
            byte[] bytes = renderer.render(spec);
            String id = DocumentInputs.newId();
            try {
                repository.insertRendered(context.organizationId(), id, subjectType, subjectId, version,
                        locale, PdfRenderer.direction(locale), sourceSystem, sourceId, effectiveAt, bytes);
            } catch (DuplicateKeyException exception) {
                failure.set(new ApiException(409, "CONFLICT", "render version already exists"));
                return null;
            }
            audit.append(context, DocumentPolicy.DOCUMENT_RENDER, DocumentPolicy.DOCUMENT_RENDER,
                    RESOURCE_TYPE, SCOPE, id, "SUCCESS");
            return repository.findRendered(id).orElseThrow();
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return rendered;
    }

    /**
     * Read metadata for one rendered revision.
     *
     * @param id opaque render identifier
     * @return the render metadata
     * @throws ApiException on invalid input, denial or concealment
     */
    public RenderedDocument rendered(String id) {
        DocumentInputs.uuid(id, "id");
        AtomicReference<ApiException> failure = new AtomicReference<>();
        RenderedDocument result = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, DocumentPolicy.DOCUMENT_READ, id, READ_PURPOSE);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            RenderedDocument found = repository.findRendered(id).orElse(null);
            if (found == null) {
                audit.append(context, READ_PURPOSE, DocumentPolicy.DOCUMENT_READ, RESOURCE_TYPE, SCOPE,
                        id, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "render not found"));
                return null;
            }
            audit.append(context, READ_PURPOSE, DocumentPolicy.DOCUMENT_READ, RESOURCE_TYPE, SCOPE,
                    id, "SUCCESS");
            return found;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    /**
     * Download the immutable bytes of one rendered revision.
     *
     * @param id opaque render identifier
     * @return the PDF bytes
     * @throws ApiException on invalid input, denial or concealment
     */
    public byte[] renderedContent(String id) {
        DocumentInputs.uuid(id, "id");
        AtomicReference<ApiException> failure = new AtomicReference<>();
        byte[] content = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, DocumentPolicy.DOCUMENT_READ, id, READ_PURPOSE);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            byte[] bytes = repository.renderedContent(id).orElse(null);
            if (bytes == null) {
                audit.append(context, READ_PURPOSE, DocumentPolicy.DOCUMENT_READ, RESOURCE_TYPE, SCOPE,
                        id, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "render not found"));
                return null;
            }
            audit.append(context, READ_PURPOSE, DocumentPolicy.DOCUMENT_READ, RESOURCE_TYPE, SCOPE,
                    id, "SUCCESS");
            return bytes;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return content;
    }

    /**
     * List every rendered version of a subject and locale.
     *
     * @param subjectType chart, order or result
     * @param subjectId   opaque target identifier
     * @param locale      en or ar
     * @return render metadata in version order
     * @throws ApiException on invalid input, denial or a concealed subject
     */
    public List<RenderedDocument> renderedVersions(String subjectType, String subjectId, String locale) {
        DocumentInputs.subjectType(subjectType);
        DocumentInputs.uuid(subjectId, "subjectId");
        DocumentInputs.locale(locale);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        List<RenderedDocument> versions = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, DocumentPolicy.DOCUMENT_READ, "LIST", READ_PURPOSE);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            if (!repository.subjectExists(subjectType, subjectId)) {
                failure.set(new ApiException(404, "NOT_FOUND", "subject not found"));
                return null;
            }
            List<RenderedDocument> found = repository.renderedVersions(subjectType, subjectId, locale);
            audit.append(context, READ_PURPOSE, "DOCUMENT_LIST", RESOURCE_TYPE, SCOPE, "LIST", "SUCCESS");
            return found;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return versions;
    }

    private ApiException staleVersion() {
        return new ApiException(412, "STALE_VERSION", "the association changed since it was read");
    }

    private ApiException authorize(TenantContext context, String action, String resourceId, String purpose) {
        if (!repository.hasActiveMembership(context.subject())) {
            audit.append(context, purpose, action, RESOURCE_TYPE, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        boolean allowed = repository.activeRoles(context.subject()).stream()
                .anyMatch(role -> policy.allows(action, role));
        if (!allowed) {
            audit.append(context, purpose, action, RESOURCE_TYPE, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "missing document permission");
        }
        return null;
    }
}
