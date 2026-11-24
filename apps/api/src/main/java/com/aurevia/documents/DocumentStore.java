package com.aurevia.documents;

import java.util.List;
import java.util.Optional;

import com.aurevia.documents.DocumentContract.Artifact;
import com.aurevia.documents.DocumentContract.DocumentAuthorization;
import com.aurevia.documents.DocumentContract.DocumentEvent;
import com.aurevia.documents.DocumentContract.PdfBinding;
import com.aurevia.documents.DocumentContract.PdfJob;
import com.aurevia.documents.DocumentContract.ReleaseDecision;

/**
 * Tenant-scoped persistence boundary for the X-03 document slice (ARC-06).
 *
 * <p>Every read takes the active organization explicitly so a caller cannot
 * reach another tenant's row by guessing a local identifier. State changes use
 * compare-and-set operations on the caller's read version, and release
 * insertion is atomic, so a competing command cannot overwrite a committed
 * artifact or create a second decision. The authoritative deployment binds
 * this boundary to PostgreSQL with forced row-level security; the synthetic
 * implementation is used by the fast unit tier.</p>
 */
public interface DocumentStore {

    /**
     * Read a server-seeded upload authorization.
     *
     * @param organizationId active organization
     * @param contextId      tenant-local context identifier
     * @return the grant when it exists in this tenant
     */
    Optional<DocumentAuthorization> authorization(String organizationId, String contextId);

    /**
     * Seed or replace a synthetic upload authorization.
     *
     * @param grant grant to store in its owning organization
     */
    void putAuthorization(DocumentAuthorization grant);

    /**
     * Seed a deliberately mis-bound grant under a chosen lookup key so the
     * synthetic tier can prove a service rejects a foreign grant (ARC-02).
     *
     * @param organizationId lookup organization
     * @param contextId      lookup context
     * @param grant          grant whose own organization may differ
     */
    void putAuthorizationFor(String organizationId, String contextId, DocumentAuthorization grant);

    /**
     * Read one artifact.
     *
     * @param organizationId active organization
     * @param id             tenant-local artifact identifier
     * @return the artifact when it exists in this tenant
     */
    Optional<Artifact> artifact(String organizationId, String id);

    /**
     * Insert or seed an artifact. Production state changes use the CAS method.
     *
     * @param artifact artifact to store in its owning organization
     */
    void putArtifact(Artifact artifact);

    /**
     * Atomically replace an artifact only if its version is unchanged.
     *
     * @param organizationId active organization
     * @param id             tenant-local artifact identifier
     * @param expectedVersion version read by the caller
     * @param next           replacement artifact
     * @return {@code true} when the replace committed
     */
    boolean replaceArtifactIfVersion(String organizationId, String id, int expectedVersion, Artifact next);

    /**
     * List every artifact owned by one organization.
     *
     * @param organizationId active organization
     * @return immutable snapshot of this tenant's artifacts
     */
    List<Artifact> artifacts(String organizationId);

    /**
     * Record a verified private object at an immutable version.
     *
     * @param organizationId active organization
     * @param objectKey      private object key
     * @param objectVersion  verified object version
     * @param checksum       expected object checksum
     * @param sizeBytes      object size
     */
    void putObject(String organizationId, String objectKey, int objectVersion,
                   String checksum, long sizeBytes);

    /**
     * Verify an object's key, version and checksum before it is completed or downloaded.
     *
     * @param organizationId active organization
     * @param objectKey      private object key
     * @param objectVersion  expected immutable version
     * @param checksum       expected checksum
     * @return {@code true} only for a matching verified object
     */
    boolean verifyObject(String organizationId, String objectKey, int objectVersion, String checksum);

    /**
     * Remove a verified object record, for example after a quarantine failure.
     *
     * @param organizationId active organization
     * @param objectKey      private object key
     * @param objectVersion  immutable object version
     */
    void removeObject(String organizationId, String objectKey, int objectVersion);

    /**
     * Read one PDF render job.
     *
     * @param organizationId active organization
     * @param jobId          tenant-local job identifier
     * @return the job when it exists in this tenant
     */
    Optional<PdfJob> pdfJob(String organizationId, String jobId);

    /**
     * List every render job owned by one organization.
     *
     * @param organizationId active organization
     * @return immutable snapshot of this tenant's jobs
     */
    List<PdfJob> pdfJobs(String organizationId);

    /**
     * Insert or seed a render job.
     *
     * @param job job to store in its owning organization
     */
    void putPdfJob(PdfJob job);

    /**
     * Atomically replace a render job only if its version is unchanged.
     *
     * @param organizationId active organization
     * @param jobId          tenant-local job identifier
     * @param expectedVersion version read by the caller
     * @param next           replacement job
     * @return {@code true} when the replace committed
     */
    boolean replacePdfJobIfVersion(String organizationId, String jobId, int expectedVersion, PdfJob next);

    /**
     * Read the binding for one revision, if any.
     *
     * @param organizationId active organization
     * @param revisionId     tenant-local revision identifier
     * @return the binding when it exists in this tenant
     */
    Optional<PdfBinding> pdfBinding(String organizationId, String revisionId);

    /**
     * Store an immutable PDF binding for one revision.
     *
     * @param binding binding to store
     */
    void putPdfBinding(PdfBinding binding);

    /**
     * Read an idempotent release decision.
     *
     * @param organizationId active organization
     * @param revisionId     released revision identifier
     * @param revisionVersion released revision version
     * @param policyVersion  applied policy version
     * @return the decision when it exists in this tenant
     */
    Optional<ReleaseDecision> release(String organizationId, String revisionId,
                                      int revisionVersion, String policyVersion);

    /**
     * List every release decision owned by one organization.
     *
     * @param organizationId active organization
     * @return immutable snapshot of this tenant's decisions
     */
    List<ReleaseDecision> releases(String organizationId);

    /**
     * Insert a release decision at most once for its key.
     *
     * @param decision decision to insert
     * @return {@code true} when this call inserted the decision
     */
    boolean insertReleaseIfAbsent(ReleaseDecision decision);

    /**
     * Append one immutable fact to the tenant's ledger.
     *
     * @param event fact to append
     */
    void appendEvent(DocumentEvent event);

    /**
     * Read every recorded fact for one organization in recorded order.
     *
     * @param organizationId active organization
     * @return immutable snapshot of this tenant's facts
     */
    List<DocumentEvent> events(String organizationId);
}
