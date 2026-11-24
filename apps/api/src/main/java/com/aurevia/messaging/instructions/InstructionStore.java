package com.aurevia.messaging.instructions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.instructions.InstructionContract.Fact;
import com.aurevia.messaging.instructions.InstructionContract.Revision;
import com.aurevia.messaging.instructions.InstructionContract.Template;
import com.aurevia.tenant.TenantContext;

/**
 * Persistence port for MSG-04 instructions.
 *
 * <p>Every operation is tenant-scoped: the active organization is part of each
 * internal key, so two tenants with identical local identifiers can never see
 * each other's templates, threads or facts. The port is deliberately small so
 * a durable adapter can implement it without leaking SQL into the domain.</p>
 */
public interface InstructionStore {

    /** @return active role keys for the authenticated subject */
    List<String> activeRoles(String subject);

    /**
     * Patient charts verified for a patient account subject (IAM-05).
     *
     * <p>A verified account may link to several independently verified charts;
     * patient reads must bind the caller to one of these rather than trust a
     * caller-supplied chart identifier. Scoped by the active tenant context.</p>
     *
     * @param subject authenticated patient account subject
     * @return immutable set of linked patient chart identifiers, possibly empty
     */
    Set<String> verifiedPatientCharts(String subject);

    /** @return whether the subject is assigned to the sending care unit */
    boolean staffAssignedToUnit(String subject, String unitId);

    /** @return whether the connection is active for this patient and unit */
    boolean connectionActive(String connectionId, String patientId, String unitId);

    /** @return the approved template, or empty when it is unknown in this tenant */
    Optional<Template> approvedTemplate(String unitId, String templateId);

    /** Store an approved template version. */
    void saveTemplate(Template template);

    /** @return the latest revision of a thread, or empty when unknown */
    Optional<Revision> current(String threadId);

    /** @return all revisions of a thread in ascending order */
    List<Revision> history(String threadId);

    /** @return current revisions visible to the patient */
    List<Revision> forPatient(String patientId);

    /** Store a new revision. */
    void save(Revision revision);

    /** Mark a prior revision superseded by a linked later revision. */
    void supersede(String threadId, int priorRevision, int newRevision);

    /** Mark the current revision withdrawn. */
    void withdraw(String threadId, int revision, String reason);

    /** Append a patient-notification or internal fact. */
    void append(Fact fact);

    /** @return immutable facts of a thread in occurrence order */
    List<Fact> facts(String threadId);

    /**
     * Synthetic in-memory adapter used only by fast unit tests. It is thread-safe
     * and keyed by organization; production uses the durable JDBC adapter.
     */
    class InMemory implements InstructionStore {

        private final ConcurrentHashMap<String, List<String>> roles = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Set<String>> verifiedCharts = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, List<String>> unitStaff = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Template> templates = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, List<Revision>> threads = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, List<Fact>> factsByThread = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, List<String>> patientThreads = new ConcurrentHashMap<>();

        @Override
        public synchronized List<String> activeRoles(String subject) {
            return List.copyOf(roles.getOrDefault(key(subject), List.of()));
        }

        @Override
        public synchronized Set<String> verifiedPatientCharts(String subject) {
            return Set.copyOf(verifiedCharts.getOrDefault(key(subject), Set.of()));
        }

        @Override
        public synchronized boolean staffAssignedToUnit(String subject, String unitId) {
            return unitStaff.getOrDefault(key(subject), List.of()).contains(unitId);
        }

        @Override
        public synchronized boolean connectionActive(String connectionId, String patientId, String unitId) {
            Connection connection = connections.get(key(connectionId));
            return connection != null && connection.active()
                    && connection.patientId().equals(patientId) && connection.unitId().equals(unitId);
        }

        @Override
        public synchronized Optional<Template> approvedTemplate(String unitId, String templateId) {
            Template template = templates.get(key(templateId));
            return Optional.ofNullable(template).filter(value -> value.unitId().equals(unitId));
        }

        @Override
        public synchronized void saveTemplate(Template template) {
            templates.put(key(template.id()), template);
        }

        @Override
        public synchronized Optional<Revision> current(String threadId) {
            return history(threadId).stream().max(Comparator.comparingInt(Revision::revision));
        }

        @Override
        public synchronized List<Revision> history(String threadId) {
            // Revisions are stored newest-first for cheap appends, but the port
            // and View contracts promise ascending order to every reader.
            return threads.getOrDefault(key(threadId), List.of()).stream()
                    .sorted(Comparator.comparingInt(Revision::revision))
                    .toList();
        }

        @Override
        public synchronized List<Revision> forPatient(String patientId) {
            List<Revision> result = new ArrayList<>();
            for (String threadId : patientThreads.getOrDefault(key(patientId), List.of())) {
                current(threadId).ifPresent(result::add);
            }
            return List.copyOf(result);
        }

