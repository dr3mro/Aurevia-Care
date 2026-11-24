package com.aurevia.staffmsg.references;

import java.nio.charset.StandardCharsets;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aurevia.api.ApiException;
import com.aurevia.events.DurableEventContract.PublishRequest;
import com.aurevia.events.DurableEventService;
import com.aurevia.messaging.governance.MessagingGovernanceService;
import com.aurevia.staff.StaffRepository;
import com.aurevia.staffmsg.attachments.StaffAttachmentInputs;
import com.aurevia.staffmsg.references.ReferenceContract.AccessOutcome;
import com.aurevia.staffmsg.references.ReferenceContract.NotificationDraft;
import com.aurevia.staffmsg.references.ReferenceContract.ReferenceAudit;
import com.aurevia.staffmsg.references.ReferenceContract.ReferenceKind;
import com.aurevia.tenant.TenantContext;

/**
 * Wiring for the reference ports using existing domain beans (MSG-07, ARC-07).
 *
 * <p>Each adapter is a narrow translation at a boundary: the connection access
 * check delegates to messaging governance, audits go through the staff audit
 * sink and notifications through the durable outbox. No existing package is
 * changed and no new dependency or persistence store is introduced.</p>
 */
@Configuration
public class ReferenceConfiguration {

    /**
     * Provide the recipient access-check port.
     *
     * @param governance connection-state and role enforcement service
     * @return the governance-backed adapter
     */
    @Bean
    public ReferenceAccess referenceAccess(MessagingGovernanceService governance) {
        return new GovernanceAccess(governance);
    }

    /**
     * Provide the identifier-free audit port.
     *
     * @param staff staff audit sink
     * @return the audit-backed adapter
     */
    @Bean
    public ReferenceAuditor referenceAuditor(StaffRepository staff) {
        return new StaffAuditAdapter(staff);
    }

    /**
     * Provide the identifier-free notification port.
     *
     * @param events durable outbox service
     * @return the outbox-backed adapter
     */
    @Bean
    public ReferenceNotifier referenceNotifier(DurableEventService events) {
        return new OutboxNotifier(events);
    }

    /** Translates a governance connection decision into a conceal-aware outcome. */
    static final class GovernanceAccess implements ReferenceAccess {

        private final MessagingGovernanceService governance;

        GovernanceAccess(MessagingGovernanceService governance) {
            this.governance = governance;
        }

        @Override
        public AccessOutcome check(String organizationId, String subject, ReferenceKind kind,
                                   String targetId, String connectionId) {
            try {
                // Governance re-checks membership and connection revocation on every call.
                governance.requireAttachmentAccess(connectionId, targetId);
                return AccessOutcome.ALLOWED;
            } catch (ApiException exception) {
                if (exception.status() == 404) {
                    return AccessOutcome.UNKNOWN_TARGET;
                }
                // Only the revocation code distinguishes a stale connection; every
                // other denial (role or membership) is a missing care relationship.
                if ("ATTACHMENT_ACCESS_REVOKED".equals(exception.code())) {
                    return AccessOutcome.REVOKED_OR_STALE;
                }
                return AccessOutcome.NO_RELATIONSHIP;
            }
        }
    }

    /** Appends reference facts to the staff audit sink without carrying target data. */
    static final class StaffAuditAdapter implements ReferenceAuditor {

        private final StaffRepository staff;

        StaffAuditAdapter(StaffRepository staff) {
            this.staff = staff;
        }

        @Override
        public void append(ReferenceAudit audit) {
            TenantContext context = TenantContext.current();
            // Without an organization there is no tenant row to key, so fail closed.
            if (context == null) {
                throw new ApiException(401, "UNAUTHENTICATED", "authentication required");
            }
            staff.appendAudit(context, audit.type().wire(), audit.referenceId(), audit.outcome());
        }
    }

    /** Publishes an identifier-free reference event to the durable outbox. */
    static final class OutboxNotifier implements ReferenceNotifier {

        private static final String NAMESPACE = "staff-reference";
        private static final String SENDER = "staff-reference";

        private final DurableEventService events;

        OutboxNotifier(DurableEventService events) {
            this.events = events;
        }

        @Override
        public void dispatch(NotificationDraft draft) {
            // The digest is over the fixed phrase, never over patient data.
            String digest = StaffAttachmentInputs.checksum(
                    draft.text().getBytes(StandardCharsets.UTF_8));
            events.publish(new PublishRequest(draft.referenceId(), 1, SENDER, draft.eventType(),
                    NAMESPACE, digest, draft.referenceId() + ":" + draft.eventType()));
        }
    }
}
