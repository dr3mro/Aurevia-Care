package com.aurevia.messaging.acknowledgment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.aurevia.messaging.acknowledgment.InstructionFacts.Acknowledgment;
import com.aurevia.messaging.acknowledgment.InstructionFacts.AuditFact;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Dispatch;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Fact;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FirstView;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FollowUp;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Instruction;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Publication;

/**
 * Tenant-scoped persistence boundary for MSG-05 facts.
 *
 * <p>Every method carries the active organization explicitly so two tenants
 * with identical local identifiers can never share a fact, a digest or a
 * follow-up. Implementations must enforce uniqueness of first view per viewer
 * and revision, uniqueness of acknowledgment per revision, and durable
 * uniqueness of a caller's idempotency key per command.</p>
 */
public interface InstructionFactStore {

    /**
     * Check active membership in the organization.
     *
     * @param organizationId active organization
     * @param subject        authenticated subject
     * @return {@code true} when the membership is active
     */
    boolean hasActiveMembership(String organizationId, String subject);

    /**
     * List role keys currently in force for the subject.
     *
     * @param organizationId active organization
     * @param subject        authenticated subject
     * @return active role keys
     */
    List<String> activeRoles(String organizationId, String subject);

    /**
     * List patient charts verified for this account (IAM-05).
     *
     * @param organizationId active organization
     * @param subject        authenticated subject
     * @return verified chart identifiers
     */
    Set<String> verifiedPatientCharts(String organizationId, String subject);

    /**
     * Check that an active connection ties a chart to a unit.
     *
     * @param organizationId active organization
     * @param patientId      verified chart
     * @param unitId         recipient unit
     * @return {@code true} when the connection exists and is active
     */
    boolean hasActiveConnection(String organizationId, String patientId, String unitId);

    /**
     * Check that a staff subject is assigned to a unit.
     *
     * @param organizationId active organization
     * @param subject        staff subject
     * @param unitId         recipient unit
     * @return {@code true} when assigned
     */
    boolean isStaffAssignedToUnit(String organizationId, String subject, String unitId);

    /**
     * Store one immutable instruction revision, returning the original on a
     * duplicate so a caller can detect an attempt to alter published content.
     *
     * @param organizationId active organization
     * @param instruction    instruction to store
     * @return the authoritative stored instruction, never a replacement
     */
    Instruction putInstruction(String organizationId, Instruction instruction);

    /**
     * Find one instruction revision.
     *
     * @param organizationId active organization
     * @param instructionId  instruction identifier
     * @param revision       revision number
     * @return the instruction when visible in this tenant
     */
    Optional<Instruction> instruction(String organizationId, String instructionId, int revision);

    /**
     * Find the latest stored revision of an instruction.
     *
     * @param organizationId active organization
     * @param instructionId  instruction identifier
     * @return the latest revision, if any
     */
    Optional<Instruction> latestInstruction(String organizationId, String instructionId);

    /**
     * List published instructions owned by a unit.
     *
     * @param organizationId active organization
     * @param unitId         owning unit
     * @return published instructions in this tenant
     */
    List<Instruction> instructionsForUnit(String organizationId, String unitId);

    /**
     * Append a publication fact, returning the original on duplicate.
     *
     * @param organizationId active organization
     * @param publication    publication fact
     * @return the authoritative stored fact
     */
    Publication putPublication(String organizationId, Publication publication);

    /**
     * List publication facts for one revision.
     *
     * @param organizationId active organization
     * @param instructionId  instruction identifier
     * @param revision       revision number
     * @return publication facts
     */
    List<Publication> publications(String organizationId, String instructionId, int revision);

    /**
     * Append one generic notification dispatch fact.
     *
     * @param organizationId active organization
     * @param dispatch       dispatch fact
     * @return the stored fact
     */
    Dispatch appendDispatch(String organizationId, Dispatch dispatch);

    /**
     * List dispatch facts for one revision.
     *
     * @param organizationId active organization
     * @param instructionId  instruction identifier
     * @param revision       revision number
     * @return dispatch facts in append order
     */
    List<Dispatch> dispatches(String organizationId, String instructionId, int revision);

