package com.aurevia.smartclinic.staffmsg.attachments;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.AttachmentAudit;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.StaffAttachment;

/**
 * Thread-safe, synthetic implementation of {@code StaffAttachmentStore} for the
 * fast unit tier. Keys embed the organization so two tenants can use identical
 * local identifiers without ever sharing a row.
 */
public final class InMemoryStaffAttachmentStore implements StaffAttachmentStore {

    private final ConcurrentHashMap<String, StaffAttachment> attachments = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, byte[]> bytes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> recipients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<AttachmentAudit>> audits =
            new ConcurrentHashMap<>();

    /** Create an empty store. */
    public InMemoryStaffAttachmentStore() {
    }

    @Override
    public Optional<StaffAttachment> attachment(String organizationId, String id) {
        return Optional.ofNullable(attachments.get(key(organizationId, id)));
    }

    @Override
    public List<StaffAttachment> attachmentsForDraft(String organizationId, String draftId) {
        return attachments.values().stream()
                .filter(attachment -> attachment.organizationId().equals(organizationId)
                        && attachment.draftId().equals(draftId))
                .toList();
    }

    @Override
    public void putAttachment(StaffAttachment attachment) {
        attachments.put(key(attachment.organizationId(), attachment.id()), attachment);
    }

    @Override
    public int countForDraft(String organizationId, String draftId) {
        return attachmentsForDraft(organizationId, draftId).size();
    }

    @Override
    public boolean isRecipient(String organizationId, String messageId, String subject) {
        return recipients.getOrDefault(key(organizationId, messageId), Set.of()).contains(subject);
    }

    /**
     * Seed the recipients allowed to download a published message (synthetic only).
     *
     * @param organizationId owning organization
     * @param messageId      published message identifier
     * @param subjects       allowed recipient subjects
     */
    public void putRecipients(String organizationId, String messageId, Set<String> subjects) {
        recipients.put(key(organizationId, messageId), Set.copyOf(subjects));
    }

    @Override
    public void putBytes(String organizationId, String objectKey, byte[] content) {
        bytes.put(key(organizationId, objectKey), content.clone());
    }

    @Override
    public Optional<byte[]> bytes(String organizationId, String objectKey, String checksum) {
        byte[] content = bytes.get(key(organizationId, objectKey));
        if (content == null) {
            return Optional.empty();
        }
        byte[] clone = content.clone();
        return StaffAttachmentInputs.checksum(clone).equals(checksum)
                ? Optional.of(clone) : Optional.empty();
    }

    @Override
    public void appendAudit(AttachmentAudit audit) {
        audits.computeIfAbsent(audit.organizationId(), ignored -> new CopyOnWriteArrayList<>()).add(audit);
    }

    @Override
    public List<AttachmentAudit> audits(String organizationId) {
        return List.copyOf(audits.getOrDefault(organizationId, new CopyOnWriteArrayList<>()));
    }

    private static String key(String organizationId, String id) {
        return organizationId + "|" + id;
    }
}
