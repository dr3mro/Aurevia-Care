package com.aurevia.messaging.attachments;

import java.util.List;
import java.util.Optional;

import com.aurevia.messaging.attachments.AttachmentContract.Attachment;
import com.aurevia.messaging.attachments.AttachmentContract.AttachmentEvent;
import com.aurevia.messaging.attachments.AttachmentContract.ConnectionGrant;

/**
 * Tenant-scoped persistence boundary for attachments.
 *
 * <p>Every method takes the active organization explicitly so a caller can
 * never read another tenant's row by guessing a local identifier. The
 * authoritative adapter is the JDBC implementation with forced row-level
 * security; in-memory fakes are test-only.</p>
 */
public interface AttachmentStore {

    /**
     * Read a verified connection grant by its tenant-local identifier.
     *
     * @param organizationId active organization
     * @param connectionId   tenant-local connection identifier
     * @return the grant when it exists in this tenant
     */
    Optional<ConnectionGrant> grant(String organizationId, String connectionId);

    /**
     * Seed or replace a synthetic connection grant.
     *
     * @param grant grant to store in its owning organization
     */
    void putGrant(ConnectionGrant grant);

    /**
     * Read one attachment by its tenant-local identifier.
     *
     * @param organizationId active organization
     * @param id             tenant-local attachment identifier
     * @return the attachment when it exists in this tenant
     */
    Optional<Attachment> attachment(String organizationId, String id);

    /**
     * Insert or replace one attachment in its owning organization.
     *
     * @param attachment attachment to store
     */
    void putAttachment(Attachment attachment);

    /**
     * List every attachment owned by one organization.
     *
     * @param organizationId active organization
     * @return immutable snapshot of this tenant's attachments
     */
    List<Attachment> attachments(String organizationId);

    /**
     * Append one immutable fact to the tenant's ledger.
     *
     * @param event fact to append
     */
    void appendEvent(AttachmentEvent event);

    /**
     * Read every recorded fact for one organization in recorded order.
     *
     * @param organizationId active organization
     * @return immutable snapshot of this tenant's facts
     */
    List<AttachmentEvent> events(String organizationId);
}