    /**
     * Record the first view by one viewer, never overwriting an earlier one.
     *
     * @param organizationId active organization
     * @param firstView      first-view candidate
     * @return the authoritative first fact
     */
    FirstView putFirstViewIfAbsent(String organizationId, FirstView firstView);

    /**
     * List first-view facts for one revision and viewer.
     *
     * @param organizationId active organization
     * @param instructionId  instruction identifier
     * @param revision       revision number
     * @param viewer         authenticated patient subject
     * @return first-view facts
     */
    List<FirstView> firstViews(String organizationId, String instructionId, int revision, String viewer);

    /**
     * Record one patient account's acknowledgment of a revision, never
     * overwriting that account's earlier receipt. Several verified accounts may
     * link to the same chart (IAM-05), so each is recorded separately.
     *
     * @param organizationId active organization
     * @param acknowledgment acknowledgment fact
     * @return the authoritative fact
     */
    Acknowledgment putAcknowledgmentIfAbsent(String organizationId, Acknowledgment acknowledgment);

    /**
     * List every patient account's acknowledgments for one revision.
     *
     * @param organizationId active organization
     * @param instructionId  instruction identifier
     * @param revision       revision number
     * @return acknowledgment facts in occurrence then actor order
     */
    List<Acknowledgment> acknowledgments(String organizationId, String instructionId, int revision);

    /**
     * Store an overdue follow-up if none exists for the revision.
     *
     * @param organizationId active organization
     * @param followUp       follow-up candidate
     * @return the authoritative follow-up
     */
    FollowUp putFollowUpIfAbsent(String organizationId, FollowUp followUp);

    /**
     * Find the follow-up for one revision.
     *
     * @param organizationId active organization
     * @param instructionId  instruction identifier
     * @param revision       revision number
     * @return the follow-up, if any
     */
    Optional<FollowUp> followUps(String organizationId, String instructionId, int revision);

    /**
     * Resolve a follow-up with a reason.
     *
     * @param organizationId active organization
     * @param instructionId  instruction identifier
     * @param revision       revision number
     * @param resolver       authorized staff subject
     * @param reason         resolution reason
     * @param resolvedAt     server resolution instant
     * @return the resolved follow-up
     */
    FollowUp resolveFollowUp(String organizationId, String instructionId, int revision, String resolver,
                             String reason, Instant resolvedAt);

    /**
     * List all follow-ups in one tenant.
     *
     * @param organizationId active organization
     * @return all follow-ups in insertion order
     */
    List<FollowUp> allFollowUps(String organizationId);

    /**
     * List instructions whose requested acknowledgment is overdue.
     *
     * @param organizationId active organization
     * @param now            current instant
     * @return instructions with a due instant strictly before {@code now}
     */
    List<Instruction> overdueCandidates(String organizationId, Instant now);

    /**
     * Record an idempotency key for a caller and command.
     *
     * @param organizationId active organization
     * @param subject        authenticated caller
     * @param action         action key
     * @param clientId       caller-supplied idempotency key
     * @param digest         canonical request digest
     * @return {@code true} when the key was new, {@code false} when it already existed
     */
    boolean putClientDigest(String organizationId, String subject, String action, String clientId,
                            String digest);

    /**
     * Read the digest recorded for an idempotency key.
     *
     * @param organizationId active organization
     * @param subject        authenticated caller
     * @param action         action key
     * @param clientId       caller-supplied idempotency key
     * @return the recorded digest, if any
     */
    Optional<String> clientDigest(String organizationId, String subject, String action, String clientId);

    /**
     * Count recorded idempotency keys for a caller and action.
     *
     * @param organizationId active organization
     * @param subject        authenticated caller
     * @param action         action key
     * @return recorded key count
     */
    int clientDigestCount(String organizationId, String subject, String action);

    /**
     * Append a non-sensitive audit fact.
     *
     * @param fact fact to append
     */
    void appendAudit(AuditFact fact);

    /**
     * List audit facts of one tenant.
     *
     * @param organizationId active organization
     * @return audit facts in append order
     */
    List<AuditFact> auditEvents(String organizationId);

    /**
     * List all facts recorded for one instruction in one tenant.
     *
     * @param organizationId active organization
     * @param instructionId  instruction identifier
     * @return all facts in insertion order
     */
    List<Fact> allFacts(String organizationId, String instructionId);
}
