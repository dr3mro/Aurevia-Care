package com.aurevia.messaging.units;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.aurevia.messaging.units.MessagingContract.CareUnit;
import com.aurevia.messaging.units.MessagingContract.EligibleRelationship;
import com.aurevia.messaging.units.MessagingContract.MessagingAudit;
import com.aurevia.messaging.units.MessagingContract.PatientUnitConnection;

/**
 * Synthetic, tenant-keyed in-memory adapter for the messaging foundation.
 *
 * <p>Test-only: {@link com.aurevia.messaging.units.JdbcMessagingStore}
 * is the production bean. This adapter lets the verification and
 * connection-scoping domain be exercised deterministically without a database.
 * Every method takes the active organization and never merges rows across
 * tenants.</p>
 */
public class InMemoryMessagingStore implements MessagingStore {

    private final Map<String, String> roles = new ConcurrentHashMap<>();
    private final Set<String> activeSubjects = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<String>> verifiedCharts = new ConcurrentHashMap<>();
    private final Set<String> patients = ConcurrentHashMap.newKeySet();
    private final Map<String, CareUnit> units = new ConcurrentHashMap<>();
    private final Map<String, EligibleRelationship> relationships = new ConcurrentHashMap<>();
    private final Map<String, PatientUnitConnection> connections = new ConcurrentHashMap<>();
    private final Map<String, String> connectionBusinessKeys = new ConcurrentHashMap<>();
    private final Map<String, List<MessagingAudit>> audits = new ConcurrentHashMap<>();

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
     * Register a synthetic patient chart.
     *
     * @param organizationId owning organization
     * @param patientId      synthetic patient identifier
     */
    public void addPatient(String organizationId, String patientId) {
        patients.add(key(organizationId, patientId));
    }

    /**
     * Link a verified chart to a patient account (IAM-05).
     *
     * @param organizationId owning organization
     * @param subject        patient account subject
     * @param patientId      verified patient chart
     */
    public void linkPatient(String organizationId, String subject, String patientId) {
        verifiedCharts.computeIfAbsent(key(organizationId, subject), ignored -> ConcurrentHashMap.newKeySet())
                .add(patientId);
    }

    /**
     * Unlink a chart; future protected actions must immediately stop using it.
     *
     * @param organizationId owning organization
     * @param subject        patient account subject
     * @param patientId      unlinked patient chart
     */
    public void unlinkPatient(String organizationId, String subject, String patientId) {
        Set<String> charts = verifiedCharts.get(key(organizationId, subject));
        if (charts != null) {
            charts.remove(patientId);
        }
    }

    /**
     * Seed a synthetic care unit.
     *
     * @param unit unit to add
     */
    public void addUnit(CareUnit unit) {
        units.put(key(unit.organizationId(), unit.id()), unit);
    }

    /**
     * Seed a synthetic verified relationship.
     *
     * @param relationship relationship to add
     */
    public void addRelationship(EligibleRelationship relationship) {
        relationships.put(key(relationship.organizationId(), relationship.id()), relationship);
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
    public boolean patientExists(String organizationId, String patientId) {
        return patients.contains(key(organizationId, patientId));
    }

    @Override
    public Optional<CareUnit> findUnit(String organizationId, String unitId) {
        return Optional.ofNullable(units.get(key(organizationId, unitId)));
    }

    @Override
    public List<EligibleRelationship> relationshipsFor(String organizationId, String patientId) {
        List<EligibleRelationship> found = new ArrayList<>();
        for (EligibleRelationship relationship : relationships.values()) {
            if (organizationId.equals(relationship.organizationId())
                    && patientId.equals(relationship.patientId())
                    && findUnit(organizationId, relationship.unitId()).isPresent()) {
                found.add(relationship);
            }
        }
        return List.copyOf(found);
    }

    @Override
    public Optional<EligibleRelationship> relationship(String organizationId, String relationshipId) {
        EligibleRelationship found = relationships.get(key(organizationId, relationshipId));
        if (found == null || findUnit(organizationId, found.unitId()).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(found);
    }

    @Override
    public void consumeRelationship(String organizationId, String relationshipId) {
        EligibleRelationship found = relationship(organizationId, relationshipId).orElse(null);
        if (found == null) {
            return;
        }
        relationships.put(key(organizationId, found.id()), new EligibleRelationship(found.id(),
                found.organizationId(), found.patientId(), found.unitId(), found.context(),
                found.reference(), found.active(), found.expiresAt(), true));
    }

    @Override
    public Optional<PatientUnitConnection> findConnectionByReference(
            String organizationId, String patientId, String unitId, String reference) {
        return connectionsFor(organizationId, patientId).stream()
                .filter(connection -> unitId.equals(connection.unitId()))
                .filter(connection -> reference.equals(connection.contextReference()))
                .findFirst();
    }

    @Override
    public List<PatientUnitConnection> connectionsFor(String organizationId, String patientId) {
        List<PatientUnitConnection> found = new ArrayList<>();
        for (PatientUnitConnection connection : connections.values()) {
            if (organizationId.equals(connection.organizationId())
                    && patientId.equals(connection.patientId())) {
                found.add(connection);
            }
        }
        return List.copyOf(found);
    }

    @Override
    public Optional<PatientUnitConnection> findConnection(String organizationId, String connectionId) {
        PatientUnitConnection found = connections.get(key(organizationId, connectionId));
        if (found == null || !organizationId.equals(found.organizationId())) {
            return Optional.empty();
        }
        return Optional.of(found);
    }

    @Override
    public PatientUnitConnection insertConnection(String organizationId, PatientUnitConnection connection) {
        String businessKey = key(organizationId, connection.patientId() + "|" + connection.unitId()
                + "|" + connection.contextReference());
        String winner = connectionBusinessKeys.putIfAbsent(businessKey, connection.id());
        if (winner != null) {
            return findConnection(organizationId, winner).orElse(connection);
        }
        connections.put(key(organizationId, connection.id()), connection);
        return connection;
    }

    @Override
    public void revokeConnection(String organizationId, String connectionId, Instant revokedAt) {
        PatientUnitConnection found = findConnection(organizationId, connectionId).orElse(null);
        if (found == null) {
            return;
        }
        connections.put(key(organizationId, connectionId), new PatientUnitConnection(
                found.id(), found.organizationId(), found.patientId(), found.unitId(),
                found.context(), found.contextReference(), found.relationshipId(),
                com.aurevia.messaging.units.MessagingContract.ConnectionState.REVOKED,
                found.verifiedBy(), found.verifiedAt(), found.createdAt(), revokedAt));
    }

    @Override
    public void appendAudit(MessagingAudit audit) {
        audits.computeIfAbsent(audit.organizationId(), ignored -> new CopyOnWriteArrayList<>()).add(audit);
    }

    @Override
    public List<MessagingAudit> auditEvents(String organizationId) {
        return List.copyOf(audits.getOrDefault(organizationId, List.of()));
    }

    private static String key(String organizationId, String identifier) {
        return organizationId + "|" + identifier;
    }
}
