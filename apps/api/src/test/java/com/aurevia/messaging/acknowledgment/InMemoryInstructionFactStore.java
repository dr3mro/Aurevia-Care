package com.aurevia.messaging.acknowledgment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.aurevia.messaging.acknowledgment.InstructionFacts.Acknowledgment;
import com.aurevia.messaging.acknowledgment.InstructionFacts.AuditFact;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Dispatch;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Fact;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FollowUp;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FollowUpState;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FirstView;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Instruction;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Publication;

/**
 * Synthetic, tenant-keyed in-memory adapter for MSG-05 facts.
 *
 * <p>The adapter exists so fact separation, revision binding and overdue
 * routing can be exercised deterministically without a database. It is not the
 * authoritative store: a production adapter must persist these append-only
 * facts under forced tenant row-level security with unique constraints on first
 * view and acknowledgment per viewer and on idempotency keys. Every method takes the active
 * organization and never merges rows across tenants.</p>
 *
 * <p>Test-only: production uses
 * {@link com.aurevia.messaging.acknowledgment.JdbcInstructionFactStore}.</p>
 */
public class InMemoryInstructionFactStore implements InstructionFactStore {

    private final Map<String, String> roles = new ConcurrentHashMap<>();
    private final Set<String> activeSubjects = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<String>> verifiedCharts = new ConcurrentHashMap<>();
    private final Set<String> connections = ConcurrentHashMap.newKeySet();
    private final Set<String> staffUnits = ConcurrentHashMap.newKeySet();
    private final Map<String, Instruction> instructions = new ConcurrentHashMap<>();
    private final Map<String, Publication> publications = new ConcurrentHashMap<>();
    private final List<Dispatch> dispatches = new CopyOnWriteArrayList<>();
    private final Map<String, FirstView> firstViews = new ConcurrentHashMap<>();
    private final Map<String, Acknowledgment> acknowledgments = new ConcurrentHashMap<>();
    private final Map<String, FollowUp> followUps = new ConcurrentHashMap<>();
    private final Map<String, String> clientDigests = new ConcurrentHashMap<>();
    private final Map<String, List<AuditFact>> audits = new ConcurrentHashMap<>();

    /**
     * Seed a synthetic membership and role.
     *
     * @param organizationId owning organization
     * @param subject        synthetic subject
     * @param role           active role key
     * @param active         whether the membership is active
     */
    public void addMember(String organizationId, String subject, String role, boolean active) {
        roles.put(key(organizationId, subject), role);
        if (active) {
            activeSubjects.add(key(organizationId, subject));
        } else {
            activeSubjects.remove(key(organizationId, subject));
        }
    }

    /**
     * Link a verified chart to a patient account (IAM-05).
     *
     * @param organizationId owning organization
     * @param subject        patient account subject
     * @param patientId      verified chart
     */
    public void linkPatient(String organizationId, String subject, String patientId) {
        verifiedCharts.computeIfAbsent(key(organizationId, subject),
                ignored -> ConcurrentHashMap.newKeySet()).add(patientId);
    }

    /**
     * Unlink a chart so future protected actions stop using it.
     *
     * @param organizationId owning organization
     * @param subject        patient account subject
     * @param patientId      unlinked chart
     */
    public void unlinkPatient(String organizationId, String subject, String patientId) {
        Set<String> charts = verifiedCharts.get(key(organizationId, subject));
        if (charts != null) {
            charts.remove(patientId);
        }
    }

    /**
     * Seed an active patient-unit connection or revoke an existing one.
     *
     * @param organizationId owning organization
     * @param patientId      verified chart
     * @param unitId         recipient unit
     * @param active         whether the connection grants access
     */
    public void addConnection(String organizationId, String patientId, String unitId, boolean active) {
        String composite = key(organizationId, patientId + "|" + unitId);
        if (active) {
            connections.add(composite);
        } else {
            connections.remove(composite);
        }
    }

    /**
     * Seed a staff assignment to a recipient unit.
     *
     * @param organizationId owning organization
     * @param subject        staff subject
     * @param unitId         recipient unit
     */
    public void assignStaffUnit(String organizationId, String subject, String unitId) {
        staffUnits.add(key(organizationId, subject + "|" + unitId));
    }

    @Override
    public boolean hasActiveMembership(String organizationId, String subject) {
        return activeSubjects.contains(key(organizationId, subject));
    }

    @Override
    public List<String> activeRoles(String organizationId, String subject) {
        String role = roles.get(key(organizationId, subject));
        if (role == null || !hasActiveMembership(organizationId, subject)) {
            return List.of();
        }
        return List.of(role);
    }

