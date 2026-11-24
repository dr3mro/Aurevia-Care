package com.aurevia.smartclinic.staffmsg.attachments;

import java.util.List;
import java.util.Set;

import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.staff.StaffRepository;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.StaffActor;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.StaffAttachment;

/**
 * Publishes a draft's pending files through the existing attachment policy when
 * a staff mail or instant message is sent (MSG-03, CLN-07).
 *
 * <p>Roles are re-derived from active staff membership inside the tenant-guarded
 * transaction on every phase, so a revoked role cannot widen the audience
 * between the preflight and the publication. The publisher never invents an
 * attachment identifier: it reads the files already bound to the draft by the
 * upload boundary and passes the message store's real identifier to the send
 * policy. A draft with no unsent files produces no send and no audit fact.</p>
 */
public class StaffAttachmentPublicationService implements StaffAttachmentPublication {

    private final StaffAttachmentService attachments;
    private final StaffRepository staff;
    private final TransactionTemplate transactions;

    /**
     * Create the publisher over the attachment policy and a fresh role source.
     *
     * @param attachments attachment domain service
     * @param staff       authoritative active-membership role source
     * @param transactions tenant-guarded transaction template for role lookups
     */
    public StaffAttachmentPublicationService(StaffAttachmentService attachments, StaffRepository staff,
                                    TransactionTemplate transactions) {
        this.attachments = attachments;
        this.staff = staff;
        this.transactions = transactions;
    }

    @Override
    public List<String> requirePublishable(String organizationId, String subject, String draftId) {
        StaffActor actor = actor(organizationId, subject);
        return attachments.requirePublishable(actor, draftId).stream()
                .map(StaffAttachment::id)
                .toList();
    }

    @Override
    public void publish(String organizationId, String subject, String draftId, String messageId,
                        Set<String> recipients, List<String> attachmentIds) {
        if (attachmentIds.isEmpty()) {
            return;
        }
        attachments.publishPrepared(actor(organizationId, subject), messageId, attachmentIds,
                Set.copyOf(recipients));
    }

    private StaffActor actor(String organizationId, String subject) {
        List<String> roles = transactions.execute(status -> staff.activeRoles(subject));
        return StaffActor.staff(organizationId, subject, roles.toArray(String[]::new));
    }
}
