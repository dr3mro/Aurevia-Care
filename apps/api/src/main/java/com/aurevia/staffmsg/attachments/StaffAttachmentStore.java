package com.aurevia.staffmsg.attachments;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.aurevia.staffmsg.attachments.StaffAttachmentContract.AttachmentAudit;
import com.aurevia.staffmsg.attachments.StaffAttachmentContract.StaffAttachment;

/**
 * Tenant-scoped persistence boundary for staff-message attachments (MSG-03).
 *
 * <p>Every method takes the active organization explicitly so a caller can never
 * reach another tenant's row by guessing a local identifier. The authoritative
 * deployment stores links in the tenant-isolated staff-attachment table and
 * original bytes in the private object store; the synthetic tier uses an
 * in-memory implementation kept under {@code src/test}.</p>
 */
public interface StaffAttachmentStore {

    /**
     * Read one attachment by its tenant-local identifier.
     *
     * @param organizationId active organization
     * @param id             tenant-local attachment identifier
     * @return the attachment when it exists in this tenant
     */
    Optional<StaffAttachment> attachment(String organizationId, String id);

    /**
     * List every attachment bound to one draft.
     *
     * @param organizationId active organization
     * @param draftId        conversation or mail-draft identifier
     * @return immutable snapshot in this tenant
     */
    List<StaffAttachment> attachmentsForDraft(String organizationId, String draftId);

    /**
     * Insert or replace one attachment in its owning organization.
     *
     * @param attachment attachment to store
     */
    void putAttachment(StaffAttachment attachment);

    /**
     * Bind one pending attachment to a published message with a conditional write.
     *
     * <p>The transition is atomic so two senders racing on one idempotency key
     * cannot both bind the same file: only the caller that changed the row should
     * record a SENT fact. A file already bound to this message is a replay and
     * returns {@code false}; a file bound to another message is refused.</p>
     *
     * @param attachment pending attachment carrying the target message identifier
     * @return {@code true} when this call performed the transition
     * @throws ApiException when the file is bound to a different message
     */
    boolean bindToMessage(StaffAttachment attachment);

    /**
     * Count the attachments bound to one draft.
     *
     * @param organizationId active organization
     * @param draftId        conversation or mail-draft identifier
     * @return number of bound files
     */
    int countForDraft(String organizationId, String draftId);

    /**
     * Decide whether a subject is a recipient of a published message.
     *
     * @param organizationId active organization
     * @param messageId      published message identifier
     * @param subject        authenticated subject
     * @return {@code true} only for a message recipient
     */
    boolean isRecipient(String organizationId, String messageId, String subject);

    /**
     * Record the audience frozen on a published message so the synthetic tier
     * can authorize downloads without a database. The durable tier resolves the
     * same audience from persisted message data at read time and needs no write.
     *
     * @param organizationId active organization
     * @param messageId      published message identifier
     * @param subjects       recipient subjects authorized to download
     */
    void grantRecipients(String organizationId, String messageId, Set<String> subjects);

    /**
     * Store the original bytes of one private object.
     *
     * @param organizationId active organization
     * @param objectKey      private object key
     * @param content        original payload bytes
     */
    void putBytes(String organizationId, String objectKey, byte[] content);

    /**
     * Read original bytes only when the private object is present and its bytes
     * still match the expected checksum.
     *
     * @param organizationId active organization
     * @param objectKey      private object key
     * @param checksum       expected original checksum
     * @return the bytes, or empty when the object is absent or fails verification
     */
    Optional<byte[]> bytes(String organizationId, String objectKey, String checksum);

    /**
     * Append one immutable audit fact.
     *
     * @param audit fact to append
     */
    void appendAudit(AttachmentAudit audit);

    /**
     * Read every audit fact for one organization in recorded order.
     *
     * @param organizationId active organization
     * @return immutable snapshot of this tenant's facts
     */
    List<AttachmentAudit> audits(String organizationId);
}