        @Override
        public synchronized void save(Revision revision) {
            String threadKey = key(revision.threadId());
            List<Revision> revisions = new ArrayList<>(threads.getOrDefault(threadKey, List.of()));
            revisions.add(0, revision);
            threads.put(threadKey, List.copyOf(revisions));
            String patientKey = key(revision.patientId());
            List<String> owned = new ArrayList<>(patientThreads.getOrDefault(patientKey, List.of()));
            if (!owned.contains(revision.threadId())) {
                owned.add(revision.threadId());
                patientThreads.put(patientKey, List.copyOf(owned));
            }
        }

        @Override
        public synchronized void supersede(String threadId, int priorRevision, int newRevision) {
            replaceState(threadId, priorRevision,
                    revision -> new Revision(revision.threadId(), revision.revision(), revision.templateId(),
                            revision.kind(), revision.title(), revision.text(), revision.language(),
                            revision.authorSubject(), revision.authorRole(), revision.unitId(),
                            revision.connectionId(), revision.patientId(), revision.issuedAt(),
                            revision.actionDueAt(), revision.contextType(), revision.contextId(),
                            revision.attachmentIds(), InstructionContract.State.SUPERSEDED,
                            newRevision, revision.replacesRevision(), revision.withdrawalReason()));
        }

        @Override
        public synchronized void withdraw(String threadId, int revision, String reason) {
            replaceState(threadId, revision, current -> new Revision(current.threadId(),
                    current.revision(), current.templateId(), current.kind(), current.title(),
                    current.text(), current.language(), current.authorSubject(), current.authorRole(),
                    current.unitId(), current.connectionId(), current.patientId(), current.issuedAt(),
                    current.actionDueAt(), current.contextType(), current.contextId(),
                    current.attachmentIds(), InstructionContract.State.WITHDRAWN,
                    current.supersededBy(), current.replacesRevision(), reason));
        }

        @Override
        public synchronized void append(Fact fact) {
            String threadKey = key(fact.threadId());
            List<Fact> existing = new ArrayList<>(factsByThread.getOrDefault(threadKey, List.of()));
            existing.add(fact);
            factsByThread.put(threadKey, List.copyOf(existing));
        }

        @Override
        public synchronized List<Fact> facts(String threadId) {
            return List.copyOf(factsByThread.getOrDefault(key(threadId), List.of()));
        }

        /** Seed a synthetic member with the given active roles. */
        public synchronized void seedMember(String subject, List<String> roles) {
            this.roles.put(key(subject), List.copyOf(roles));
        }

        /** Link a verified patient chart to a patient account subject (IAM-05). */
        public synchronized void linkPatientChart(String subject, String patientId) {
            verifiedCharts.computeIfAbsent(key(subject), ignored -> ConcurrentHashMap.newKeySet())
                    .add(patientId);
        }

        /** Seed a unit assignment for a synthetic subject. */
        public synchronized void seedUnitStaff(String subject, String unitId) {
            List<String> assigned = new ArrayList<>(unitStaff.getOrDefault(key(subject), List.of()));
            assigned.add(unitId);
            unitStaff.put(key(subject), List.copyOf(assigned));
        }

        /** Seed an active connection in the active organization. */
        public synchronized void seedConnection(String connectionId, String patientId, String unitId) {
            seedConnectionOrg(organization(), connectionId, patientId, unitId);
        }

        /** Seed an active connection in a specific organization. */
        public synchronized void seedConnectionOrg(String org, String connectionId, String patientId,
                                                    String unitId) {
            connections.put(org + "|" + connectionId, new Connection(patientId, unitId, true));
        }

        /** Deactivate a synthetic connection in the active organization. */
        public synchronized void deactivateConnection(String connectionId) {
            Connection connection = connections.get(key(connectionId));
            if (connection != null) {
                connections.put(key(connectionId),
                        new Connection(connection.patientId(), connection.unitId(), false));
            }
        }

        /** @return an approved template in a specific organization, for isolation tests */
        public synchronized Optional<Template> findTemplateOrg(String org, String templateId) {
            return Optional.ofNullable(templates.get(org + "|" + templateId));
        }

        private void replaceState(String threadId, int revision,
                                  java.util.function.UnaryOperator<Revision> change) {
            String threadKey = key(threadId);
            List<Revision> revisions = threads.getOrDefault(threadKey, List.of());
            if (revisions.isEmpty()) {
                throw new ApiException(404, "NOT_FOUND", "instruction thread not found");
            }
            List<Revision> updated = new ArrayList<>();
            for (Revision candidate : revisions) {
                updated.add(candidate.revision() == revision ? change.apply(candidate) : candidate);
            }
            threads.put(threadKey, List.copyOf(updated));
        }

        private String key(String localId) {
            return organization() + "|" + localId;
        }

        private String organization() {
            TenantContext context = TenantContext.current();
            if (context == null || context.organizationId() == null) {
                throw new ApiException(403, "FORBIDDEN", "no active tenant context");
            }
            return context.organizationId();
        }

        /** Synthetic verified connection. */
        private record Connection(String patientId, String unitId, boolean active) {
        }
    }
}