    @Override
    public Set<String> verifiedPatientCharts(String organizationId, String subject) {
        Set<String> charts = verifiedCharts.get(key(organizationId, subject));
        return charts == null ? Set.of() : Set.copyOf(charts);
    }

    @Override
    public boolean hasActiveConnection(String organizationId, String patientId, String unitId) {
        return connections.contains(key(organizationId, patientId + "|" + unitId));
    }

    @Override
    public boolean isStaffAssignedToUnit(String organizationId, String subject, String unitId) {
        return staffUnits.contains(key(organizationId, subject + "|" + unitId));
    }

    @Override
    public Instruction putInstruction(String organizationId, Instruction instruction) {
        if (instruction == null || !organizationId.equals(instruction.organizationId())) {
            throw new IllegalArgumentException("instruction must belong to the active organization");
        }
        Instruction winner = instructions.putIfAbsent(
                revisionKey(organizationId, instruction.id(), instruction.revision()), instruction);
        return winner == null ? instruction : winner;
    }

    @Override
    public Optional<Instruction> instruction(String organizationId, String instructionId, int revision) {
        return Optional.ofNullable(instructions.get(revisionKey(organizationId, instructionId, revision)));
    }

    @Override
    public Optional<Instruction> latestInstruction(String organizationId, String instructionId) {
        return instructions.values().stream()
                .filter(instruction -> organizationId.equals(instruction.organizationId())
                        && instructionId.equals(instruction.id()))
                .max(Comparator.comparingInt(Instruction::revision));
    }

    @Override
    public List<Instruction> instructionsForUnit(String organizationId, String unitId) {
        return instructions.values().stream()
                .filter(instruction -> organizationId.equals(instruction.organizationId())
                        && unitId.equals(instruction.unitId())
                        && publications.containsKey(revisionKey(organizationId,
                                instruction.id(), instruction.revision())))
                .toList();
    }

    @Override
    public Publication putPublication(String organizationId, Publication publication) {
        String composite = revisionKey(organizationId, publication.instructionId(), publication.revision());
        Publication winner = publications.putIfAbsent(composite, publication);
        return winner == null ? publication : winner;
    }

    @Override
    public List<Publication> publications(String organizationId, String instructionId, int revision) {
        Publication found = publications.get(revisionKey(organizationId, instructionId, revision));
        return found == null ? List.of() : List.of(found);
    }

    @Override
    public Dispatch appendDispatch(String organizationId, Dispatch dispatch) {
        dispatches.add(dispatch);
        return dispatch;
    }

    @Override
    public List<Dispatch> dispatches(String organizationId, String instructionId, int revision) {
        return dispatches.stream()
                .filter(dispatch -> organizationId.equals(dispatch.organizationId())
                        && instructionId.equals(dispatch.instructionId())
                        && revision == dispatch.revision())
                .toList();
    }

    @Override
    public FirstView putFirstViewIfAbsent(String organizationId, FirstView firstView) {
        String composite = key(organizationId, firstView.instructionId() + "|" + firstView.revision()
                + "|" + firstView.actor());
        FirstView winner = firstViews.putIfAbsent(composite, firstView);
        return winner == null ? firstView : winner;
    }

    @Override
    public List<FirstView> firstViews(String organizationId, String instructionId, int revision, String viewer) {
        FirstView found = firstViews.get(key(organizationId,
                instructionId + "|" + revision + "|" + viewer));
        return found == null ? List.of() : List.of(found);
    }

    @Override
    public Acknowledgment putAcknowledgmentIfAbsent(String organizationId, Acknowledgment acknowledgment) {
        // IAM-05 permits several verified accounts on one chart: dedupe per
        // acknowledging subject so a replay returns its own receipt, never the
        // first caller's fact with the wrong actor.
        String composite = key(organizationId, acknowledgment.instructionId() + "|"
                + acknowledgment.revision() + "|" + acknowledgment.actor());
        Acknowledgment winner = acknowledgments.putIfAbsent(composite, acknowledgment);
        return winner == null ? acknowledgment : winner;
    }

    @Override
    public List<Acknowledgment> acknowledgments(String organizationId, String instructionId, int revision) {
        String prefix = key(organizationId, instructionId + "|" + revision + "|");
        return acknowledgments.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .sorted(Comparator.comparing(Acknowledgment::occurredAt)
                        .thenComparing(Acknowledgment::actor))
                .toList();
    }

