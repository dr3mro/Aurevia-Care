package com.aurevia.messaging.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.aurevia.messaging.units.MessagingContract.MessagingAudit;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceProfile;
import com.aurevia.messaging.service.ServiceProfileContract.WorkItemFact;

/**
 * Synthetic, tenant-scoped in-memory adapter for MSG-06 unit tests.
 *
 * <p>Keys are always the active organization plus a local identifier, so the
 * same local identifier in two tenants cannot collide. This is a test fake only:
 * production uses {@link com.aurevia.messaging.service.JdbcServiceProfileStore}
 * with forced row-level security, durable uniqueness and an append-only audit
 * table. No Spring stereotype is present so it is never a production bean.</p>
 */
public class InMemoryServiceProfileStore implements ServiceProfileStore {

    private final Map<String, String> roles = new ConcurrentHashMap<>();
    private final Set<String> activeSubjects = ConcurrentHashMap.newKeySet();
    private final Set<String> units = ConcurrentHashMap.newKeySet();
    private final Set<String> verifiedCharts = ConcurrentHashMap.newKeySet();
    private final Map<String, String> connections = new ConcurrentHashMap<>();
    private final Map<String, String> assignments = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> approvedChannels = new ConcurrentHashMap<>();
    private final Map<String, ServiceProfile> profiles = new ConcurrentHashMap<>();
    private final Map<String, List<WorkItemFact>> workItems = new ConcurrentHashMap<>();
    private final Map<String, List<MessagingAudit>> audits = new ConcurrentHashMap<>();

    /** Seed a synthetic membership and role. */
    public void addMember(String organizationId, String subject, String role, boolean active) {
        roles.put(key(organizationId, subject), role);
        if (active) {
            activeSubjects.add(key(organizationId, subject));
        } else {
            activeSubjects.remove(key(organizationId, subject));
        }
    }

    /** Seed a synthetic care unit. */
    public void addUnit(String organizationId, String unitId) {
        units.add(key(organizationId, unitId));
    }

    /** Link a verified chart to a patient account (IAM-05). */
    public void linkPatient(String organizationId, String subject, String patientId) {
        verifiedCharts.add(key(organizationId, subject) + "|" + patientId);
    }

    /** Seed an active or revoked patient-unit connection. */
    public void addConnection(String organizationId, String patientId, String unitId, boolean active) {
        connections.put(key(organizationId, patientId) + "|" + unitId, active ? "active" : "revoked");
    }

    /** Assign a staff subject to a care unit. */
    public void assignUnit(String organizationId, String subject, String unitId) {
        assignments.put(key(organizationId, subject), unitId);
    }

    /**
     * Seed the tenant-approved channel codes for one policy key.
     *
     * @param organizationId active tenant identifier
     * @param key            tenant-local approved-channel policy key
     * @param codes          comma-separated approved channel codes
     */
    public void seedApprovedChannels(String organizationId, String key, String codes) {
        Set<String> approved = new java.util.HashSet<>();
        for (String code : codes.split(",")) {
            String trimmed = code.strip();
            if (!trimmed.isEmpty()) {
                approved.add(trimmed);
            }
        }
        approvedChannels.put(key(organizationId, key), Set.copyOf(approved));
    }

    /** Seed a synthetic service profile version. */
    public void addProfile(ServiceProfile profile) {
        profiles.put(key(profile.organizationId(), profile.id()), profile);
    }

    /** Seed a synthetic work fact. */
    @Override
    public void addWorkItem(String organizationId, WorkItemFact item) {
        workItems.computeIfAbsent(key(organizationId, item.unitId()), ignored -> new ArrayList<>()).add(item);
    }

    @Override
    public boolean hasActiveMembership(String organizationId, String subject) {
        return activeSubjects.contains(key(organizationId, subject));
    }

    @Override
    public List<String> activeRoles(String organizationId, String subject) {
        String role = roles.get(key(organizationId, subject));
        return role == null ? List.of() : List.of(role);
    }

    @Override
    public boolean unitExists(String organizationId, String unitId) {
        return units.contains(key(organizationId, unitId));
    }

    @Override
    public boolean hasConnectedChart(String organizationId, String subject, String unitId) {
        // Derive candidate charts from the CURRENT tenant only, then look up the
        // exact scoped connection. Scanning all connections and discarding their
        // org prefix would let another tenant's active connection resurrect a
        // revoked local one (ARC-02, IAM-05).
        String chartPrefix = key(organizationId, subject) + "|";
        return verifiedCharts.stream()
                .filter(entry -> entry.startsWith(chartPrefix))
                .map(entry -> entry.substring(chartPrefix.length()))
                .map(patientId -> key(organizationId, patientId) + "|" + unitId)
                .anyMatch(connectionKey -> "active".equals(connections.get(connectionKey)));
    }

    @Override
    public boolean isUnitAssigned(String organizationId, String subject, String unitId) {
        return unitId.equals(assignments.get(key(organizationId, subject)));
    }

    @Override
    public Set<String> approvedChannels(String organizationId, String key) {
        return approvedChannels.getOrDefault(key(organizationId, key), Set.of());
    }

    @Override
    public Optional<ServiceProfile> findProfile(String organizationId, String profileId) {
        return Optional.ofNullable(profiles.get(key(organizationId, profileId)));
    }

    @Override
    public Optional<ServiceProfile> effectiveProfile(String organizationId, String unitId, Instant at) {
        return profiles.values().stream()
                .filter(profile -> organizationId.equals(profile.organizationId()))
                .filter(profile -> unitId.equals(profile.unitId()))
                .filter(profile -> profile.effectiveAt(at))
                .max(Comparator.comparingInt(ServiceProfile::version));
    }

    @Override
    public List<ServiceProfile> versions(String organizationId, String unitId) {
        return profiles.values().stream()
                .filter(profile -> organizationId.equals(profile.organizationId()))
                .filter(profile -> unitId.equals(profile.unitId()))
                .sorted(Comparator.comparingInt(ServiceProfile::version))
                .toList();
    }

    @Override
    public int latestVersion(String organizationId, String unitId) {
        return profiles.values().stream()
                .filter(profile -> organizationId.equals(profile.organizationId()))
                .filter(profile -> unitId.equals(profile.unitId()))
                .mapToInt(ServiceProfile::version)
                .max()
                .orElse(0);
    }

    @Override
    public ServiceProfile insertProfile(String organizationId, ServiceProfile profile) {
        profiles.put(key(organizationId, profile.id()), profile);
        return profile;
    }

    @Override
    public ServiceProfile updateProfile(String organizationId, ServiceProfile profile) {
        profiles.put(key(organizationId, profile.id()), profile);
        return profile;
    }

    @Override
    public List<WorkItemFact> workItems(String organizationId, String unitId) {
        List<WorkItemFact> items = workItems.get(key(organizationId, unitId));
        return items == null ? List.of() : List.copyOf(items);
    }

    @Override
    public void appendAudit(MessagingAudit audit) {
        audits.computeIfAbsent(audit.organizationId(), ignored -> new ArrayList<>()).add(audit);
    }

    @Override
    public List<MessagingAudit> auditEvents(String organizationId) {
        return List.copyOf(audits.getOrDefault(organizationId, List.of()));
    }

    private static String key(String organizationId, String identifier) {
        return organizationId + "|" + identifier;
    }
}
