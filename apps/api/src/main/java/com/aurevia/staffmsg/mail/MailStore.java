package com.aurevia.staffmsg.mail;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.aurevia.staffmsg.mail.MailContract.DraftView;
import com.aurevia.staffmsg.mail.MailContract.SentMail;
import com.aurevia.staffmsg.model.StaffMessagingContract.MailFolder;

/**
 * Durable persistence boundary for email-like staff messages (MSG-02, ARC-07).
 *
 * <p>The kernel owns every decision; this port only stores and retrieves state.
 * Every method takes the active organization explicitly so a JDBC adapter can
 * name it in its SQL and rely on forced row-level security as a second boundary.
 * Drafts remain mutable and versioned, a published message is immutable, folders
 * belong to one viewer, and publication plus its retry key commit as one unit.</p>
 */
public interface MailStore {

    /**
     * Insert or replace a draft, keyed by its tenant and identifier.
     *
     * @param organizationId active tenant
     * @param draft          the current draft value
     */
    void saveDraft(String organizationId, DraftView draft);

    /**
     * Find a draft owned by one subject.
     *
     * @param organizationId active tenant
     * @param draftId        draft identifier
     * @param actor          owner subject, never accepted from a request body
     * @return the draft, or empty when it is absent or owned by somebody else
     */
    Optional<DraftView> draft(String organizationId, String draftId, String actor);

    /**
     * Find a published message by identifier.
     *
     * @param organizationId active tenant
     * @param messageId      message identifier
     * @return the message, or empty when it is absent in this tenant
     */
    Optional<SentMail> message(String organizationId, String messageId);

    /**
     * List every published message in a tenant. Participation and folder filters
     * are applied by the kernel, not here, so read policy stays in one place.
     *
     * @param organizationId active tenant
     * @return all published messages in insertion order
     */
    List<SentMail> messages(String organizationId);

    /**
     * Replace the flags of a published message; the message body never changes.
     *
     * @param organizationId active tenant
     * @param messageId      message identifier
     * @param flags          complete replacement flag set
     */
    void setFlags(String organizationId, String messageId, List<String> flags);

    /**
     * Return the folder placement of one viewer for every message.
     *
     * @param organizationId active tenant
     * @param actor          viewer subject
     * @return message identifier to folder, absent entries default to inbox
     */
    Map<String, MailFolder> folders(String organizationId, String actor);

    /**
     * Place one message in one viewer's folder.
     *
     * @param organizationId active tenant
     * @param messageId      message identifier
     * @param actor          viewer subject
     * @param folder         target folder
     */
    void setFolder(String organizationId, String messageId, String actor, MailFolder folder);

    /**
     * Read the message id already published for a client retry key.
     *
     * @param organizationId   active tenant
     * @param actor            original sender
     * @param clientMessageId  opaque retry key from the request
     * @return the first published message id, or empty on a fresh key
     */
    Optional<String> replay(String organizationId, String actor, String clientMessageId);

    /**
     * Publish a message and its retry key atomically. A key that another request
     * claimed in the meantime resolves to the winning message instead of a
     * second publication, so a concurrent retry is still idempotent.
     *
     * @param organizationId  active tenant
     * @param actor           authenticated sender
     * @param clientMessageId opaque retry key
     * @param draftId         draft consumed by this publication
     * @param message         the frozen, published message
     * @param recipients      concrete recipient subjects resolved at send time
     * @return the id of the message that won the key
     */
    String publish(String organizationId, String actor, String clientMessageId, String draftId,
                   SentMail message, List<String> recipients);
}