    @Override
    public FollowUp putFollowUpIfAbsent(String organizationId, FollowUp followUp) {
        String composite = revisionKey(organizationId, followUp.instructionId(), followUp.revision());
        FollowUp winner = followUps.putIfAbsent(composite, followUp);
        return winner == null ? followUp : winner;
    }

    @Override
    public Optional<FollowUp> followUps(String organizationId, String instructionId, int revision) {
        return Optional.ofNullable(followUps.get(revisionKey(organizationId, instructionId, revision)));
    }

    @Override
    public FollowUp resolveFollowUp(String organizationId, String instructionId, int revision,
                                    String resolver, String reason, Instant resolvedAt) {
        String composite = revisionKey(organizationId, instructionId, revision);
        FollowUp current = followUps.get(composite);
        if (current == null) {
            throw new IllegalStateException("no follow-up to resolve");
        }
        FollowUp resolved = new FollowUp(current.organizationId(), current.instructionId(),
                current.revision(), current.unitId(), current.routedAt(), FollowUpState.RESOLVED,
                resolver, resolvedAt, reason);
        followUps.put(composite, resolved);
        return resolved;
    }

    @Override
    public List<FollowUp> allFollowUps(String organizationId) {
        return followUps.values().stream()
                .filter(followUp -> organizationId.equals(followUp.organizationId()))
                .toList();
    }

    @Override
    public List<Instruction> overdueCandidates(String organizationId, Instant now) {
        return instructions.values().stream()
                .filter(instruction -> organizationId.equals(instruction.organizationId()))
                .filter(instruction -> latestInstruction(organizationId, instruction.id())
                        .map(latest -> latest.revision() == instruction.revision()).orElse(false))
                .filter(Instruction::requiresAcknowledgment)
                .filter(instruction -> instruction.acknowledgmentDueAt() != null)
                .filter(instruction -> instruction.acknowledgmentDueAt().isBefore(now))
                .filter(instruction -> acknowledgments(organizationId,
                        instruction.id(), instruction.revision()).isEmpty())
                .filter(instruction -> followUps.get(revisionKey(organizationId,
                        instruction.id(), instruction.revision())) == null)
                .toList();
    }

    @Override
    public boolean putClientDigest(String organizationId, String subject, String action, String clientId,
                                   String digest) {
        String composite = key(organizationId, subject + "|" + action + "|" + clientId);
        return clientDigests.putIfAbsent(composite, digest) == null;
    }

    @Override
    public Optional<String> clientDigest(String organizationId, String subject, String action, String clientId) {
        return Optional.ofNullable(clientDigests.get(key(organizationId,
                subject + "|" + action + "|" + clientId)));
    }

    @Override
    public int clientDigestCount(String organizationId, String subject, String action) {
        String prefix = key(organizationId, subject + "|" + action + "|");
        return (int) clientDigests.keySet().stream().filter(composite -> composite.startsWith(prefix)).count();
    }

    @Override
    public void appendAudit(AuditFact fact) {
        audits.computeIfAbsent(fact.organizationId(), ignored -> new CopyOnWriteArrayList<>()).add(fact);
    }

    @Override
    public List<AuditFact> auditEvents(String organizationId) {
        return List.copyOf(audits.getOrDefault(organizationId, List.of()));
    }

    @Override
    public List<Fact> allFacts(String organizationId, String instructionId) {
        List<Fact> facts = new ArrayList<>();
        publications.values().stream()
                .filter(fact -> organizationId.equals(fact.organizationId())
                        && instructionId.equals(fact.instructionId()))
                .forEach(facts::add);
        dispatches.stream()
                .filter(fact -> organizationId.equals(fact.organizationId())
                        && instructionId.equals(fact.instructionId()))
                .forEach(facts::add);
        firstViews.values().stream()
                .filter(fact -> organizationId.equals(fact.organizationId())
                        && instructionId.equals(fact.instructionId()))
                .forEach(facts::add);
        acknowledgments.values().stream()
                .filter(fact -> organizationId.equals(fact.organizationId())
                        && instructionId.equals(fact.instructionId()))
                .forEach(facts::add);
        followUps.values().stream()
                .filter(fact -> organizationId.equals(fact.organizationId())
                        && instructionId.equals(fact.instructionId()))
                .forEach(facts::add);
        return List.copyOf(facts);
    }

    private static String key(String organizationId, String identifier) {
        return organizationId + "|" + identifier;
    }

    private static String revisionKey(String organizationId, String identifier, int revision) {
        return key(organizationId, identifier + "|" + revision);
    }
}
