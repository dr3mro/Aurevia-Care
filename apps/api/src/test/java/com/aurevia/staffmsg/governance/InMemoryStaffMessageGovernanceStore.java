package com.aurevia.staffmsg.governance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.aurevia.messaging.governance.MessagingAuditContract.LegalHold;
import com.aurevia.messaging.governance.MessagingAuditContract.RetentionPolicy;
import com.aurevia.staffmsg.governance.StaffMessageGovernanceContract.AuditEvent;
import com.aurevia.staffmsg.governance.StaffMessageGovernanceContract.ConversationExport;
import com.aurevia.staffmsg.governance.StaffMessageGovernanceContract.MessageView;
import com.aurevia.staffmsg.model.StaffMessagingContract.AttachmentLink;
import com.aurevia.staffmsg.model.StaffMessagingContract.ConversationKind;
import com.aurevia.staffmsg.model.StaffMessagingContract.MailFolder;
import com.aurevia.staffmsg.model.StaffMessagingContract.ParticipantRole;
import com.aurevia.staffmsg.model.StaffMessagingContract.StaffConversation;
import com.aurevia.staffmsg.model.StaffMessagingContract.StaffParticipant;

/**
 * Synthetic, tenant-keyed in-memory adapter for staff-message governance tests.
 *
 * <p>Test-only fake: it lets the domain rules be exercised deterministically
 * without a database and is never a Spring bean. Production uses
 * {@link com.aurevia.staffmsg.governance.JdbcStaffMessageGovernanceStore}.
 * Every method names the active organization and never merges rows across
 * tenants, matching the durable port contract.</p>
 */
public class InMemoryStaffMessageGovernanceStore implements StaffMessageGovernanceStore {

    private final Map<String, String> roles = new ConcurrentHashMap<>();
    private final Map<String, Boolean> activeMembers = new ConcurrentHashMap<>();
    private final Map<String, List<AuditEvent>> audits = new ConcurrentHashMap<>();
    private final Map<String, List<LegalHold>> holds = new ConcurrentHashMap<>();
    private final Map<String, RetentionPolicy> policies = new ConcurrentHashMap<>();
    private final Map<String, List<MessageView>> messages = new ConcurrentHashMap<>();
    private final Map<String, List<StaffParticipant>> participants = new ConcurrentHashMap<>();
    private final Map<String, StaffConversation> conversations = new ConcurrentHashMap<>();
    private final Map<String, Map<String, MailFolder>> folders = new ConcurrentHashMap<>();
    private final Map<String, List<AttachmentLink>> links = new ConcurrentHashMap<>();
    private final Map<String, List<ConversationExport>> exports = new ConcurrentHashMap<>();

    /** Seed one membership and role. */
    public void addMember(String organizationId, String subject, String role, boolean active) {
        roles.put(key(organizationId, subject), role);
        activeMembers.put(key(organizationId, subject), active);
    }

    /** Seed one conversation created at a server instant. */
    public void addConversation(String organizationId, String id, Instant createdAt) {
        conversations.put(key(organizationId, id), new StaffConversation(organizationId, "facility", id,
                ConversationKind.MAIL_THREAD, "Synthetic topic", "nurse-a", createdAt));
    }

    /** Seed one participant membership in a conversation. */
    public void addParticipant(String organizationId, String conversationId, String subject) {
        participants.computeIfAbsent(key(organizationId, conversationId), ignored -> new ArrayList<>())
                .add(new StaffParticipant(organizationId, conversationId, subject, ParticipantRole.MEMBER,
                        Instant.EPOCH, null, false));
    }

    /** Seed one durable message projection. */
    public void addMessage(String organizationId, MessageView message) {
        messages.computeIfAbsent(organizationId, ignored -> new ArrayList<>()).add(message);
    }

    /** Seed one attachment link carrying a checksum. */
    public void addLink(String organizationId, String messageId, AttachmentLink link) {
        links.computeIfAbsent(key(organizationId, messageId), ignored -> new ArrayList<>()).add(link);
    }

    /** Seed one tenant retention policy. */
    public void putPolicy(String organizationId, String resourceType, long days) {
        policies.put(key(organizationId, resourceType), new RetentionPolicy(organizationId, resourceType, days));
    }

    /** Seed one legal hold. */
    public void putHold(String organizationId, LegalHold hold) {
        holds.computeIfAbsent(organizationId, ignored -> new ArrayList<>()).add(hold);
    }

    /** Return the exports persisted for a tenant. */
    public List<ConversationExport> exports(String organizationId) {
        return List.copyOf(exports.getOrDefault(organizationId, List.of()));
    }

    @Override
    public boolean hasActiveMembership(String organizationId, String subject) {
        return Boolean.TRUE.equals(activeMembers.get(key(organizationId, subject)));
    }

    @Override
    public List<String> activeRoles(String organizationId, String subject) {
        String role = roles.get(key(organizationId, subject));
        return role == null || !hasActiveMembership(organizationId, subject) ? List.of() : List.of(role);
    }

    @Override
    public void appendAudit(AuditEvent event) {
        audits.computeIfAbsent(event.organizationId(), ignored -> new ArrayList<>()).add(event);
    }

    @Override
    public List<AuditEvent> auditEvents(String organizationId) {
        return List.copyOf(audits.getOrDefault(organizationId, List.of()));
    }

    @Override
    public List<LegalHold> legalHolds(String organizationId) {
        return List.copyOf(holds.getOrDefault(organizationId, List.of()));
    }

    @Override
    public void putLegalHold(String organizationId, LegalHold hold) {
        List<LegalHold> current = holds.computeIfAbsent(organizationId, ignored -> new ArrayList<>());
        current.removeIf(existing -> existing.id().equals(hold.id()));
        current.add(hold);
    }

    @Override
    public Optional<RetentionPolicy> retentionPolicy(String organizationId, String resourceType) {
        return Optional.ofNullable(policies.get(key(organizationId, resourceType)));
    }

    @Override
    public List<MessageView> messages(String organizationId) {
        return List.copyOf(messages.getOrDefault(organizationId, List.of()));
    }

    @Override
    public List<StaffParticipant> participants(String organizationId, String conversationId) {
        return List.copyOf(participants.getOrDefault(key(organizationId, conversationId), List.of()));
    }

    @Override
    public Optional<StaffConversation> conversation(String organizationId, String conversationId) {
        return Optional.ofNullable(conversations.get(key(organizationId, conversationId)));
    }

    @Override
    public Map<String, MailFolder> folders(String organizationId, String subject) {
        return new LinkedHashMap<>(folders.getOrDefault(key(organizationId, subject), Map.of()));
    }

    @Override
    public void setFolder(String organizationId, String messageId, String subject, MailFolder folder) {
        folders.computeIfAbsent(key(organizationId, subject), ignored -> new LinkedHashMap<>())
                .put(messageId, folder);
    }

    @Override
    public List<AttachmentLink> attachmentLinks(String organizationId, String messageId) {
        return List.copyOf(links.getOrDefault(key(organizationId, messageId), List.of()));
    }

    @Override
    public void putExport(String organizationId, ConversationExport export) {
        exports.computeIfAbsent(organizationId, ignored -> new ArrayList<>()).add(export);
    }

    private static String key(String organizationId, String identifier) {
        return organizationId + "|" + identifier;
    }
}
